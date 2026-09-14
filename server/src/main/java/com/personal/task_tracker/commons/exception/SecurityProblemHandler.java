package com.personal.task_tracker.commons.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Writes the 401 and 403 raised by the security filters: they happen before any controller, so GlobalExceptionHandler
 * never sees them. The body is the same ProblemDetail as the one returned by GlobalExceptionHandler.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	/**
	 * The mixin puts the ProblemDetail properties ("code") at the root of the JSON, as Spring MVC does
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder()
			.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
			.build();

	@Override
	public void commence(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull AuthenticationException ex)
			throws IOException {
		write(request, response, GlobalExceptionHandler.unauthorized());
	}

	@Override
	public void handle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull AccessDeniedException ex)
			throws IOException {
		write(request, response, GlobalExceptionHandler.forbidden());
	}

	private static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
			throws IOException {
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		MAPPER.writeValue(response.getOutputStream(), problem);
	}
}
