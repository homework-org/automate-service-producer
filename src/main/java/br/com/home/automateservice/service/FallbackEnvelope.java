package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantEvent;

import java.time.Duration;

/**
 * O que a fila de fallback do Redis realmente armazena: o evento mais o número
 * de falhas de envio já acumuladas e o instante da primeira falha. {@link RedisService}
 * usa esses dois contadores para decidir entre reenfileirar o evento e promovê-lo
 * à dead-letter queue.
 *
 * @param event               o evento a reenviar ao Kafka
 * @param attempts            falhas de envio já registradas para este evento
 * @param firstFailureEpochMs epoch millis da primeira falha, base para a retenção máxima
 */
public record FallbackEnvelope(HomeAssistantEvent event, int attempts, long firstFailureEpochMs) {

    /** Envelope para a primeira falha de um evento recém-recebido (ainda sem tentativas contabilizadas). */
    public static FallbackEnvelope firstFailure(HomeAssistantEvent event) {
        return new FallbackEnvelope(event, 0, System.currentTimeMillis());
    }

    /** Cópia com mais uma falha contabilizada, preservando o instante da primeira falha. */
    public FallbackEnvelope withFailure() {
        return new FallbackEnvelope(event, attempts + 1, firstFailureEpochMs);
    }

    /** {@code true} se a primeira falha ocorreu há mais que {@code retention}. */
    public boolean olderThan(Duration retention) {
        return System.currentTimeMillis() - firstFailureEpochMs > retention.toMillis();
    }
}
