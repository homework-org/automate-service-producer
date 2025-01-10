package br.com.home.automateservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfig {

    @Bean
    public KafkaConfig getKafkaConfig() {
        return new KafkaConfig();
    }
}
