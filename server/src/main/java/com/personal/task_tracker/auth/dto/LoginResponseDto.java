package com.personal.task_tracker.auth.dto;

public record LoginResponseDto(
		String accessToken,
		String tokenType,
		long expiresIn
) {
}
