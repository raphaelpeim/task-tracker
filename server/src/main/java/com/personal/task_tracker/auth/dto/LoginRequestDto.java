package com.personal.task_tracker.auth.dto;

import com.personal.task_tracker.auth.validator.MaxBytes;
import jakarta.validation.constraints.NotBlank;

public record LoginRequestDto(
		@NotBlank(message = "{auth.email.required}")
		String email,

		@NotBlank(message = "{auth.password.required}")
		@MaxBytes(value = 72, message = "{auth.password.invalidSize}")
		String password
) {
}
