package com.personal.task_tracker.auth.controller;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.auth.dto.RegisterResponseDto;
import com.personal.task_tracker.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService service;

	public AuthController(
			AuthService service
	) {
		this.service = service;
	}

	@PostMapping("/register")
	public ResponseEntity<RegisterResponseDto> register(@Valid @RequestBody RegisterRequestDto userDto) {
		return new ResponseEntity<>(service.register(userDto),HttpStatus.CREATED);
	}
}
