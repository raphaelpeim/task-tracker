package com.personal.task_tracker.auth.controller;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.service.AuthService;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService service;

	public AuthController(
			AuthService service
	) {
		this.service = service;
	}

	@PostMapping("/register")
	public AppUser register(@Valid @RequestBody RegisterRequestDto userDto) {
		return service.register(userDto);
	}
}
