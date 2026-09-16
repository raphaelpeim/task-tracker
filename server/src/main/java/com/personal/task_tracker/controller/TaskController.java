package com.personal.task_tracker.controller;

import com.personal.task_tracker.dto.task.TaskRequestDto;
import com.personal.task_tracker.dto.task.TaskResponseDto;
import com.personal.task_tracker.entity.Task;
import com.personal.task_tracker.mapper.TaskMapper;
import com.personal.task_tracker.service.TaskService;
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
			@Valid @RequestBody TaskRequestDto taskRequestDto
	) {
		Task task = taskService.createTask(TaskMapper.toEntity(taskRequestDto));
		TaskResponseDto taskResponseDto = TaskMapper.toDto(task);
		return new ResponseEntity<>(taskResponseDto, HttpStatus.CREATED);
	}
}
