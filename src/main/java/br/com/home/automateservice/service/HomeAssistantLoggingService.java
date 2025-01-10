package br.com.home.automateservice.service;

import br.com.home.automateservice.config.ServiceConfig;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class HomeAssistantLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(HomeAssistantLoggingService.class);

    private final KafkaTemplate<String, Object> template;

    private final ServiceConfig serviceConfig;

    public HomeAssistantLoggingService(KafkaTemplate<String, Object> template, ServiceConfig serviceConfig) {
        this.template = template;
        this.serviceConfig = serviceConfig;
    }

    public HomeAssistantEvent push(HomeAssistantEvent event) {
        String topic = serviceConfig.getKafkaConfig().getTopic();

        CompletableFuture<SendResult<String, Object>> future = template.send(topic, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.info("Event sent to {}", topic);
            } else {
                logger.error("Error sending event to {}", topic);
                throw new KafkaException(String.format("Error sending event to %s", topic));
            }
        });

        return event;
    }
}