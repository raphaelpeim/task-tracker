package com.personal.task_tracker.user.mapper;

import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.dto.UserResponseDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;

public class UserMapper {

	private UserMapper() {}

	public static AppUser toEntity(UserCreateDto userDto, String username, String email, String passwordHash) {
		return new AppUser(
				userDto.firstname(),
				userDto.lastname(),
				username,
				email,
				passwordHash,
				UserRole.USER
		);
	}

	public static UserResponseDto toDto(AppUser user) {
		return new UserResponseDto(
				user.getId(),
				user.getFirstname(),
				user.getLastname(),
				user.getUsername(),
				user.getEmail()
		);
	}
}
