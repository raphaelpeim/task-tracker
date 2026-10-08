package com.personal.task_tracker.task.exception;

public class InvalidTaskUpdateException extends RuntimeException {
	public InvalidTaskUpdateException(String message) {
		super(message);
	}
}
