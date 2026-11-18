package com.personal.task_tracker.auth.exception;

/**
 * Raised by the login when the email is unknown or the password is wrong: both cases give the same error, so that the
 * response never tells whether an email is registered.
 * Not an AuthenticationException: it is a business error of the login endpoint, with its own code
 * ("auth.invalidCredentials"), not a missing or invalid token.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid email or password");
	}
}
