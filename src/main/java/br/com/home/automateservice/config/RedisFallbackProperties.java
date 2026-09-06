package br.com.home.automateservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limites da fila de fallback do Redis, usada quando o Kafka está indisponível.
 * Sem eles a fila cresce sem teto durante uma indisponibilidade prolongada
 * (risco de OOM no nó Redis) e eventos permanentemente impublicáveis
 * (ex.: schema incompatível) ciclam para sempre.
 *
 * @param maxQueueSize teto de eventos na fila de retry; ao atingir, os novos eventos vão direto para a DLQ
 * @param maxAttempts  número máximo de reenvios de um evento antes de movê-lo para a DLQ
 * @param retention    TTL da key da fila e idade máxima de um evento (contada desde a 1ª falha) antes da DLQ
 * @param dlqMaxSize   teto de eventos retidos na dead-letter queue; os mais antigos são descartados ao exceder
 */
@ConfigurationProperties(prefix = "app.redis.fallback")
public record RedisFallbackProperties(
        @DefaultValue("100000") int maxQueueSize,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("24h") Duration retention,
        @DefaultValue("10000") int dlqMaxSize) {
}
