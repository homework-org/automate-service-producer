package br.com.home.automateservice.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class HomeAssistantEventMapperTest {

    private final HomeAssistantEventMapper mapper = new HomeAssistantEventMapper();

    private static final LocalDateTime WHEN = LocalDateTime.parse("2026-01-02T03:04:05.678");

    @Test
    void fromRequestCopiesEveryField() {
        HomeAssistantEventRequest request = new HomeAssistantEventRequest(
                "id-1", WHEN, "hall", "state_changed", "sensor.temperature");

        HomeAssistantEvent event = mapper.fromHomeAssistantEventRequest(request);

        assertThat(event.id()).isEqualTo("id-1");
        assertThat(event.timeFired()).isEqualTo(WHEN);
        assertThat(event.device()).isEqualTo("hall");
        assertThat(event.eventType()).isEqualTo("state_changed");
        assertThat(event.entityId()).isEqualTo("sensor.temperature");
    }

    @Test
    void toAvroSerialisesTimeFiredAsEpochMillisTreatingTheLocalTimeAsUtc() {
        HomeAssistantEvent event = new HomeAssistantEvent(
                "id-1", WHEN, "hall", "state_changed", "sensor.temperature");

        HomeAssistantAvroEvent avro = mapper.toHomeAssistantAvroEvent(event);

        assertThat(avro.getTimeFired()).isEqualTo(WHEN.toInstant(ZoneOffset.UTC).toEpochMilli());
        assertThat(avro.getTimeFired()).isEqualTo(Instant.parse("2026-01-02T03:04:05.678Z").toEpochMilli());
    }

    @Test
    void avroRoundTripPreservesAllFieldsIncludingMillis() {
        HomeAssistantEvent original = new HomeAssistantEvent(
                "id-1", WHEN, "hall", "state_changed", "sensor.temperature");

        HomeAssistantEvent restored = mapper.fromHomeAssistantAvroEvent(mapper.toHomeAssistantAvroEvent(original));

        assertThat(restored).isEqualTo(original);
    }
}
