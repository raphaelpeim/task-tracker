package com.personal.task_tracker.auth.dto;

import com.personal.task_tracker.auth.validator.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

public record RegisterRequestDto(
		@NotBlank(message = "{user.firstname.required}")
		@Size(max = 50, message = "{user.firstname.invalidSize}")
		String firstname,

		@NotBlank(message = "{user.lastname.required}")
		@Size(max = 50, message = "{user.lastname.invalidSize}")
		String lastname,

		@NotBlank(message = "{user.username.required}")
		@Size(max = 50, message = "{user.username.invalidSize}")
		String username,

		@NotBlank(message = "{user.email.required}")
		@Email(message = "{user.email.invalidFormat}")
		@Size(max = 255, message = "{user.email.invalidSize}")
		String email,

		@NotBlank(message = "{user.password.required}")
		@Size(min = 8, message = "{user.password.invalidSize}")
		@MaxBytes(value = 72, message = "{user.password.invalidSize}")
		String password
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
