package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	private final UserService userService;

	public AuthService(UserService userService) {
		this.userService = userService;
	}

	public AppUser register(RegisterRequestDto registerRequestDto) {
		return userService.createUser(registerRequestDto);
	}
}
