package com.example.task_queue.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DelayedQueueScheduler {

    private final RedisTemplate<String, String> redisTemplate;

    @Scheduled(fixedRate = 1000)
    public void pollDelayedQueue() {
        long currentTimestamp = Instant.now().getEpochSecond();
        
        Set<String> tasks = redisTemplate.opsForZSet().rangeByScore(
                RedisQueueWorker.DELAYED_QUEUE, 0, currentTimestamp);
                
        if (tasks != null && !tasks.isEmpty()) {
            for (String taskId : tasks) {
                Long removed = redisTemplate.opsForZSet().remove(RedisQueueWorker.DELAYED_QUEUE, taskId);
                if (removed != null && removed > 0) {
                    redisTemplate.opsForList().leftPush(RedisQueueWorker.TASK_QUEUE, taskId);
                    log.info("Re-queued task {} from delayed queue to task_queue", taskId);
                }
            }
        }
    }
}
