package com.personal.task_tracker.auth.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {

	private int max;

	@Override
	public void initialize(MaxBytes constraint) {
		this.max = constraint.value();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
	}
}
