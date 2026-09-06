package br.com.home.automateservice.controller;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.dto.HomeAssistantEventMapper;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HomeAssistantLoggingController.class)
class HomeAssistantLoggingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HomeAssistantLoggingService loggingService;

    @MockitoBean
    private HomeAssistantEventMapper mapper;

    private static final String VALID_BODY = """
            {"id":"id-1","timeFired":"2026-01-02T03:04:05.678Z","device":"hall",
             "eventType":"state_changed","entityId":"sensor.temperature"}
            """;

    @Test
    void acceptsAValidPayloadAndForwardsTheMappedEventToTheService() throws Exception {
        HomeAssistantEvent mapped = new HomeAssistantEvent(
                "id-1", LocalDateTime.parse("2026-01-02T03:04:05.678"),
                "hall", "state_changed", "sensor.temperature");
        when(mapper.fromHomeAssistantEventRequest(any())).thenReturn(mapped);

        mvc.perform(post("/logging").contentType(APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());

        verify(loggingService).push(mapped);
    }

    @Test
    void rejectsABlankIdWith400AndAnInvalidParamsProblemDetail() throws Exception {
        String blankId = VALID_BODY.replace("\"id-1\"", "\"  \"");

        mvc.perform(post("/logging").contentType(APPLICATION_JSON).content(blankId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request params"))
                .andExpect(jsonPath("$.['invalid-params'][0].name").value("id"));

        verifyNoInteractions(loggingService);
    }
}
