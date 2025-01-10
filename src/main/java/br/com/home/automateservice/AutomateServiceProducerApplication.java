package br.com.home.automateservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication
@Configuration
public class AutomateServiceProducerApplication {
	public static void main(String[] args) {
		SpringApplication.run(AutomateServiceProducerApplication.class, args);
	}

	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.build();
	}
}