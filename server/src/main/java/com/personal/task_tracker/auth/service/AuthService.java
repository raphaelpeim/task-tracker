package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.auth.mapper.AuthMapper;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
	private final UserService userService;

	public AuthService(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Register a new user
	 *
	 * @param registerRequestDto new user data
	 * @return the created user
	 */
	@Transactional
	public RegisterResponseDto register(RegisterRequestDto registerRequestDto) {
		AppUser createdUser = userService.createUser(AuthMapper.toUserCreateDto(registerRequestDto));
		return AuthMapper.toRegisterResponseDto(createdUser);
	}
}
