package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.dto.LoginRequestDto;
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
	 * @param userDto new user data
	 * @return the created user
	 */
	@Transactional
	public RegisterResponseDto register(RegisterRequestDto userDto) {
		AppUser createdUser = userService.createUser(AuthMapper.toUserCreateDto(userDto));
		return AuthMapper.toRegisterResponseDto(createdUser);
	}

//	/**
//	 * Check credentials and create the token
//	 * @param userDto credentials initial
//	 */
//	@Transactional
//	public void login(LoginRequestDto userDto) {
//		AppUser createdUser = userService.createUser(AuthMapper.toUserCreateDto(userDto));
//	}
}
