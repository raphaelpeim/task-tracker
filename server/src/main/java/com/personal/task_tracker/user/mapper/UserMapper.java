package com.personal.task_tracker.user.mapper;

import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;

public class UserMapper {

	private UserMapper() {}

	public static AppUser toEntity(UserCreateDto userDto, String passwordHash) {
		return new AppUser(
				userDto.firstname(),
				userDto.lastname(),
				userDto.username(),
				userDto.email(),
				passwordHash,
				UserRole.USER
		);
	}
}
