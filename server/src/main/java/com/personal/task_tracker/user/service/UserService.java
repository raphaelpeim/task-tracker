package com.personal.task_tracker.user.service;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

	private final UserRepository repository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * Create a user
	 * @param registerRequestDto user to create data
	 * @return the created user
	 */
	public AppUser createUser(RegisterRequestDto registerRequestDto) {
		// TODO Vérifications
		//	if (repository.existsByUsername(user.getUsername())) {
		//		throw new Exception("");
		//	}
		//	if (repository.existsByEmail(user.getEmail())) {
		//		throw new Exception("");
		//	}

		AppUser user = UserMapper.toEntity(registerRequestDto);
		user.setPassword(passwordEncoder.encode(user.getPassword()));

		return repository.save(user);
	}
}
