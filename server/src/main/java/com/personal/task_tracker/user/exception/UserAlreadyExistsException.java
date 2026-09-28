package com.personal.task_tracker.user.exception;

import org.jspecify.annotations.Nullable;

public class UserAlreadyExistsException extends RuntimeException {

	private final String code;

	private UserAlreadyExistsException(String code, String message, @Nullable Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public static UserAlreadyExistsException username() {
		return username(null);
	}

	public static UserAlreadyExistsException username(@Nullable Throwable cause) {
		return new UserAlreadyExistsException("user.username.alreadyExists", "Username already exists", cause);
	}

	public static UserAlreadyExistsException email() {
		return email(null);
	}

	public static UserAlreadyExistsException email(@Nullable Throwable cause) {
		return new UserAlreadyExistsException("user.email.alreadyExists", "Email already exists", cause);
	}

	public String getCode() {
		return code;
	}
}
