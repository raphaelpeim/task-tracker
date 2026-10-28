package com.personal.task_tracker.auth.dto;

import jakarta.validation.constraints.Email;
import org.jspecify.annotations.NonNull;

public record LoginRequestDto(
		@Email @NonNull
		String email,
		@NonNull
		String password
) {
}
