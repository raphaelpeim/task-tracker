package com.personal.task_tracker.task.service;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.exception.TaskNotFoundException;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.mapper.TaskMapper;
import com.personal.task_tracker.task.repository.TaskRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {
	private final TaskRepository repository;

	public TaskService(TaskRepository repository) {
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
	 * @param id id of the task
	 * @return the corresponding task
	 */
	public TaskResponseDto getTaskById(Long id) {
		return TaskMapper.toDto(findTaskById(id));
	}

	/**
	 * Create a task
	 * @param taskDto task to create data
	 * @return the created task
	 */
	public TaskResponseDto createTask(TaskRequestDto taskDto) {
		Task task = TaskMapper.toEntity(taskDto);
		Task createdTask = repository.save(task);
		return TaskMapper.toDto(createdTask);
	}

	/**
	 * Update a task
	 * @param id Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 */
	public TaskResponseDto updateTask(Long id, TaskRequestDto taskDto) {
		Task taskToUpdate = findTaskById(id);

		taskToUpdate.setTitle(taskDto.title());
		taskToUpdate.setDescription(taskDto.description());
		taskToUpdate.setType(taskDto.type());
		taskToUpdate.setStatus(taskDto.status());
		taskToUpdate.setPriority(taskDto.priority());
		taskToUpdate.setAssignee(taskDto.assignee());

		Task saved = repository.save(taskToUpdate);

		return TaskMapper.toDto(saved);
	}

	/**
	 * Update partially a task
	 * @param id Id of the task to update
	 * @param taskDto task to update data
	 * @return the updated task
	 */
	@Transactional
	public TaskResponseDto updatePartialTask(Long id, TaskRequestPartialDto taskDto) {
		Task taskToUpdate = findTaskById(id);

		TaskMapper.applyPartialUpdate(taskToUpdate, taskDto);

		Task saved = repository.save(taskToUpdate);

		return TaskMapper.toDto(saved);
	}

	/**
	 * Delete a task
	 * @param id Id of the task to delete
	 */
	public void deleteTask(Long id) {
		if (!repository.existsById(id)) {
			throw new TaskNotFoundException("There is no task with id: " + id);
		}
		repository.deleteById(id);
	}

	/**
	 * Get task by id
	 * @param id id of the task
	 * @return the corresponding task
	 */
	private Task findTaskById(Long id) {
		return repository.findById(id).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + id));
	}
}
