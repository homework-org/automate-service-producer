package br.com.home.automateservice.config;

import org.springframework.beans.factory.annotation.Value;

public class KafkaConfig {
    @Value("${spring.kafka.topic}")
    private String topic;

    public KafkaConfig() {
    }

    public KafkaConfig(String topic) {
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
