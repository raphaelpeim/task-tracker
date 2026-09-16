package com.personal.task_tracker.service;

import com.personal.task_tracker.exception.TaskNotFoundException;
import com.personal.task_tracker.entity.Task;
import com.personal.task_tracker.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {
	private final TaskRepository taskRepository;

	public TaskService(TaskRepository taskRepository) {
		this.taskRepository = taskRepository;
	}

	/**
	 * Get all tasks
	 * @return all tasks
	 */
	public List<Task> getAllTasks() {
		return taskRepository.findAll();
	}

	/**
	 * Get a specific task by its id
	 * @param id id of the task
	 * @return the corresponding task
	 */
	public Task getTaskById(Long id) {
		return taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + id));
	}

	/**
	 * Create a task
	 * @param task the new task to create
	 * @return the created new task
	 */
	public Task createTask(Task task) {
		return taskRepository.save(task);
	}
}
