package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	private final UserService userService;

	public AuthService(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Register a new user
	 * @param registerRequestDto new user data
	 * @return the created user
	 */
	public RegisterResponseDto register(RegisterRequestDto registerRequestDto) {
		AppUser createdUser = userService.createUser(registerRequestDto);
		return UserMapper.toRegisterResponseDto(createdUser);
	}
}
