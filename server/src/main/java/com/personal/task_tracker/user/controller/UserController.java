package com.personal.task_tracker.user.controller;

import com.personal.task_tracker.user.dto.UserResponseDto;
import com.personal.task_tracker.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
	private final UserService service;

	public UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<List<UserResponseDto>> getAllUsers() {
		List<UserResponseDto> userDtos = service.getAllUsers();
		return new ResponseEntity<>(userDtos, HttpStatus.OK);
	}

	@GetMapping("/{userId}")
	public ResponseEntity<UserResponseDto> getUser(
			@PathVariable Long userId
	) {
		UserResponseDto userResponseDtos = service.getUserById(userId);
		return new ResponseEntity<>(userResponseDtos, HttpStatus.OK);
	}
}
