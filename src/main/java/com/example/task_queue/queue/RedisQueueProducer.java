package com.example.task_queue.queue;

import com.example.task_queue.entity.Task;
import com.example.task_queue.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisQueueProducer {

    private final TaskRepository taskRepository;
    private final RedisTemplate<String, String> redisTemplate;
    
    private static final String TASK_QUEUE = "task_queue";

    public Task submitTask(String payload) {
        // 1. Create and store the task in PostgreSQL with status "PENDING"
        Task task = Task.builder()
                .payload(payload)
                .status("PENDING")
                .retries(0)
                .build();
        
        task = taskRepository.save(task);
        log.info("Task {} saved to DB with status PENDING", task.getId());
        
        // 2. Push only the task ID into the Redis list
        redisTemplate.opsForList().leftPush(TASK_QUEUE, task.getId().toString());
        log.info("Task {} pushed to Redis task_queue", task.getId());
        
        return task;
    }
}
