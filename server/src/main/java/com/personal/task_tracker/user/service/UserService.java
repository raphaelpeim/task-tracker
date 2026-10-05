package com.personal.task_tracker.user.service;

import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.exception.UserAlreadyExistsException;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository repository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * Create a user
	 *
	 * @param userDto user to create data
	 * @return the created user
	 */
	@Transactional
	public AppUser createUser(UserCreateDto userDto) {
		if (repository.existsByUsername(userDto.username())) {
			throw new UserAlreadyExistsException("Username already exists");
		}
		if (repository.existsByEmail(userDto.email())) {
			throw new UserAlreadyExistsException("Email already exists");
		}
		String passwordHash = passwordEncoder.encode(userDto.rawPassword());
		AppUser user = UserMapper.toEntity(userDto, passwordHash);

		return repository.save(user);
	}

	public Optional<AppUser> findUserById(Long userId) {
		return repository.findById(userId);
				// .orElseThrow(it -> UserNotFoundException);
	}
}
