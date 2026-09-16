package com.personal.task_tracker.user.mapper;

import com.personal.task_tracker.user.dto.UserResponseDto;
import com.personal.task_tracker.user.entity.AppUser;

public class UserMapper {

	private UserMapper() {
	}

	public static UserResponseDto toUserResponseDto(AppUser user) {
		return new UserResponseDto(
				user.getId(),
				user.getFirstname(),
				user.getLastname(),
				user.getUsername(),
				user.getEmail()
		);
	}
}
