package br.com.home.automateservice.task;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.service.FallbackEnvelope;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import br.com.home.automateservice.service.RedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisEventsTaskTest {

    /** Mirrors the private {@code RedisEventsTask.MAX_PER_CYCLE}. */
    private static final int MAX_PER_CYCLE = 200;

    @Mock
    private RedisService redisService;

    @Mock
    private HomeAssistantLoggingService loggingService;

    @InjectMocks
    private RedisEventsTask task;

    @Test
    void drainsEveryQueuedEventInOrderThenStopsWhenQueueIsEmpty() {
        FallbackEnvelope first = envelope("1");
        FallbackEnvelope second = envelope("2");
        when(redisService.pollForRetry()).thenReturn(first, second, null);

        task.drainFallbackQueue();

        InOrder ordered = inOrder(loggingService);
        ordered.verify(loggingService).retry(first);
        ordered.verify(loggingService).retry(second);
        verifyNoMoreInteractions(loggingService);
    }

    @Test
    void doesNothingWhenTheQueueIsEmpty() {
        when(redisService.pollForRetry()).thenReturn(null);

        task.drainFallbackQueue();

        verifyNoInteractions(loggingService);
    }

    @Test
    void stopsAtMaxPerCycleEvenWhenTheQueueStillHasEvents() {
        when(redisService.pollForRetry()).thenReturn(envelope("x"));

        task.drainFallbackQueue();

        verify(redisService, times(MAX_PER_CYCLE)).pollForRetry();
        verify(loggingService, times(MAX_PER_CYCLE)).retry(any());
    }

    private static FallbackEnvelope envelope(String id) {
        return FallbackEnvelope.firstFailure(new HomeAssistantEvent(id, LocalDateTime.parse("2026-01-02T03:04:05.678"),
                "hall", "state_changed", "sensor.temperature"));
    }
}
