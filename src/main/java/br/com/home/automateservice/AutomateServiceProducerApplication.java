package br.com.home.automateservice;

import br.com.home.automateservice.config.KafkaProperties;
import br.com.home.automateservice.config.RedisFallbackProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({KafkaProperties.class, RedisFallbackProperties.class})
public class AutomateServiceProducerApplication {
	public static void main(String[] args) {
		SpringApplication.run(AutomateServiceProducerApplication.class, args);
	}
}
