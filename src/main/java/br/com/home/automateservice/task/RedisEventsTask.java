package br.com.home.automateservice.task;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import br.com.home.automateservice.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedisEventsTask {
    private final Logger logger = LoggerFactory.getLogger(RedisEventsTask.class);

    private final RedisService redisService;

    private final HomeAssistantLoggingService homeAssistantLoggingService;

    public RedisEventsTask(RedisService redisService, HomeAssistantLoggingService homeAssistantLoggingService) {
        this.redisService = redisService;
        this.homeAssistantLoggingService = homeAssistantLoggingService;
    }

    @Scheduled(fixedRate = 10000)
    public void getEvents() {
        HomeAssistantEvent event = redisService.getEvent();

        try {
            if (event != null) {
                logger.info("FALLBACK - got event from queue: " + event);
                homeAssistantLoggingService.push(event);
                logger.info("FALLBACK - event sent successfully");
            }
        } catch(Exception e) {
            logger.error("FALLBACK - failed sending event, retrying");
        }
    }
}