package com.personal.task_tracker.controller;

import com.personal.task_tracker.dto.task.TaskRequestDto;
import com.personal.task_tracker.dto.task.TaskRequestPartialDto;
import com.personal.task_tracker.dto.task.TaskResponseDto;
import com.personal.task_tracker.entity.Task;
import com.personal.task_tracker.mapper.TaskMapper;
import com.personal.task_tracker.service.TaskService;
import com.personal.task_tracker.validator.TaskUpdatePartialValidator;
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
		List<TaskResponseDto> taskResponseDtoList = taskService.getAllTasks().stream().map(TaskMapper::toDto).toList();
		return new ResponseEntity<>(taskResponseDtoList, HttpStatus.OK);
	}

	@GetMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> getTask(
			@PathVariable Long taskId
	) {
		TaskResponseDto taskResponseDto = TaskMapper.toDto(taskService.getTaskById(taskId));
		return new ResponseEntity<>(taskResponseDto, HttpStatus.OK);
	}

	@PostMapping
	public ResponseEntity<TaskResponseDto> createTask(
			@Valid @RequestBody TaskRequestDto taskDto
	) {
		Task task = taskService.createTask(TaskMapper.toEntity(taskDto));
		TaskResponseDto taskResponseDto = TaskMapper.toDto(task);
		return new ResponseEntity<>(taskResponseDto, HttpStatus.CREATED);
	}

	@PutMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> updateTask(
			@PathVariable Long taskId,
			@Valid @RequestBody TaskRequestDto taskDto
	) {
		Task task = taskService.updateTask(taskId, TaskMapper.toEntity(taskDto));
		TaskResponseDto taskResponseDto = TaskMapper.toDto(task);
		return new ResponseEntity<>(taskResponseDto, HttpStatus.OK);
	}

	@PatchMapping("/{taskId}")
	public ResponseEntity<TaskResponseDto> updatePartialTask(
			@PathVariable Long taskId,
			@Valid @RequestBody TaskRequestPartialDto taskDto
	) {
		TaskUpdatePartialValidator.validate(taskDto);
		Task task = taskService.updatePartialTask(taskId, taskDto);
		TaskResponseDto taskResponseDto = TaskMapper.toDto(task);
		return new ResponseEntity<>(taskResponseDto, HttpStatus.OK);
	}

	@DeleteMapping("/{taskId}")
	public ResponseEntity<Void> deleteTask(
			@PathVariable Long taskId
	) {
		taskService.deleteTask(taskId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
