package com.personal.task_tracker.user.dto;

public record UserResponseDto(
		Long id,
		String firstname,
		String lastname,
		String username,
		String email
) {
}
