package com.personal.task_tracker.commons.exception;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Raises the security exceptions from a controller, for SecurityErrorsTest.
 * Top-level class: Spring Boot excludes the classes nested in a test class from component scanning.
 */
@RestController
class SecurityErrorsTestController {

	@GetMapping("/test/security/unauthorized")
	void unauthorized() {
		throw new InsufficientAuthenticationException("internal message");
	}

	@GetMapping("/test/security/forbidden")
	void forbidden() {
		throw new AccessDeniedException("internal message");
	}
}
