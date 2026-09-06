package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
     * enfileira o evento no Redis, que é drenado pelo {@code RedisEventsTask}.
     * Nunca lança: o contrato do endpoint é "aceito para processamento".
     */
    public void push(HomeAssistantEvent homeAssistantEvent) {
        attemptPublish(FallbackEnvelope.firstFailure(homeAssistantEvent));
    }

    /**
     * Reenvia um evento drenado da fila de fallback. Recebe o envelope original para
     * que a contagem de tentativas seja preservada e {@link RedisService} possa
     * promovê-lo à dead-letter queue quando o limite for atingido - caso contrário
     * cada reenvio recomeçaria a contagem do zero.
     */
    public void retry(FallbackEnvelope envelope) {
        attemptPublish(envelope);
    }

    private void attemptPublish(FallbackEnvelope envelope) {
        HomeAssistantEvent event = envelope.event();
        try {
            kafkaService.send(event).whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.warn("KAFKA_FALLBACK - falha no envio, enfileirando no Redis: {}", ex.getMessage());
                    redisService.enqueueForRetry(envelope);
                } else {
                    logger.info("KAFKA_EVENT_SENT - evento [{}] offset [{}] topic [{}]", event,
                            result.getRecordMetadata().offset(), result.getRecordMetadata().topic());
                }
            });
        } catch (Exception e) {
            logger.warn("KAFKA_FALLBACK_SYNC - falha síncrona, enfileirando no Redis: {}", e.getMessage());
            redisService.enqueueForRetry(envelope);
        }
    }
}
