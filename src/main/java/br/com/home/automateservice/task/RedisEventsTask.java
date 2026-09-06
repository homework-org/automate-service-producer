package br.com.home.automateservice.task;

import br.com.home.automateservice.service.FallbackEnvelope;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import br.com.home.automateservice.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedisEventsTask {
    private static final int MAX_PER_CYCLE = 200;

    private final Logger logger = LoggerFactory.getLogger(RedisEventsTask.class);

    private final RedisService redisService;

    private final HomeAssistantLoggingService homeAssistantLoggingService;

    public RedisEventsTask(RedisService redisService, HomeAssistantLoggingService homeAssistantLoggingService) {
        this.redisService = redisService;
        this.homeAssistantLoggingService = homeAssistantLoggingService;
    }

    /**
     * Drena a fila de fallback do Redis em lote. {@code retry} reenfileira o evento
     * sozinho se o reenvio ao Kafka falhar de novo, e {@link RedisService} o move
     * para a dead-letter queue quando os limites da fila são atingidos.
     */
    @Scheduled(fixedRate = 10000)
    public void drainFallbackQueue() {
        for (int i = 0; i < MAX_PER_CYCLE; i++) {
            FallbackEnvelope envelope = redisService.pollForRetry();
            if (envelope == null) {
                return;
            }
            logger.info("FALLBACK_RETRY - reenviando evento {} (tentativas={})", envelope.event(), envelope.attempts());
            homeAssistantLoggingService.retry(envelope);
        }
    }
}
