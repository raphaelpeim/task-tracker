package com.personal.task_tracker.task.service;

import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.exception.TaskNotFoundException;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.mapper.TaskMapper;
import com.personal.task_tracker.task.repository.TaskRepository;
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
	public List<Task> getAllTasks() {
		return repository.findAll();
	}

	/**
	 * Get a specific task by its id
	 * @param id id of the task
	 * @return the corresponding task
	 */
	public Task getTaskById(Long id) {
		return repository.findById(id).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + id));
	}

	/**
	 * Create a task
	 * @param task the new task to create
	 * @return the created new task
	 */
	public Task createTask(Task task) {
		return repository.save(task);
	}

	/**
	 * Update a task
	 * @param id Id of the task to update
	 * @param task The task with the new values
	 * @return the updated task
	 */
	public Task updateTask(Long id, Task task) {
		Task taskToUpdate = getTaskById(id);

		taskToUpdate.setTitle(task.getTitle());
		taskToUpdate.setDescription(task.getDescription());
		taskToUpdate.setType(task.getType());
		taskToUpdate.setStatus(task.getStatus());
		taskToUpdate.setPriority(task.getPriority());
		taskToUpdate.setAssignee(task.getAssignee());

		return repository.save(taskToUpdate);
	}

	/**
	 * Update partially a task
	 * @param id Id of the task to update
	 * @param taskDto The task with the new values
	 * @return the updated task
	 */
	public Task updatePartialTask(Long id, TaskRequestPartialDto taskDto) {
		Task taskToUpdate = getTaskById(id);
		TaskMapper.applyPartialUpdate(taskToUpdate, taskDto);
		return repository.save(taskToUpdate);
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
}
