package br.com.home.automateservice.service;

import br.com.home.automateservice.config.RedisFallbackProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Fila de fallback do Redis para eventos que não puderam ser publicados no Kafka.
 * A fila é limitada em três dimensões (ver {@link RedisFallbackProperties}): número
 * de tentativas por evento, idade do evento e tamanho total da fila. Ao estourar
 * qualquer limite o evento é movido para a dead-letter queue em vez de ciclar
 * indefinidamente ou fazer a fila crescer sem teto.
 */
@Service
public class RedisService {
    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);

    public static final String RETRY_QUEUE_NAME = "home-assistant-events";
    public static final String DEAD_LETTER_QUEUE_NAME = "home-assistant-events:dlq";

    /**
     * Rede de segurança em memória para quando o próprio Redis está indisponível no
     * momento de devolver um evento à fila. Cobre blips curtos; é perdida se o
     * processo morrer, então é mitigação, não garantia.
     */
    private static final int LOCAL_BUFFER_LIMIT = 1000;

    private final RQueue<FallbackEnvelope> retryQueue;
    private final RQueue<FallbackEnvelope> deadLetterQueue;
    private final RedisFallbackProperties properties;
    private final Deque<FallbackEnvelope> localBuffer = new ConcurrentLinkedDeque<>();

    private final Counter enqueued;
    private final Counter deadLettered;
    private final Counter enqueueFailed;
    private final Counter reprocessed;

    public RedisService(RedissonClient redissonClient, RedisFallbackProperties properties, MeterRegistry meterRegistry) {
        // Codec com o tipo fixado em FallbackEnvelope: o JSON não carrega o nome da
        // classe (nada de @JsonTypeInfo/@class), evitando o vetor de desserialização
        // polimórfica. Serve para as duas filas, ambas de FallbackEnvelope.
        Codec codec = new TypedJsonJacksonCodec(FallbackEnvelope.class);
        this.retryQueue = redissonClient.getQueue(RETRY_QUEUE_NAME, codec);
        this.deadLetterQueue = redissonClient.getQueue(DEAD_LETTER_QUEUE_NAME, codec);
        this.properties = properties;

        this.enqueued = meterRegistry.counter("fallback.enqueued");
        this.deadLettered = meterRegistry.counter("fallback.dead_letter");
        this.enqueueFailed = meterRegistry.counter("fallback.enqueue_failed");
        this.reprocessed = meterRegistry.counter("fallback.dlq.reprocessed");
        Gauge.builder("fallback.queue.depth", retryQueue, RQueue::size)
                .description("Eventos aguardando reenvio ao Kafka na fila de fallback do Redis")
                .register(meterRegistry);
        Gauge.builder("fallback.dlq.depth", deadLetterQueue, RQueue::size)
                .description("Eventos descartados para a dead-letter queue")
                .register(meterRegistry);
        Gauge.builder("fallback.local_buffer.depth", localBuffer, Deque::size)
                .description("Eventos retidos em memória porque o Redis estava indisponível na devolução")
                .register(meterRegistry);
    }

    /**
     * Registra a falha de reenvio de um evento e o devolve à fila - ou à DLQ, se
     * algum limite foi atingido, nesta ordem:
     * <ol>
     *   <li>tentativas &gt; {@code app.redis.fallback.max-attempts};</li>
     *   <li>idade desde a 1ª falha &gt; {@code app.redis.fallback.retention};</li>
     *   <li>fila cheia ({@code app.redis.fallback.max-queue-size}) - o evento novo vai para a DLQ,
     *       preservando o backlog FIFO já enfileirado.</li>
     * </ol>
     * A key da fila recebe TTL igual à retenção a cada escrita, então uma fila
     * ociosa é coletada automaticamente. <strong>Nunca lança:</strong> se o Redis
     * estiver indisponível o evento é retido em memória e reinserido numa chamada
     * futura (ou descartado, com log e métrica, se o buffer local encher).
     */
    public void enqueueForRetry(FallbackEnvelope envelope) {
        flushLocalBuffer();
        persist(envelope.withFailure());
    }

    /**
     * Retira o próximo envelope da fila de retry, ou {@code null} se ela estiver
     * vazia ou o Redis indisponível. Aproveita para reinserir o que ficou no
     * buffer local enquanto o Redis esteve fora.
     */
    public FallbackEnvelope pollForRetry() {
        try {
            flushLocalBuffer();
            FallbackEnvelope polled = retryQueue.poll();
            if (polled != null) {
                logger.debug("POLL - retirado da fila de fallback {} (tentativas={})", polled.event(), polled.attempts());
            }
            return polled;
        } catch (RuntimeException redisDown) {
            logger.warn("POLL_FAILED - Redis indisponível na drenagem: {}", redisDown.getMessage());
            return null;
        }
    }

    /**
     * Devolve até {@code limit} eventos da DLQ (mais antigos primeiro) à fila de retry,
     * com tentativas zeradas e retenção reiniciada - sem isso eles voltariam direto
     * para a DLQ. Para quando a DLQ esvazia, o {@code limit} é atingido ou a fila de
     * retry enche (os restantes ficam na DLQ). Cada evento é copiado para a fila de
     * retry <em>antes</em> de sair da DLQ, então uma falha do Redis no meio não perde
     * eventos (no pior caso um evento fica duplicado, e o Kafka já é at-least-once).
     * Se o Redis estiver indisponível, devolve o que conseguiu mover até então.
     *
     * @return quantos eventos foram movidos
     */
    public synchronized int reprocessDeadLetters(int limit) {
        int moved = 0;
        try {
            while (moved < limit && retryQueue.size() < properties.maxQueueSize()) {
                FallbackEnvelope next = deadLetterQueue.peek();
                if (next == null) {
                    break;
                }
                retryQueue.offer(next.reset());
                deadLetterQueue.poll();
                moved++;
            }
            if (moved > 0) {
                retryQueue.expire(properties.retention());
                reprocessed.increment(moved);
                logger.info("DLQ_REPROCESSED - {} evento(s) devolvido(s) da DLQ à fila de retry", moved);
            }
        } catch (RuntimeException redisDown) {
            logger.warn("DLQ_REPROCESS_FAILED - Redis indisponível após mover {} evento(s): {}",
                    moved, redisDown.getMessage());
        }
        return moved;
    }

    private void persist(FallbackEnvelope incremented) {
        try {
            persistToRedis(incremented);
        } catch (RuntimeException redisDown) {
            bufferLocally(incremented, redisDown);
        }
    }

    private void persistToRedis(FallbackEnvelope envelope) {
        if (envelope.attempts() > properties.maxAttempts()) {
            deadLetter(envelope, "MAX_ATTEMPTS");
            return;
        }
        if (envelope.olderThan(properties.retention())) {
            deadLetter(envelope, "RETENTION_EXCEEDED");
            return;
        }
        if (retryQueue.size() >= properties.maxQueueSize()) {
            deadLetter(envelope, "QUEUE_FULL");
            return;
        }
        retryQueue.offer(envelope);
        retryQueue.expire(properties.retention());
        enqueued.increment();
    }

    private void flushLocalBuffer() {
        for (FallbackEnvelope buffered = localBuffer.poll(); buffered != null; buffered = localBuffer.poll()) {
            try {
                persistToRedis(buffered);
            } catch (RuntimeException redisStillDown) {
                localBuffer.addFirst(buffered);
                return;
            }
        }
    }

    private void bufferLocally(FallbackEnvelope envelope, RuntimeException cause) {
        enqueueFailed.increment();
        if (localBuffer.size() >= LOCAL_BUFFER_LIMIT) {
            logger.error("FALLBACK_LOST - Redis indisponível e buffer local cheio ({}); evento descartado: {}",
                    LOCAL_BUFFER_LIMIT, envelope.event(), cause);
            return;
        }
        logger.warn("FALLBACK_BUFFERED - Redis indisponível, retendo evento em memória: {}", envelope.event());
        localBuffer.offer(envelope);
    }

    private void deadLetter(FallbackEnvelope envelope, String reason) {
        logger.warn("DEAD_LETTER - evento movido para a DLQ (motivo={}, tentativas={}): {}",
                reason, envelope.attempts(), envelope.event());
        deadLetterQueue.offer(envelope);
        deadLetterQueue.expire(properties.retention());
        trimDeadLetterQueue();
        deadLettered.increment();
    }

    private void trimDeadLetterQueue() {
        int overflow = deadLetterQueue.size() - properties.dlqMaxSize();
        for (int i = 0; i < overflow; i++) {
            if (deadLetterQueue.poll() == null) {
                return;
            }
        }
    }
}
