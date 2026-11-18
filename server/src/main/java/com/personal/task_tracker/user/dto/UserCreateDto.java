package com.personal.task_tracker.user.dto;

import org.jspecify.annotations.NonNull;

public record UserCreateDto(
		String firstname,
		String lastname,
		String username,
		String email
) {
	@Override
	public @NonNull String toString() {
		return "UserCreateDto[firstname=" + firstname
				+ ", lastname=" + lastname
				+ ", username=" + username
				+ ", email=" + email + "]";
	}
}
