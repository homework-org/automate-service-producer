package br.com.home.automateservice.service;

import br.com.home.automateservice.config.KafkaProperties;
import br.com.home.automateservice.dto.HomeAssistantAvroEvent;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.dto.HomeAssistantEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaServiceTest {

    @Mock
    private KafkaTemplate<String, Object> template;

    @Test
    void sendMapsToAvroAndPublishesToConfiguredTopic() {
        KafkaService service = new KafkaService(
                new KafkaProperties("test-topic"), template, new HomeAssistantEventMapper());
        HomeAssistantEvent event = new HomeAssistantEvent(
                "id-1", LocalDateTime.parse("2026-01-02T03:04:05.678"),
                "living-room", "state_changed", "sensor.temperature");
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(template.send(eq("test-topic"), any())).thenReturn(future);

        CompletableFuture<SendResult<String, Object>> result = service.send(event);

        assertThat(result).isSameAs(future);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).send(eq("test-topic"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(HomeAssistantAvroEvent.class);
        assertThat(((HomeAssistantAvroEvent) payload.getValue()).getEntityId()).isEqualTo("sensor.temperature");
    }
}
