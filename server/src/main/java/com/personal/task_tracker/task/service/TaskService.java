package com.personal.task_tracker.task.service;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.exception.TaskNotFoundException;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.mapper.TaskMapper;
import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.task.validator.TaskUpdatePartialValidator;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class TaskService {
	private final UserService userService;
	private final TaskRepository repository;

	public TaskService(UserService userService, TaskRepository repository) {
		this.userService = userService;
		this.repository = repository;
	}

	/**
	 * Get all tasks
	 * @return all tasks
	 */
	public List<TaskResponseDto> getAllTasks() {
		return repository.findAll().stream().map(TaskMapper::toDto).toList();
	}

	/**
	 * Get task by id
	 * @param taskId id of the task
	 * @return the corresponding task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	public TaskResponseDto getTaskById(Long taskId) {
		return TaskMapper.toDto(findTaskById(taskId));
	}

	/**
	 * Create a task
	 * @param taskDto task to create data
	 * @return the created task
	 */
	@Transactional
	public TaskResponseDto createTask(TaskRequestDto taskDto) {
		Task task = TaskMapper.toEntity(taskDto);
		Task createdTask = repository.save(task);
		return TaskMapper.toDto(createdTask);
	}

	/**
	 * Update a task
	 * @param taskId Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	@Transactional
	public TaskResponseDto updateTask(Long taskId, TaskRequestDto taskDto) {
		Task task = findTaskById(taskId);
		Optional<AppUser> user = userService.findUserById(taskDto.assigneeId());

		TaskMapper.applyUpdate(task, taskDto);

		repository.flush();

		return TaskMapper.toDto(task);
	}

	/**
	 * Update partially a task
	 * @param taskId Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	@Transactional
	public TaskResponseDto updatePartialTask(Long taskId, TaskRequestPartialDto taskDto) {
		TaskUpdatePartialValidator.validate(taskDto);

		Task task = findTaskById(taskId);

		TaskMapper.applyPartialUpdate(task, taskDto);

		repository.flush();

		return TaskMapper.toDto(task);
	}

	/**
	 * Delete a task
	 * @param taskId Id of the task to delete
	 */
	@Transactional
	public void deleteTask(Long taskId) {
		if (!repository.existsById(taskId)) {
			throw new TaskNotFoundException("There is no task with id: " + taskId);
		}
		repository.deleteById(taskId);
	}

	/**
	 * Get task by id
	 * @param taskId id of the task
	 * @return the corresponding task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	private Task findTaskById(Long taskId) {
		return repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + taskId));
	}
}
