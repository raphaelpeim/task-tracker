package com.personal.task_tracker.commons.exception;

import jakarta.validation.Constraint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request body must be declared in GlobalExceptionHandler.DOMAINS, and every field of a request body must have
 * its translation keys in ValidationMessages.properties: one per constraint, given as "{key}" in the constraint message,
 * and the "invalidFormat" key used when the JSON value cannot be converted.
 */
class ValidationMessagesTest {

	@Test
	void everyRequestBodyShouldHaveADomain() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		List<String> missingDtos = new ArrayList<>();

		for (BeanDefinition controller : scanner.findCandidateComponents("com.personal.task_tracker")) {
			for (Method method : Class.forName(controller.getBeanClassName()).getDeclaredMethods()) {
				for (Parameter parameter : method.getParameters()) {
					if (parameter.isAnnotationPresent(RequestBody.class)
							&& !GlobalExceptionHandler.DOMAINS.containsKey(parameter.getType())) {
						missingDtos.add(method.getDeclaringClass().getSimpleName() + "." + method.getName()
								+ ": " + parameter.getType().getSimpleName() + " missing from GlobalExceptionHandler.DOMAINS");
					}
				}
			}
		}

		assertThat(missingDtos).isEmpty();
	}

	@Test
	void everyConstraintMessageShouldBeAKeyDefinedInValidationMessages() throws Exception {
		List<String> problems = new ArrayList<>();

		for (Map.Entry<Class<?>, String> entry : GlobalExceptionHandler.DOMAINS.entrySet()) {
			Class<?> dto = entry.getKey();
			for (RecordComponent component : dto.getRecordComponents()) {
				Field field = dto.getDeclaredField(component.getName());
				for (Annotation annotation : field.getAnnotations()) {
					if (!annotation.annotationType().isAnnotationPresent(Constraint.class)) {
						continue;
					}
					String message = (String) annotation.annotationType().getMethod("message").invoke(annotation);
					String location = dto.getSimpleName() + "." + field.getName() + " @" + annotation.annotationType().getSimpleName();
					if (!message.matches("\\{[^{}]+}")) {
						problems.add(location + ": message is not a key between braces: " + message);
					} else if (!GlobalExceptionHandler.MESSAGES.containsKey(message.substring(1, message.length() - 1))) {
						problems.add(location + ": key missing from ValidationMessages.properties: " + message);
					}
				}
			}
		}

		assertThat(problems).isEmpty();
	}

	@Test
	void everyFieldShouldHaveAnInvalidFormatKey() {
		List<String> missingKeys = new ArrayList<>();

		for (Map.Entry<Class<?>, String> entry : GlobalExceptionHandler.DOMAINS.entrySet()) {
			for (RecordComponent component : entry.getKey().getRecordComponents()) {
				String key = entry.getValue() + "." + component.getName() + "." + GlobalExceptionHandler.INVALID_FORMAT;
				if (!GlobalExceptionHandler.MESSAGES.containsKey(key)) {
					missingKeys.add(key);
				}
			}
		}

		assertThat(missingKeys).isEmpty();
	}
}
