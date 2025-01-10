package br.com.home.automateservice.controller;

import br.com.home.automateservice.dto.HomeAssistantEvent;
import br.com.home.automateservice.service.HomeAssistantLoggingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeAssistantLoggingController {

    private final HomeAssistantLoggingService homeAssistantLoggingService;

    public HomeAssistantLoggingController(HomeAssistantLoggingService homeAssistantLoggingService) {
        this.homeAssistantLoggingService = homeAssistantLoggingService;
    }

    @PostMapping("/logging")
    public ResponseEntity<HomeAssistantEvent> sendEvent(@RequestBody @Valid HomeAssistantEvent event) {

        var saved = homeAssistantLoggingService.push(event);

        return ResponseEntity.ok(saved);
    }
}