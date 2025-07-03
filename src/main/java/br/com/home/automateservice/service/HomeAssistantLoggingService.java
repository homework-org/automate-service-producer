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

    public void push(HomeAssistantEvent homeAssistantEvent) {
        try {
            kafkaService.pushEvent(homeAssistantEvent);
        } catch (Exception e) {
            logger.error(e.getMessage());

            redisService.saveEventOnQueue(homeAssistantEvent);

            throw new RuntimeException("Unable to send event [" + homeAssistantEvent + "]");
        }
    }
}