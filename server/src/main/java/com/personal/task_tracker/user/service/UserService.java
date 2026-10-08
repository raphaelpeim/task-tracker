package com.personal.task_tracker.user.service;

import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.dto.UserResponseDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.exception.UserAlreadyExistsException;
import com.personal.task_tracker.user.exception.UserNotFoundException;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

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
	 * Get all users
	 *
	 * @return All users
	 */
	public List<UserResponseDto> getAllUsers() {
		List<AppUser> userList = repository.findAll();
		userList.sort(
				Comparator.comparing((AppUser::getLastname))
						.thenComparing((AppUser::getFirstname))
						.thenComparing((AppUser::getUsername))
		);
		return userList.stream().map(UserMapper::toDto).toList();
	}

	/**
	 * Get a user by its id
	 *
	 * @param userId the user id
	 * @return The corresponding user
	 */
	public UserResponseDto getUserById(Long userId) {
		AppUser user = findUserById(userId);
		return UserMapper.toDto(user);
	}

	/**
	 * Create a user
	 *
	 * @param userDto user to create data
	 * @return the created user
	 */
	@Transactional
	public AppUser createUser(UserCreateDto userDto) {
		if (repository.existsByUsername(userDto.username().trim().toLowerCase())) {
			throw new UserAlreadyExistsException("Username already exists");
		}
		if (repository.existsByEmail(userDto.email().trim().toLowerCase())) {
			throw new UserAlreadyExistsException("Email already exists");
		}

		String passwordHash = passwordEncoder.encode(userDto.rawPassword());
		AppUser user = UserMapper.toEntity(userDto, passwordHash);

		return repository.save(user);
	}

	/**
	 * Find a user by its id
	 *
	 * @param userId the user id
	 * @return the corresponding user
	 * @throws UserNotFoundException Exception thrown if there is no user with the id
	 */
	public AppUser findUserById(Long userId) {
		return repository.findById(userId).orElseThrow(() -> new UserNotFoundException("There is no user with id: " + userId));
	}
}
