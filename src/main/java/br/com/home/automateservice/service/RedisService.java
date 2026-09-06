package br.com.home.automateservice.service;

import br.com.home.automateservice.config.RedisFallbackProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    private final RQueue<FallbackEnvelope> retryQueue;
    private final RQueue<FallbackEnvelope> deadLetterQueue;
    private final RedisFallbackProperties properties;

    private final Counter enqueued;
    private final Counter deadLettered;

    public RedisService(RedissonClient redissonClient, RedisFallbackProperties properties, MeterRegistry meterRegistry) {
        JsonJacksonCodec codec = new JsonJacksonCodec();
        this.retryQueue = redissonClient.getQueue(RETRY_QUEUE_NAME, codec);
        this.deadLetterQueue = redissonClient.getQueue(DEAD_LETTER_QUEUE_NAME, codec);
        this.properties = properties;

        this.enqueued = meterRegistry.counter("fallback.enqueued");
        this.deadLettered = meterRegistry.counter("fallback.dead_letter");
        Gauge.builder("fallback.queue.depth", retryQueue, RQueue::size)
                .description("Eventos aguardando reenvio ao Kafka na fila de fallback do Redis")
                .register(meterRegistry);
        Gauge.builder("fallback.dlq.depth", deadLetterQueue, RQueue::size)
                .description("Eventos descartados para a dead-letter queue")
                .register(meterRegistry);
    }

    /**
     * Registra a falha de reenvio de um evento e o reenfileira, a menos que algum
     * limite tenha sido atingido - caso em que ele vai para a dead-letter queue.
     * Ordem de verificação:
     * <ol>
     *   <li>tentativas &gt; {@code app.redis.fallback.max-attempts};</li>
     *   <li>idade desde a 1ª falha &gt; {@code app.redis.fallback.retention};</li>
     *   <li>fila cheia ({@code app.redis.fallback.max-queue-size}) - o evento novo vai para a DLQ,
     *       preservando o backlog FIFO já enfileirado.</li>
     * </ol>
     * A key da fila recebe TTL igual à retenção a cada escrita, então uma fila
     * ociosa (serviço parado, backlog abandonado) é coletada automaticamente.
     */
    public void enqueueForRetry(FallbackEnvelope envelope) {
        FallbackEnvelope next = envelope.withFailure();

        if (next.attempts() > properties.maxAttempts()) {
            deadLetter(next, "MAX_ATTEMPTS");
            return;
        }
        if (next.olderThan(properties.retention())) {
            deadLetter(next, "RETENTION_EXCEEDED");
            return;
        }
        if (retryQueue.size() >= properties.maxQueueSize()) {
            deadLetter(next, "QUEUE_FULL");
            return;
        }

        retryQueue.offer(next);
        retryQueue.expire(properties.retention());
        enqueued.increment();
    }

    /** Retira o próximo envelope da fila de retry, ou {@code null} se ela estiver vazia. */
    public FallbackEnvelope pollForRetry() {
        FallbackEnvelope polled = retryQueue.poll();
        if (polled != null) {
            logger.info("POLL - retirado da fila de fallback {} (tentativas={})", polled.event(), polled.attempts());
        }
        return polled;
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
