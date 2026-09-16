package com.personal.task_tracker.task.service;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.exception.TaskNotFoundException;
import com.personal.task_tracker.task.mapper.TaskMapper;
import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.exception.UserNotFoundException;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
	 *
	 * @return all tasks, sorted by id
	 */
	public List<TaskResponseDto> getAllTasks() {
		return repository.findAll(Sort.by("id")).stream().map(TaskMapper::toTaskResponseDto).toList();
	}

	/**
	 * Get task by id
	 *
	 * @param taskId id of the task
	 * @return the corresponding task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	public TaskResponseDto getTaskById(Long taskId) {
		return TaskMapper.toTaskResponseDto(findTaskById(taskId));
	}

	/**
	 * Create a task
	 *
	 * @param taskDto task to create data
	 * @return the created task
	 * @throws UserNotFoundException error thrown if the assignee does not exist
	 */
	@Transactional
	public TaskResponseDto createTask(TaskRequestDto taskDto) {
		Task task = TaskMapper.toEntity(taskDto);

		if (taskDto.assigneeId() != null) {
			AppUser user = userService.findUserById(taskDto.assigneeId());
			task.setAssignee(user);
		}

		Task createdTask = repository.save(task);

		return TaskMapper.toTaskResponseDto(createdTask);
	}

	/**
	 * Update a task
	 *
	 * @param taskId  Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 * @throws UserNotFoundException error thrown if the assignee does not exist
	 */
	@Transactional
	public TaskResponseDto updateTask(Long taskId, TaskRequestDto taskDto) {
		Task task = findTaskById(taskId);

		TaskMapper.applyUpdate(task, taskDto);

		if (taskDto.assigneeId() == null) {
			task.setAssignee(null);
		} else {
			AppUser user = userService.findUserById(taskDto.assigneeId());
			task.setAssignee(user);
		}

		repository.flush();

		return TaskMapper.toTaskResponseDto(task);
	}

	/**
	 * Update partially a task
	 *
	 * @param taskId  Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 * @throws UserNotFoundException error thrown if the assignee does not exist
	 */
	@Transactional
	public TaskResponseDto updatePartialTask(Long taskId, TaskRequestPartialDto taskDto) {
		Task task = findTaskById(taskId);

		TaskMapper.applyPartialUpdate(task, taskDto);

		if (taskDto.assigneeId().isPresent()) {
			if (taskDto.assigneeId().get() == null) {
				task.setAssignee(null);
			} else {
				AppUser user = userService.findUserById(taskDto.assigneeId().get());
				task.setAssignee(user);
			}
		}

		repository.flush();

		return TaskMapper.toTaskResponseDto(task);
	}

	/**
	 * Delete a task
	 *
	 * @param taskId Id of the task to delete
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	@Transactional
	public void deleteTask(Long taskId) {
		Task task = findTaskById(taskId);
		repository.delete(task);
	}

	/**
	 * Get task by id
	 *
	 * @param taskId id of the task
	 * @return the corresponding task
	 * @throws TaskNotFoundException error thrown if no tasks found
	 */
	private Task findTaskById(Long taskId) {
		return repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + taskId));
	}
}
