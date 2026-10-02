package br.com.home.automateservice.controller;

import br.com.home.automateservice.service.RedisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeadLetterController.class)
class DeadLetterControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RedisService redisService;

    @Test
    void reprocessesWithTheDefaultLimitAndReportsHowManyWereMoved() throws Exception {
        when(redisService.reprocessDeadLetters(1000)).thenReturn(42);

        mvc.perform(post("/admin/dlq/reprocess"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(42));
    }

    @Test
    void clampsTheRequestedLimit() throws Exception {
        when(redisService.reprocessDeadLetters(10_000)).thenReturn(0);

        mvc.perform(post("/admin/dlq/reprocess").param("limit", "999999")).andExpect(status().isOk());
        verify(redisService).reprocessDeadLetters(10_000);
    }
}
