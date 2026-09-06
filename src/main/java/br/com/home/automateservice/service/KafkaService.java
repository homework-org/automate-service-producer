package br.com.home.automateservice.service;

import br.com.home.automateservice.config.KafkaProperties;
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
public class KafkaService {
    private static final Logger logger = LoggerFactory.getLogger(KafkaService.class);

    private final KafkaTemplate<String, Object> template;
    private final HomeAssistantEventMapper homeAssistantEventMapper;

    private final String topic;

    public KafkaService(KafkaProperties kafkaProperties, KafkaTemplate<String, Object> template, HomeAssistantEventMapper homeAssistantEventMapper) {
        this.template = template;
        this.topic = kafkaProperties.topic();
        this.homeAssistantEventMapper = homeAssistantEventMapper;
    }

    /**
     * Envia o evento ao Kafka de forma assíncrona. O {@link CompletableFuture} retornado
     * completa com sucesso quando o broker confirma o envio (acks=all) e completa
     * excepcionalmente em caso de falha - cabe ao chamador tratar o fallback.
     */
    public CompletableFuture<SendResult<String, Object>> send(HomeAssistantEvent homeAssistantEvent) {
        HomeAssistantAvroEvent homeAssistantAvroEvent = homeAssistantEventMapper.toHomeAssistantAvroEvent(homeAssistantEvent);
        return template.send(topic, homeAssistantAvroEvent);
    }

    @PreDestroy
    public void close() {
        if (template != null) {
            logger.info("Closing producer");
            template.destroy();
        }
    }
}