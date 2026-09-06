package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class HomeAssistantLoggingService {
    private final Logger logger = LoggerFactory.getLogger(HomeAssistantLoggingService.class);

    private final RedisService redisService;
    private final KafkaService kafkaService;

    public HomeAssistantLoggingService(RedisService redisService, KafkaService kafkaService) {
        this.kafkaService = kafkaService;
        this.redisService = redisService;
    }

    /**
     * Aceita o evento e tenta publicá-lo no Kafka de forma assíncrona. Qualquer falha
     * (síncrona - serialização/schema registry - ou assíncrona - broker indisponível)
     * devolve o evento à fila de fallback do Redis, drenada pelo {@code RedisEventsTask}.
     * Nunca lança, e o {@link CompletableFuture} retornado nunca completa
     * excepcionalmente: ele resolve para {@link PublishOutcome} assim que o desfecho é
     * conhecido, para que o drain possa contabilizar sucesso/falha e aplicar backpressure.
     */
    public CompletableFuture<PublishOutcome> push(HomeAssistantEvent homeAssistantEvent) {
        return attemptPublish(FallbackEnvelope.firstFailure(homeAssistantEvent));
    }

    /**
     * Reenvia um evento drenado da fila de fallback, preservando a contagem de
     * tentativas do envelope para que {@link RedisService} possa promovê-lo à DLQ
     * quando o limite for atingido - caso contrário cada reenvio recomeçaria do zero.
     */
    public CompletableFuture<PublishOutcome> retry(FallbackEnvelope envelope) {
        return attemptPublish(envelope);
    }

    private CompletableFuture<PublishOutcome> attemptPublish(FallbackEnvelope envelope) {
        HomeAssistantEvent event = envelope.event();
        try {
            return kafkaService.send(event).handle((result, ex) -> {
                if (ex != null) {
                    logger.warn("KAFKA_FALLBACK - falha no envio, devolvendo ao Redis: {}", ex.getMessage());
                    fallBack(envelope);
                    return PublishOutcome.FELL_BACK;
                }
                logger.debug("KAFKA_EVENT_SENT - evento [{}] offset [{}] topic [{}]", event,
                        result.getRecordMetadata().offset(), result.getRecordMetadata().topic());
                return PublishOutcome.PUBLISHED;
            });
        } catch (Exception e) {
            logger.warn("KAFKA_FALLBACK_SYNC - falha síncrona, devolvendo ao Redis: {}", e.getMessage());
            fallBack(envelope);
            return CompletableFuture.completedFuture(PublishOutcome.FELL_BACK);
        }
    }

    /** Devolve o evento ao Redis sem nunca propagar: {@code RedisService} já tem sua própria rede de segurança. */
    private void fallBack(FallbackEnvelope envelope) {
        try {
            redisService.enqueueForRetry(envelope);
        } catch (RuntimeException e) {
            logger.error("FALLBACK_FAILED - não foi possível devolver o evento ao Redis: {}", envelope.event(), e);
        }
    }
}
