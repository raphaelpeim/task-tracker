package com.personal.task_tracker.controller;

import com.personal.task_tracker.dto.task.CreateTaskDto;
import com.personal.task_tracker.dto.task.TaskDto;
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
	public ResponseEntity<List<TaskDto>> getAllTasks() {
		List<TaskDto> taskDtoList = taskService.getAllTasks().stream().map(TaskMapper::toDto).toList();
		return new ResponseEntity<>(taskDtoList, HttpStatus.OK);
	}

	@GetMapping("/{taskId}")
	public ResponseEntity<TaskDto> getTask(
			@PathVariable Long taskId
	) {
		TaskDto taskDto = TaskMapper.toDto(taskService.getTaskById(taskId));
		return new ResponseEntity<>(taskDto, HttpStatus.OK);
	}

	@PostMapping
	public ResponseEntity<TaskDto> createTask(
			@Valid @RequestBody CreateTaskDto createTaskDto
	) {
		Task task = taskService.createTask(TaskMapper.toEntity(createTaskDto));
		TaskDto taskDto = TaskMapper.toDto(task);
		return new ResponseEntity<>(taskDto, HttpStatus.CREATED);
	}
}
