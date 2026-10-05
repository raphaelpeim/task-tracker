package com.personal.task_tracker.user.exception;

public class UserAlreadyExistsException  extends RuntimeException {
	public UserAlreadyExistsException(String message) {
		super(message);
	}
}
