package br.com.home.automateservice.controller;

import br.com.home.automateservice.dto.HomeAssistantAvroEvent;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.dto.HomeAssistantEventMapper;
import br.com.home.automateservice.dto.HomeAssistantEventRequest;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeAssistantLoggingController {

    private final HomeAssistantLoggingService homeAssistantLoggingService;
    private final HomeAssistantEventMapper homeAssistantEventMapper;

    public HomeAssistantLoggingController(HomeAssistantLoggingService homeAssistantLoggingService, HomeAssistantEventMapper homeAssistantEventMapper) {
        this.homeAssistantLoggingService = homeAssistantLoggingService;
        this.homeAssistantEventMapper = homeAssistantEventMapper;
    }

    @PostMapping("/logging")
    public ResponseEntity<String> sendEvent(@RequestBody @Valid HomeAssistantEventRequest homeAssistantEvent) {

        ResponseEntity<String> response = ResponseEntity.ok().build();

        try {
            homeAssistantLoggingService.push(homeAssistantEventMapper.fromHomeAssistantEventRequest(homeAssistantEvent));
        } catch(Exception e) {
            response = ResponseEntity.internalServerError().body(e.getMessage());
        }

        return response;
    }
}