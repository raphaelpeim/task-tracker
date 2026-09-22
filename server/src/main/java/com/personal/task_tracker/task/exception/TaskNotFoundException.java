package com.personal.task_tracker.task.exception;

public class TaskNotFoundException extends RuntimeException {
	public TaskNotFoundException(String message) {
		super(message);
	}
}
