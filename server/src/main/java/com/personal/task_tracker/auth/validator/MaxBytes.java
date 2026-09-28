package com.personal.task_tracker.auth.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string must not exceed the given number of bytes once encoded in UTF-8.
 * BCrypt only accepts passwords up to 72 bytes, whereas {@code @Size} counts characters.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxBytesValidator.class)
public @interface MaxBytes {

	int value();

	String message() default "size must not exceed {value} bytes";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
