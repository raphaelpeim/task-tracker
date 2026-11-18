package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.config.JwtProperties;
import com.personal.task_tracker.auth.dto.LoginRequestDto;
import com.personal.task_tracker.auth.dto.LoginResponseDto;
import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.auth.exception.InvalidCredentialsException;
import com.personal.task_tracker.auth.mapper.AuthMapper;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AuthService {
	private final String dummyPasswordHash;
	private final JwtProperties jwtProperties;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;
	private final UserService userService;

	public AuthService(
			JwtProperties jwtProperties,
			PasswordEncoder passwordEncoder,
			TokenService tokenService,
			UserService userService
	) {
		this.dummyPasswordHash = passwordEncoder.encode("dummyPassword");
		this.jwtProperties = jwtProperties;
		this.passwordEncoder = passwordEncoder;
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
		String passwordHash = passwordEncoder.encode(userDto.password());
		AppUser createdUser = userService.createUser(AuthMapper.toUserCreateDto(userDto), passwordHash);
		return AuthMapper.toRegisterResponseDto(createdUser);
	}

	/**
	 * Check credentials and create the token
	 *
	 * @param userDto credentials
	 * @return the token and its lifetime
	 */
	public LoginResponseDto login(LoginRequestDto userDto) {
		Optional<AppUser> user = userService.findUserByEmail(userDto.email());
		String passwordHash = user.map(AppUser::getPassword).orElse(dummyPasswordHash);
		boolean passwordMatches = passwordEncoder.matches(userDto.password(), passwordHash);

		if (user.isPresent() && passwordMatches) {
			return new LoginResponseDto(
					tokenService.generateToken(user.get()),
					"Bearer",
					jwtProperties.expiration().toSeconds()
			);
		} else {
			throw new InvalidCredentialsException();
		}
	}
}
