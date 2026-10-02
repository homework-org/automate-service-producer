package br.com.home.automateservice.task;

import br.com.home.automateservice.config.DrainProperties;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.service.FallbackEnvelope;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import br.com.home.automateservice.service.PublishOutcome;
import br.com.home.automateservice.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisEventsTaskTest {

    @Mock
    private RedisService redisService;

    @Mock
    private HomeAssistantLoggingService loggingService;

    private RedisEventsTask task;

    private static final DrainProperties DRAIN = new DrainProperties(200, Duration.ofSeconds(5), 3, 6, true, 2);

    @BeforeEach
    void setUp() {
        task = new RedisEventsTask(redisService, loggingService, DRAIN);
    }

    @Test
    void drainsEveryQueuedEventInOrderThenStopsWhenQueueIsEmpty() {
        FallbackEnvelope first = envelope("1");
        FallbackEnvelope second = envelope("2");
        when(redisService.pollForRetry()).thenReturn(first, second, null);
        when(loggingService.retry(any())).thenReturn(published());

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
        task = new RedisEventsTask(redisService, loggingService, new DrainProperties(5, Duration.ofSeconds(5), 3, 6, true, 2));
        when(redisService.pollForRetry()).thenReturn(envelope("x"));
        when(loggingService.retry(any())).thenReturn(published());

        task.drainFallbackQueue();

        verify(redisService, times(5)).pollForRetry();
        verify(loggingService, times(5)).retry(any());
    }

    @Test
    void awaitsTheBatchOutcomesBeforeReturning() {
        CompletableFuture<PublishOutcome> pending = new CompletableFuture<>();
        when(redisService.pollForRetry()).thenReturn(envelope("1")).thenReturn(null);
        when(loggingService.retry(any())).thenReturn(pending);
        pending.complete(PublishOutcome.PUBLISHED); // already resolved: allOf().get() returns immediately

        task.drainFallbackQueue();

        verify(loggingService).retry(any());
    }

    @Test
    void opensTheCircuitAfterConsecutiveFullyFailedCyclesThenResumesAfterCooldown() {
        when(redisService.pollForRetry()).thenReturn(
                envelope("a"), null,   // cycle 1 -> full fail, streak 1
                envelope("b"), null,   // cycle 2 -> full fail, streak 2
                envelope("c"), null,   // cycle 3 -> full fail, trips (cooldown = 6)
                envelope("d"), null);  // consumed only after cooldown elapses
        when(loggingService.retry(any())).thenReturn(fellBack());

        task.drainFallbackQueue();
        task.drainFallbackQueue();
        task.drainFallbackQueue();
        verify(loggingService, times(3)).retry(any());
        verify(redisService, times(6)).pollForRetry();

        for (int i = 0; i < 6; i++) {
            task.drainFallbackQueue(); // circuit open: skipped, no polling
        }
        verify(loggingService, times(3)).retry(any());
        verify(redisService, times(6)).pollForRetry();

        task.drainFallbackQueue(); // cooldown elapsed
        verify(loggingService, times(4)).retry(any());
        verify(redisService, times(8)).pollForRetry();
    }

    @Test
    void aPartiallySuccessfulCycleResetsTheFailureStreakAndKeepsTheCircuitClosed() {
        when(redisService.pollForRetry()).thenReturn(
                envelope("1"), null,
                envelope("2"), null,
                envelope("3"), null,
                envelope("4"), null,
                envelope("5"), null);
        when(loggingService.retry(any())).thenReturn(
                fellBack(),    // cycle 1 -> streak 1
                fellBack(),    // cycle 2 -> streak 2
                published(),   // cycle 3 -> streak back to 0
                fellBack(),    // cycle 4 -> streak 1
                fellBack());   // cycle 5 -> streak 2, never trips

        for (int i = 0; i < 5; i++) {
            task.drainFallbackQueue();
        }

        verify(loggingService, times(5)).retry(any());
        verify(redisService, times(10)).pollForRetry(); // 2 per cycle, nothing skipped
    }

    @Test
    void reprocessesTheDlqOnTheFirstHealthyCycleAfterAFullyFailedOne() {
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null, envelope("b"), null);
        when(loggingService.retry(any())).thenReturn(fellBack(), published());
        when(redisService.reprocessDeadLetters(2)).thenReturn(1);

        task.drainFallbackQueue(); // full failure arms the trigger
        verify(redisService, never()).reprocessDeadLetters(anyInt());

        task.drainFallbackQueue(); // healthy -> Kafka recovered
        verify(redisService).reprocessDeadLetters(2);

        task.drainFallbackQueue(); // empty cycle, trigger already consumed (moved < batch)
        verify(redisService, times(1)).reprocessDeadLetters(anyInt());
    }

    @Test
    void neverReprocessesTheDlqWithoutAPriorFullFailure() {
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null);
        when(loggingService.retry(any())).thenReturn(published());

        task.drainFallbackQueue();
        task.drainFallbackQueue();

        verify(redisService, never()).reprocessDeadLetters(anyInt());
    }

    @Test
    void aPartiallyFailedCycleDoesNotTriggerTheReprocessEvenWhenArmed() {
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null, envelope("b"), envelope("c"), null);
        when(loggingService.retry(any())).thenReturn(fellBack(), published(), fellBack());

        task.drainFallbackQueue(); // full failure -> armed
        task.drainFallbackQueue(); // 1 published + 1 fell back -> partial, no trigger

        verify(redisService, never()).reprocessDeadLetters(anyInt());
    }

    @Test
    void keepsReprocessingBatchByBatchWhileTheDlqReturnsFullBatches() {
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null, null, null, null);
        when(loggingService.retry(any())).thenReturn(fellBack());
        when(redisService.reprocessDeadLetters(2)).thenReturn(2, 2, 1);

        task.drainFallbackQueue(); // arm
        task.drainFallbackQueue(); // empty/healthy -> 2 moved (full batch)
        task.drainFallbackQueue(); // empty/healthy -> 2 moved (full batch)
        task.drainFallbackQueue(); // empty/healthy -> 1 moved (done)
        task.drainFallbackQueue(); // nothing more

        verify(redisService, times(3)).reprocessDeadLetters(2);
    }

    @Test
    void aNewFullFailureRearmsTheTriggerAfterTheDlqWasDrained() {
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null, null, envelope("b"), null, null);
        when(loggingService.retry(any())).thenReturn(fellBack(), fellBack());
        when(redisService.reprocessDeadLetters(2)).thenReturn(0);

        task.drainFallbackQueue(); // fail -> armed
        task.drainFallbackQueue(); // empty -> reprocess (0), disarmed
        task.drainFallbackQueue(); // empty -> no reprocess
        task.drainFallbackQueue(); // fail -> armed again
        task.drainFallbackQueue(); // empty -> reprocess

        verify(redisService, times(2)).reprocessDeadLetters(2);
    }

    @Test
    void doesNotReprocessWhenAutoReprocessIsDisabled() {
        task = new RedisEventsTask(redisService, loggingService,
                new DrainProperties(200, Duration.ofSeconds(5), 3, 6, false, 2));
        when(redisService.pollForRetry()).thenReturn(envelope("a"), null, null);
        when(loggingService.retry(any())).thenReturn(fellBack());

        task.drainFallbackQueue();
        task.drainFallbackQueue();

        verify(redisService, never()).reprocessDeadLetters(anyInt());
    }

    private static CompletableFuture<PublishOutcome> published() {
        return CompletableFuture.completedFuture(PublishOutcome.PUBLISHED);
    }

    private static CompletableFuture<PublishOutcome> fellBack() {
        return CompletableFuture.completedFuture(PublishOutcome.FELL_BACK);
    }

    private static FallbackEnvelope envelope(String id) {
        return FallbackEnvelope.firstFailure(new HomeAssistantEvent(id, LocalDateTime.parse("2026-01-02T03:04:05.678"),
                "hall", "state_changed", "sensor.temperature"));
    }
}
