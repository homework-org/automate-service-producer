package br.com.home.automateservice.task;

import br.com.home.automateservice.config.DrainProperties;
import br.com.home.automateservice.service.FallbackEnvelope;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import br.com.home.automateservice.service.PublishOutcome;
import br.com.home.automateservice.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drena a fila de fallback do Redis em lotes. Ao contrário de disparar e esquecer,
 * cada ciclo <em>aguarda</em> as confirmações do Kafka do lote antes de terminar -
 * como o agendador do Spring é single-thread, isso já serve de backpressure natural.
 * Se {@code trip-after-failed-cycles} ciclos seguidos falharem por inteiro, abre um
 * circuito que pula os próximos {@code cooldown-cycles} ciclos, evitando o loop
 * quente (e a enxurrada de logs) durante uma indisponibilidade prolongada do Kafka.
 * Os campos de estado são acessados só pela thread do agendador, que nunca sobrepõe
 * execuções do mesmo método {@code @Scheduled}.
 */
@Component
public class RedisEventsTask {

    private final Logger logger = LoggerFactory.getLogger(RedisEventsTask.class);

    private final RedisService redisService;
    private final HomeAssistantLoggingService homeAssistantLoggingService;
    private final DrainProperties drainProperties;

    private int consecutiveFailedCycles;
    private int cooldownRemaining;

    public RedisEventsTask(RedisService redisService, HomeAssistantLoggingService homeAssistantLoggingService,
                           DrainProperties drainProperties) {
        this.redisService = redisService;
        this.homeAssistantLoggingService = homeAssistantLoggingService;
        this.drainProperties = drainProperties;
    }

    @Scheduled(fixedDelay = 10000)
    public void drainFallbackQueue() {
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            logger.debug("FALLBACK_DRAIN_SKIPPED - circuito aberto, {} ciclos restantes", cooldownRemaining);
            return;
        }

        List<CompletableFuture<PublishOutcome>> inFlight = new ArrayList<>();
        for (int i = 0; i < drainProperties.maxPerCycle(); i++) {
            FallbackEnvelope envelope = redisService.pollForRetry();
            if (envelope == null) {
                break;
            }
            logger.debug("FALLBACK_RETRY - reenviando {} (tentativas={})", envelope.event(), envelope.attempts());
            inFlight.add(homeAssistantLoggingService.retry(envelope));
        }

        if (inFlight.isEmpty()) {
            consecutiveFailedCycles = 0;
            return;
        }

        int fellBack = awaitOutcomes(inFlight);
        int published = inFlight.size() - fellBack;

        if (fellBack == inFlight.size()) {
            consecutiveFailedCycles++;
            logger.warn("FALLBACK_DRAIN - ciclo falhou por inteiro ({} eventos devolvidos), sequência={}",
                    fellBack, consecutiveFailedCycles);
            if (consecutiveFailedCycles >= drainProperties.tripAfterFailedCycles()) {
                cooldownRemaining = drainProperties.cooldownCycles();
                consecutiveFailedCycles = 0;
                logger.warn("FALLBACK_DRAIN - circuito aberto pelos próximos {} ciclos", drainProperties.cooldownCycles());
            }
        } else {
            consecutiveFailedCycles = 0;
            logger.info("FALLBACK_DRAIN - {} reenviados, {} devolvidos à fila", published, fellBack);
        }
    }

    private int awaitOutcomes(List<CompletableFuture<PublishOutcome>> inFlight) {
        try {
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new))
                    .get(drainProperties.awaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            logger.warn("FALLBACK_DRAIN - {} confirmações não chegaram em {}",
                    inFlight.stream().filter(f -> !f.isDone()).count(), drainProperties.awaitTimeout());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("FALLBACK_DRAIN - espera pelas confirmações encerrou com erro: {}", cause.toString());
        }

        int fellBack = 0;
        for (CompletableFuture<PublishOutcome> future : inFlight) {
            PublishOutcome outcome;
            try {
                outcome = future.getNow(PublishOutcome.FELL_BACK);
            } catch (CompletionException | CancellationException ex) {
                outcome = PublishOutcome.FELL_BACK;
            }
            if (outcome != PublishOutcome.PUBLISHED) {
                fellBack++;
            }
        }
        return fellBack;
    }
}
