package br.com.home.automateservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Ajustes do ciclo de drenagem da fila de fallback ({@code RedisEventsTask}).
 *
 * @param maxPerCycle           máximo de eventos retirados da fila por ciclo
 * @param awaitTimeout          espera máxima pelas confirmações do Kafka antes de encerrar o ciclo
 * @param tripAfterFailedCycles ciclos consecutivos totalmente falhos que abrem o circuito
 * @param cooldownCycles        ciclos pulados enquanto o circuito está aberto
 * @param autoReprocessDlq      se {@code true}, devolve a DLQ à fila quando o Kafka se recupera de uma falha
 * @param dlqBatchSize          eventos devolvidos da DLQ por ciclo saudável até esvaziá-la
 */
@ConfigurationProperties(prefix = "app.redis.fallback.drain")
public record DrainProperties(
        @DefaultValue("200") int maxPerCycle,
        @DefaultValue("30s") Duration awaitTimeout,
        @DefaultValue("3") int tripAfterFailedCycles,
        @DefaultValue("6") int cooldownCycles,
        @DefaultValue("true") boolean autoReprocessDlq,
        @DefaultValue("200") int dlqBatchSize) {
}
