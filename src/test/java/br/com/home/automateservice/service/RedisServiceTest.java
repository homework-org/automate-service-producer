package br.com.home.automateservice.service;

import br.com.home.automateservice.config.RedisFallbackProperties;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisServiceTest {

    @SuppressWarnings("unchecked")
    private final RQueue<FallbackEnvelope> retryQueue = mock(RQueue.class);
    @SuppressWarnings("unchecked")
    private final RQueue<FallbackEnvelope> deadLetterQueue = mock(RQueue.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final HomeAssistantEvent event = new HomeAssistantEvent(
            "id-1", LocalDateTime.parse("2026-01-02T03:04:05.678"),
            "hall", "state_changed", "sensor.temperature");

    // maxQueueSize=5, maxAttempts=3, retention=24h, dlqMaxSize=10
    private final RedisFallbackProperties properties =
            new RedisFallbackProperties(5, 3, Duration.ofHours(24), 10);

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.<FallbackEnvelope>getQueue(eq(RedisService.RETRY_QUEUE_NAME), any()))
                .thenReturn(retryQueue);
        when(redissonClient.<FallbackEnvelope>getQueue(eq(RedisService.DEAD_LETTER_QUEUE_NAME), any()))
                .thenReturn(deadLetterQueue);
        redisService = new RedisService(redissonClient, properties, meterRegistry);
    }

    @Test
    void enqueuesWithAnIncrementedAttemptCountAndRefreshesTheTtlWhenUnderEveryLimit() {
        redisService.enqueueForRetry(FallbackEnvelope.firstFailure(event));

        ArgumentCaptor<FallbackEnvelope> captor = ArgumentCaptor.forClass(FallbackEnvelope.class);
        verify(retryQueue).offer(captor.capture());
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        assertThat(captor.getValue().event()).isEqualTo(event);
        verify(retryQueue).expire(Duration.ofHours(24));
        verify(deadLetterQueue, never()).offer(any());
        assertThat(meterRegistry.counter("fallback.enqueued").count()).isEqualTo(1.0);
    }

    @Test
    void movesToTheDeadLetterQueueOnceTheAttemptLimitIsExceeded() {
        // attempts already at maxAttempts -> withFailure() pushes it over
        FallbackEnvelope exhausted = new FallbackEnvelope(event, 3, System.currentTimeMillis());

        redisService.enqueueForRetry(exhausted);

        verify(deadLetterQueue).offer(any());
        verify(retryQueue, never()).offer(any());
        assertThat(meterRegistry.counter("fallback.dead_letter").count()).isEqualTo(1.0);
    }

    @Test
    void movesToTheDeadLetterQueueWhenTheEventIsOlderThanTheRetentionWindow() {
        long twoDaysAgo = System.currentTimeMillis() - Duration.ofHours(48).toMillis();
        FallbackEnvelope stale = new FallbackEnvelope(event, 1, twoDaysAgo);

        redisService.enqueueForRetry(stale);

        verify(deadLetterQueue).offer(any());
        verify(retryQueue, never()).offer(any());
    }

    @Test
    void movesToTheDeadLetterQueueWhenTheRetryQueueIsAtCapacity() {
        when(retryQueue.size()).thenReturn(5);

        redisService.enqueueForRetry(FallbackEnvelope.firstFailure(event));

        verify(deadLetterQueue).offer(any());
        verify(retryQueue, never()).offer(any());
    }

    @Test
    void trimsTheDeadLetterQueueBackToItsConfiguredMaxSize() {
        when(deadLetterQueue.size()).thenReturn(13);
        when(deadLetterQueue.poll()).thenReturn(new FallbackEnvelope(event, 0, 0L));
        FallbackEnvelope exhausted = new FallbackEnvelope(event, 3, System.currentTimeMillis());

        redisService.enqueueForRetry(exhausted);

        verify(deadLetterQueue, times(3)).poll(); // 13 - dlqMaxSize(10)
    }

    @Test
    void pollForRetryReturnsNullWhenTheQueueIsEmpty() {
        when(retryQueue.poll()).thenReturn(null);

        assertThat(redisService.pollForRetry()).isNull();
    }
}
