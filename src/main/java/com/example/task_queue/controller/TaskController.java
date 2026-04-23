package com.example.task_queue.controller;

import com.example.task_queue.dto.TaskRequest;
import com.example.task_queue.entity.Task;
import com.example.task_queue.queue.RedisQueueProducer;
import com.example.task_queue.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final RedisQueueProducer queueProducer;
    private final TaskRepository taskRepository;

    @PostMapping
    public ResponseEntity<Task> submitTask(@RequestBody TaskRequest request) {
        Task createdTask = queueProducer.submitTask(request.getPayload());
        return ResponseEntity.ok(createdTask);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getTask(@PathVariable UUID id) {
        return taskRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
