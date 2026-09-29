package com.personal.task_tracker.user.mapper;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;

public class UserMapper {

	private UserMapper() {}

	public static AppUser toEntity(RegisterRequestDto registerRequestDto) {
		return new AppUser(
				registerRequestDto.firstname(),
				registerRequestDto.lastname(),
				registerRequestDto.username(),
				registerRequestDto.email(),
				registerRequestDto.password(),
				UserRole.USER
		);
	}
}
