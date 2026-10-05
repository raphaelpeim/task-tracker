package com.personal.task_tracker.user.dto;

public record UserResponseDto(
		String firstname,
		String lastname,
		String username,
		String email
) {
}