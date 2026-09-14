package com.personal.task_tracker.commons.exception;

import jakarta.validation.ConstraintViolation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Every error follows RFC 9457 (ProblemDetail) with an extra "code" property: a translation key for the front,
 * "detail" being the English sentence.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final String CODE = "code";
	private static final List<String> CONSTRAINT_PRIORITY = List.of("NotNull", "NotBlank", "Size", "MaxBytes", "Email");
	static final String INVALID_FORMAT = "invalidFormat";
	static final ResourceBundle MESSAGES = ResourceBundle.getBundle("ValidationMessages");
	static final Map<Class<?>, String> DOMAINS = Map.of();

	// REQUESTS

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			@NonNull MethodArgumentNotValidException ex,
			@NonNull HttpHeaders headers,
			@NonNull HttpStatusCode status,
			@NonNull WebRequest request
	) {
		// One error per field: when several constraints fail, keep the most basic one first
		Map<String, FieldError> fieldErrors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.merge(error.getField(), error, (current, other) -> priority(other) < priority(current) ? other : current);
		}
		List<Map<String, String>> errors = fieldErrors.values().stream().map(GlobalExceptionHandler::toError).toList();

		return handleExceptionInternal(ex, validationProblem(status, errors), headers, status, request);
	}

	private static int priority(FieldError error) {
		int index = CONSTRAINT_PRIORITY.indexOf(error.getCode());
		return index == -1 ? CONSTRAINT_PRIORITY.size() : index;
	}

	/**
	 * The constraint message is a key between braces ("{task.title.required}"): the key goes in "code",
	 * the sentence it resolves to in ValidationMessages.properties goes in "detail".
	 */
	private static Map<String, String> toError(FieldError error) {
		String code = error.getCode();
		if (error.contains(ConstraintViolation.class)) {
			String template = error.unwrap(ConstraintViolation.class).getMessageTemplate();
			code = template.replaceAll("^\\{|}$", "");
		}
		Map<String, String> fieldError = new LinkedHashMap<>();
		fieldError.put("pointer", "#/" + error.getField());
		fieldError.put(CODE, code);
		fieldError.put("detail", error.getDefaultMessage());
		return fieldError;
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(
			@NonNull HttpMessageNotReadableException ex,
			@NonNull HttpHeaders headers,
			@NonNull HttpStatusCode status,
			@NonNull WebRequest request
	) {
		Map<String, String> fieldError = toError(ex);
		if (fieldError != null) {
			ProblemDetail problem = validationProblem(status, List.of(fieldError));
			return handleExceptionInternal(ex, problem, headers, status, request);
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				status,
				"The request body is wrong or contains an invalid value"
		);
		problem.setProperty(CODE, "request.invalidBody");
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/**
	 * A value Jackson cannot convert (text for a number, unknown enum value, object for a text...) becomes a field error
	 * "<domain>.<field>.invalidFormat", like a validation error. Malformed JSON has no field: returns null.
	 */
	private static @Nullable Map<String, String> toError(HttpMessageNotReadableException ex) {
		if (!(ex.getCause() instanceof MismatchedInputException cause) || cause.getPath().isEmpty()) {
			return null;
		}
		JacksonException.Reference reference = cause.getPath().getFirst();
		Object from = reference.from();
		String domain = DOMAINS.get(from instanceof Class<?> type ? type : from.getClass());
		String field = reference.getPropertyName();
		if (domain == null || field == null) {
			return null;
		}
		String code = domain + "." + field + "." + INVALID_FORMAT;
		Map<String, String> fieldError = new LinkedHashMap<>();
		fieldError.put("pointer", "#/" + field);
		fieldError.put(CODE, code);
		fieldError.put("detail", MESSAGES.containsKey(code) ? MESSAGES.getString(code) : "The value is invalid");
		return fieldError;
	}

	private static ProblemDetail validationProblem(HttpStatusCode status, List<Map<String, String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request has validation errors");
		problem.setProperty(CODE, "validation.failed");
		problem.setProperty("errors", errors);
		return problem;
	}

	/**
	 * Errors handled by the parent class (wrong parameter type, unsupported method or media type, unknown route...)
	 * get a code built from their status: "request.methodNotAllowed", "request.notFound"...
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(
			@NonNull Exception ex,
			@Nullable Object body,
			@NonNull HttpHeaders headers,
			@NonNull HttpStatusCode statusCode,
			@NonNull WebRequest request
	) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem
				&& (problem.getProperties() == null || !problem.getProperties().containsKey(CODE))) {
			problem.setProperty(CODE, "request." + statusToKey(statusCode));
		}
		return response;
	}

	private static String statusToKey(HttpStatusCode statusCode) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		if (status == null) {
			return "error";
		}
		StringBuilder key = new StringBuilder();
		for (String word : status.name().toLowerCase().split("_")) {
			key.append(key.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
		}
		return key.toString();
	}

	// SECURITY

	/**
	 * Raised in a controller or by @PreAuthorize: without this handler, it would end in handleUnexpected as a 500.
	 * The 401 raised by the security filters never reach this class, see SecurityProblemHandler.
	 */
	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail handleAuthentication() {
		return unauthorized();
	}

	/**
	 * Same as handleAuthentication, for the 403
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ProblemDetail handleAccessDenied() {
		return forbidden();
	}

	static ProblemDetail unauthorized() {
		return problem(HttpStatus.UNAUTHORIZED, "request.unauthorized", "Authentication is required");
	}

	static ProblemDetail forbidden() {
		return problem(HttpStatus.FORBIDDEN, "request.forbidden", "Access is denied");
	}

	// UNEXPECTED

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		logger.error("Unexpected error", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "server.internalError", "An unexpected error occurred");
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty(CODE, code);
		return problem;
	}
}
