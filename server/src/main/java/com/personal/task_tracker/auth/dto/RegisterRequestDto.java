package com.personal.task_tracker.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

public record RegisterRequestDto(
		@NotBlank @Size(max = 50) String firstname,
		@NotBlank @Size(max = 50) String lastname,
		@NotBlank @Size(max = 50) String username,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(min = 8, max = 72) String password
) {
	@Override
	public @NonNull String toString() {
		return "RegisterRequestDto[firstname=" + firstname
				+ ", lastname=" + lastname
				+ ", username=" + username
				+ ", email=" + email
				+ ", password=***]";
	}
}
