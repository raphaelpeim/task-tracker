package com.personal.task_tracker.task.controller;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
	private final TaskService taskService;

	public TaskController(TaskService taskService) {
		this.taskService = taskService;
	}

	@GetMapping
	public ResponseEntity<List<TaskResponseDto>> getAllTasks() {
		List<TaskResponseDto> tasksDto = taskService.getAllTasks();
		return new ResponseEntity<>(tasksDto, HttpStatus.OK);
	}

	@GetMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> getTask(
			@PathVariable Long taskId
	) {
		TaskResponseDto taskDto = taskService.getTaskById(taskId);
		return new ResponseEntity<>(taskDto, HttpStatus.OK);
	}

	@PostMapping
	public ResponseEntity<TaskResponseDto> createTask(
			@Valid @RequestBody TaskRequestDto taskDto
	) {
		TaskResponseDto createdTaskDto = taskService.createTask(taskDto);
		return new ResponseEntity<>(createdTaskDto, HttpStatus.CREATED);
	}

	@PutMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> updateTask(
			@PathVariable Long taskId,
			@Valid @RequestBody TaskRequestDto taskDto
	) {
		TaskResponseDto updatedTaskDto = taskService.updateTask(taskId, taskDto);
		return new ResponseEntity<>(updatedTaskDto, HttpStatus.OK);
	}

	@PatchMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> updatePartialTask(
			@PathVariable Long taskId,
			@Valid @RequestBody TaskRequestPartialDto taskDto
	) {
		TaskResponseDto updateTaskDto = taskService.updatePartialTask(taskId, taskDto);
		return new ResponseEntity<>(updateTaskDto, HttpStatus.OK);
	}

	@DeleteMapping("/{taskId}")
	public ResponseEntity<Void> deleteTask(
			@PathVariable Long taskId
	) {
		taskService.deleteTask(taskId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
