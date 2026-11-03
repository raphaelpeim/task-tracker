package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.config.JwtProperties;
import com.personal.task_tracker.auth.dto.LoginRequestDto;
import com.personal.task_tracker.auth.dto.LoginResponseDto;
import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.auth.mapper.AuthMapper;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
	private final JwtProperties jwtProperties;
	private final TokenService tokenService;
	private final UserService userService;

	public AuthService(
			JwtProperties jwtProperties,
			TokenService tokenService,
			UserService userService
	) {
		this.jwtProperties = jwtProperties;
		this.tokenService = tokenService;
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

	/**
	 * Check credentials and create the token
	 *
	 * @param userDto credentials
	 * @return the token and its lifetime
	 */
	@Transactional
	public LoginResponseDto login(LoginRequestDto userDto) {
		AppUser user = userService.login(userDto.email(), userDto.password());
		return new LoginResponseDto(
				tokenService.generateToken(user),
				"Bearer",
				jwtProperties.expiration().toSeconds()
		);
	}
}
