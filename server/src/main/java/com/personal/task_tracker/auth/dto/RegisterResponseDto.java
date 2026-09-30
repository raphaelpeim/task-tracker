package com.personal.task_tracker.auth.dto;

public record RegisterResponseDto(
		Long id,
		String firstname,
		String lastname,
		String username,
		String email,
		String role
) {
}
