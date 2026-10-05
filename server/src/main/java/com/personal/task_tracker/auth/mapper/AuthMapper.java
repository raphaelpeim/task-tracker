package com.personal.task_tracker.auth.mapper;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.entity.AppUser;

public class AuthMapper {

	private AuthMapper() {
	}

	public static RegisterResponseDto toRegisterResponseDto(AppUser user) {
		return new RegisterResponseDto(
				user.getId(),
				user.getFirstname(),
				user.getLastname(),
				user.getUsername(),
				user.getEmail(),
				user.getRole().toString()
		);
	}

	public static UserCreateDto toUserCreateDto(RegisterRequestDto registerRequestDto) {
		return new UserCreateDto(
				registerRequestDto.firstname(),
				registerRequestDto.lastname(),
				registerRequestDto.username(),
				registerRequestDto.email(),
				registerRequestDto.password()
		);
	}
}
