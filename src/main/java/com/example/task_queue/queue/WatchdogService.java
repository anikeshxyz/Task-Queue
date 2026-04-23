package com.example.task_queue.queue;

import com.example.task_queue.entity.Task;
import com.example.task_queue.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchdogService {

    private final RedisTemplate<String, String> redisTemplate;
    private final TaskRepository taskRepository;

    // Time threshold in minutes for considering a task stuck
    private static final int STUCK_THRESHOLD_MINUTES = 5;

    @Scheduled(fixedRate = 60000) // Runs every minute
    public void requeueStuckTasks() {
        log.info("Watchdog checking for stuck tasks...");
        List<String> processingTasks = redisTemplate.opsForList().range(RedisQueueWorker.PROCESSING_QUEUE, 0, -1);
        
        if (processingTasks == null || processingTasks.isEmpty()) {
            return;
        }

        LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);

        for (String taskIdStr : processingTasks) {
            try {
                UUID taskId = UUID.fromString(taskIdStr);
                Optional<Task> taskOpt = taskRepository.findById(taskId);
                
                if (taskOpt.isPresent()) {
                    Task task = taskOpt.get();
                    
                    if ("PROCESSING".equals(task.getStatus()) && task.getUpdatedAt().isBefore(thresholdTime)) {
                        log.warn("Watchdog detected stuck task: {}. Re-queueing.", taskIdStr);
                        
                        redisTemplate.opsForList().leftPush(RedisQueueWorker.TASK_QUEUE, taskIdStr);
                        redisTemplate.opsForList().remove(RedisQueueWorker.PROCESSING_QUEUE, 1, taskIdStr);
                        
                        task.setStatus("RETRYING");
                        taskRepository.save(task);
                    }
                } else {
                    redisTemplate.opsForList().remove(RedisQueueWorker.PROCESSING_QUEUE, 1, taskIdStr);
                }
            } catch (Exception e) {
                log.error("Watchdog failed for task {}", taskIdStr, e);
            }
        }
    }
}
