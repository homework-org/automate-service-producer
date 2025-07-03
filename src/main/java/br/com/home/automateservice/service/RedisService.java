package br.com.home.automateservice.service;

import br.com.home.automateservice.dto.HomeAssistantAvroEvent;
import br.com.home.automateservice.dto.HomeAssistantEvent;
import org.redisson.api.RBucket;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisService {
    private final Logger logger = LoggerFactory.getLogger(RedisService.class);

    private final RedissonClient redissonClient;
    public static final String RETRY_QUEUE_NAME = "home-assistant-events";
    private final RQueue<HomeAssistantEvent> retryQueue;

    public RedisService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.retryQueue = redissonClient.getQueue(RETRY_QUEUE_NAME, new JsonJacksonCodec());
    }

    public void saveEvent(HomeAssistantAvroEvent event) {
        RBucket<HomeAssistantAvroEvent> bucket = redissonClient.getBucket(String.format(event.getId()));
        bucket.expire(Duration.ofDays(1));
        bucket.set(event);
    }

    public void saveEventOnQueue(HomeAssistantEvent event) {
        retryQueue.offer(event);
    }

    public HomeAssistantEvent getEvent() {
        HomeAssistantEvent polledEvent = retryQueue.poll();

        if(polledEvent != null) {
            logger.info("POLL - polled event [" + polledEvent + "]");
        }

        return polledEvent;
    }
}