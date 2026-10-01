package br.com.home.automateservice.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.DelayStrategy;

import java.io.InputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida o {@code redisson.yml} real. No Redisson 3.50.0 o {@code EqualJitterDelay}
 * padrão da {@code reconnectionDelay} estoura {@code long} a partir da 57ª tentativa
 * de reconexão e lança {@code IllegalArgumentException} dentro do
 * {@code ConnectionWatchdog}: a reconexão daquela conexão morre e o pool passa a
 * entregar canais fechados para sempre ("Unable to write command into connection").
 * Uma indisponibilidade do Redis de poucos minutos já basta para chegar lá.
 */
class RedissonConfigTest {

    @Test
    void reconnectionDelayStaysValidForLongOutages() throws Exception {
        Config config;
        try (InputStream yaml = getClass().getResourceAsStream("/redisson.yml")) {
            config = Config.fromYAML(yaml);
        }
        DelayStrategy reconnectionDelay = config.useSingleServer().getReconnectionDelay();

        // ~horas de reconexão com o teto de 10s; cobre com folga o ponto de overflow (57).
        for (int attempt = 0; attempt < 10_000; attempt++) {
            Duration delay = reconnectionDelay.calcDelay(attempt);
            assertThat(delay).as("delay na tentativa %d", attempt)
                    .isBetween(Duration.ZERO, Duration.ofSeconds(10));
        }
    }
}
