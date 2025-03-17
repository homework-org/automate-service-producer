package br.com.home.automateservice.service;

import br.com.home.automateservice.config.ServiceConfig;
import br.com.home.automateservice.dto.HomeAssistantAvroEvent;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.dto.HomeAssistantEventMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class HomeAssistantLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(HomeAssistantLoggingService.class);

    private final KafkaTemplate<String, Object> template;
    private final HomeAssistantEventMapper homeAssistantEventMapper;

    private final String topic;

    public HomeAssistantLoggingService(KafkaTemplate<String, Object> template, ServiceConfig serviceConfig, HomeAssistantEventMapper homeAssistantEventMapper) {
        this.template = template;
        this.homeAssistantEventMapper = homeAssistantEventMapper;

        topic = serviceConfig.getKafkaConfig().getTopic();
    }

    public void push(HomeAssistantEvent homeAssistantEvent) {
        try {
            HomeAssistantAvroEvent homeAssistantAvroEvent = homeAssistantEventMapper.toHomeAssistantAvroEvent(homeAssistantEvent);

            CompletableFuture<SendResult<String, Object>> future = template.send(topic, homeAssistantAvroEvent);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Sent event [{}] with offset [{}] to [{}]", homeAssistantEvent,
                            result.getRecordMetadata().offset(), topic);
                } else {
                    logger.error("Unable to send event [{}] with offset [{}] to {}", homeAssistantEvent,
                            result.getRecordMetadata().offset(), topic);
                }
            });
        } catch (Exception e) {
            logger.error("Unable to send event [{}] to {}", homeAssistantEvent, topic);
            throw new RuntimeException("Unable to send event [" + homeAssistantEvent + "]");
        }
    }

    @PreDestroy
    public void close() {
        if (template != null) {
            logger.info("Closing producer");
            template.destroy();
        }
    }
}