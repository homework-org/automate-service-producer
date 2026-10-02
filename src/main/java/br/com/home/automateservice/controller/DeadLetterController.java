package br.com.home.automateservice.controller;

import br.com.home.automateservice.service.RedisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Operação manual sobre a dead-letter queue de eventos que não chegaram ao Kafka. */
@RestController
@RequestMapping("/admin/dlq")
public class DeadLetterController {

    private static final int MAX_LIMIT = 10_000;

    private final RedisService redisService;

    public DeadLetterController(RedisService redisService) {
        this.redisService = redisService;
    }

    /** Devolve até {@code limit} eventos da DLQ à fila de retry; o drain normal os reenvia ao Kafka. */
    @PostMapping("/reprocess")
    public Map<String, Integer> reprocess(@RequestParam(defaultValue = "1000") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return Map.of("moved", redisService.reprocessDeadLetters(bounded));
    }
}
