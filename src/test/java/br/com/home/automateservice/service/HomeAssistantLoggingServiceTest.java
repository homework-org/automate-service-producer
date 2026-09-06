package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeAssistantLoggingServiceTest {

    @Mock
    private KafkaService kafkaService;

    @Mock
    private RedisService redisService;

    @InjectMocks
    private HomeAssistantLoggingService service;

    private final HomeAssistantEvent event = new HomeAssistantEvent(
            "id-1", LocalDateTime.parse("2026-01-02T03:04:05.678"),
            "hall", "state_changed", "sensor.temperature");

    @Test
    void publishesWithoutTouchingRedisWhenTheKafkaSendSucceeds() {
        when(kafkaService.send(event)).thenReturn(CompletableFuture.completedFuture(sendResult()));

        CompletableFuture<PublishOutcome> result = service.push(event);

        assertThat(result.getNow(null)).isEqualTo(PublishOutcome.PUBLISHED);
        verifyNoInteractions(redisService);
    }

    @Test
    void fallsBackToRedisWhenTheKafkaFutureFailsAsynchronously() {
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaService.send(event)).thenReturn(failed);

        CompletableFuture<PublishOutcome> result = service.push(event);

        assertThat(result.getNow(null)).isEqualTo(PublishOutcome.FELL_BACK);
        verify(redisService).enqueueForRetry(argThat(e -> e.event().equals(event) && e.attempts() == 0));
    }

    @Test
    void fallsBackToRedisWhenTheKafkaSendFailsSynchronously() {
        when(kafkaService.send(event)).thenThrow(new RuntimeException("schema registry down"));

        CompletableFuture<PublishOutcome> result = service.push(event);

        assertThat(result).isCompletedWithValue(PublishOutcome.FELL_BACK);
        verify(redisService).enqueueForRetry(argThat(e -> e.event().equals(event) && e.attempts() == 0));
    }

    @Test
    void neverPropagatesWhenBothTheKafkaSendAndTheRedisFallbackFail() {
        when(kafkaService.send(event)).thenThrow(new RuntimeException("schema registry down"));
        doThrow(new RuntimeException("redis down")).when(redisService).enqueueForRetry(any());

        assertThatCode(() -> service.push(event)).doesNotThrowAnyException();
    }

    @Test
    void retryPreservesTheEnvelopeSoTheAttemptCountKeepsGrowingTowardsTheDeadLetterQueue() {
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException());
        when(kafkaService.send(any())).thenReturn(failed);
        FallbackEnvelope drained = new FallbackEnvelope(event, 4, System.currentTimeMillis());

        service.retry(drained);

        ArgumentCaptor<FallbackEnvelope> captor = ArgumentCaptor.forClass(FallbackEnvelope.class);
        verify(redisService).enqueueForRetry(captor.capture());
        assertThat(captor.getValue()).isSameAs(drained);
    }

    private static SendResult<String, Object> sendResult() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("automate-events", 0), 0L, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>("automate-events", null, null), metadata);
    }
}
