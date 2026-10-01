package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.Codec;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que {@link FallbackEnvelope} (e o {@link HomeAssistantEvent} aninhado, com
 * {@code LocalDateTime} e milissegundos) faz round-trip pelo codec real da fila do
 * Redis - {@code TypedJsonJacksonCodec} - <em>sem</em> depender de {@code @JsonTypeInfo}
 * no payload. É a verificação do P0-4 e fecha a ponta solta de serialização do P0-2/P0-3.
 */
class FallbackEnvelopeCodecTest {

    private final Codec codec = new TypedJsonJacksonCodec(FallbackEnvelope.class);

    private static final LocalDateTime WHEN = LocalDateTime.parse("2026-01-02T03:04:05.678");

    @Test
    void roundTripsThroughRedissonCodecWithoutInBandClassInformation() throws Exception {
        FallbackEnvelope original = new FallbackEnvelope(
                new HomeAssistantEvent("id-1", WHEN, "hall", "state_changed", "sensor.temperature"),
                3, 1_767_326_645_678L);

        ByteBuf encoded = codec.getValueEncoder().encode(original);
        String json = encoded.toString(CharsetUtil.UTF_8);
        Object decoded = codec.getValueDecoder().decode(encoded, null);

        assertThat(json).doesNotContain("@class");
        assertThat(decoded).isInstanceOf(FallbackEnvelope.class).isEqualTo(original);

        FallbackEnvelope back = (FallbackEnvelope) decoded;
        assertThat(back.event().timeFired()).isEqualTo(WHEN);
        assertThat(back.attempts()).isEqualTo(3);
        assertThat(back.firstFailureEpochMs()).isEqualTo(1_767_326_645_678L);
    }
}
