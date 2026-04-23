package com.example.task_queue.queue;

import com.example.task_queue.entity.Task;
import com.example.task_queue.repository.TaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisQueueWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final TaskRepository taskRepository;

    public static final String TASK_QUEUE = "task_queue";
    public static final String PROCESSING_QUEUE = "processing_queue";
    public static final String DEAD_LETTER_QUEUE = "dead_letter_queue";
    public static final String DELAYED_QUEUE = "delayed_retry_queue";

    private ExecutorService executorService;
    private volatile boolean running = true;

    private static final int WORKER_COUNT = 10;
    private static final int MAX_RETRIES = 3;

    @PostConstruct
    public void startWorkers() {
        executorService = Executors.newFixedThreadPool(WORKER_COUNT);
        for (int i = 0; i < WORKER_COUNT; i++) {
            executorService.submit(this::processTasks);
        }
        log.info("Started {} worker threads", WORKER_COUNT);
    }

    private void processTasks() {
        while (running) {
            try {
                // Implement At-Least-Once Delivery using BRPOPLPUSH
                // Blocks up to 5 seconds
                String taskIdStr = redisTemplate.opsForList()
                        .rightPopAndLeftPush(TASK_QUEUE, PROCESSING_QUEUE, 5, TimeUnit.SECONDS);

                if (taskIdStr != null) {
                    processTask(taskIdStr);
                }
            } catch (Exception e) {
                log.error("Exception in worker thread during fetch: ", e);
                try {
                    Thread.sleep(1000); // Backoff on Redis connection error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processTask(String taskIdStr) {
        log.info("Processing Task ID: {}", taskIdStr);
        try {
            UUID taskId = UUID.fromString(taskIdStr);
            Optional<Task> taskOpt = taskRepository.findById(taskId);

            if (taskOpt.isEmpty()) {
                log.warn("Task {} not found in DB. Discarding.", taskIdStr);
                removeFromProcessingQueue(taskIdStr);
                return;
            }

            Task task = taskOpt.get();
            
            // Mark task as processing
            task.setStatus("PROCESSING");
            taskRepository.save(task);

            // Simulate doing some work
            simulateWork(task);

            // On success
            task.setStatus("SUCCESS");
            taskRepository.save(task);
            log.info("Successfully processed task {}", taskIdStr);

            // Remove from processing queue only after successful DB update
            removeFromProcessingQueue(taskIdStr);

        } catch (Exception e) {
            log.error("Task {} failed to process: {}", taskIdStr, e.getMessage());
            handleTaskFailure(taskIdStr);
        }
    }

    private void simulateWork(Task task) throws Exception {
        // Simulate computation or network call
        Thread.sleep(500); 
        
        // Specific mock failure
        if ("fail".equalsIgnoreCase(task.getPayload())) {
            throw new RuntimeException("Simulated failure due to payload content");
        }
    }

    private void handleTaskFailure(String taskIdStr) {
        try {
            UUID taskId = UUID.fromString(taskIdStr);
            Optional<Task> taskOpt = taskRepository.findById(taskId);
            
            if (taskOpt.isPresent()) {
                Task task = taskOpt.get();
                int retries = task.getRetries() + 1;
                task.setRetries(retries);
                
                if (retries <= MAX_RETRIES) {
                    // Exponential backoff
                    task.setStatus("RETRYING");
                    taskRepository.save(task);
                    
                    double waitTimeSeconds = Math.pow(2, retries); 
                    long nextExecutionTime = Instant.now().getEpochSecond() + (long)waitTimeSeconds;
                    
                    // Add to Delayed Queue
                    redisTemplate.opsForZSet().add(DELAYED_QUEUE, taskIdStr, nextExecutionTime);
                    log.info("Task {} retry {}/{} scheduled in {} seconds", taskIdStr, retries, MAX_RETRIES, waitTimeSeconds);
                } else {
                    // Maximum retries reached.
                    task.setStatus("FAILED");
                    taskRepository.save(task);
                    
                    // Push to Dead Letter Queue
                    redisTemplate.opsForList().leftPush(DEAD_LETTER_QUEUE, taskIdStr);
                    log.info("Task {} failed permanently up to {} retries. Moved to DLQ.", taskIdStr, MAX_RETRIES);
                }
            }
        } finally {
            removeFromProcessingQueue(taskIdStr);
        }
    }

    private void removeFromProcessingQueue(String taskIdStr) {
        redisTemplate.opsForList().remove(PROCESSING_QUEUE, 1, taskIdStr);
    }

    @PreDestroy
    public void shutdownWorkers() {
        running = false;
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }
}
