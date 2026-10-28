package com.personal.task_tracker.user.service;

import com.personal.task_tracker.auth.dto.LoginRequestDto;
import com.personal.task_tracker.user.dto.UserCreateDto;
import com.personal.task_tracker.user.dto.UserResponseDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.exception.UserAlreadyExistsException;
import com.personal.task_tracker.user.exception.UserNotFoundException;
import com.personal.task_tracker.user.mapper.UserMapper;
import com.personal.task_tracker.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class UserService {

	private static final String USERNAME_UNIQUE_INDEX = "app_user_username_upper";
	private static final String EMAIL_UNIQUE_CONSTRAINT = "app_user_email_key";

	private final UserRepository repository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * Get all users
	 *
	 * @return All users, sorted by lastname, firstname and username
	 */
	public List<UserResponseDto> getAllUsers() {
		return repository.findAll(Sort.by("lastname", "firstname", "username")).stream()
				.map(UserMapper::toUserResponseDto)
				.toList();
	}

	/**
	 * Get a user by its id
	 *
	 * @param userId the user id
	 * @return The corresponding user
	 * @throws UserNotFoundException error thrown if there is no user with the id
	 */
	public UserResponseDto getUserById(Long userId) {
		AppUser user = findUserById(userId);
		return UserMapper.toUserResponseDto(user);
	}

	/**
	 * Create a user
	 *
	 * @param userDto user to create data
	 * @return the created user
	 * @throws UserAlreadyExistsException if the username (case-insensitive) or the email is already used
	 */
	@Transactional
	public AppUser createUser(UserCreateDto userDto) {
		String username = userDto.username().trim();
		String email = userDto.email().trim().toLowerCase(Locale.ROOT);

		if (repository.existsByUsernameIgnoreCase(username)) {
			throw UserAlreadyExistsException.username();
		}
		if (repository.existsByEmail(email)) {
			throw UserAlreadyExistsException.email();
		}

		String passwordHash = passwordEncoder.encode(userDto.rawPassword());
		AppUser user = UserMapper.toEntity(userDto, username, email, passwordHash);

		try {
			return repository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			String constraintName = exception.getCause() instanceof ConstraintViolationException cve ? cve.getConstraintName() : null;
			if (USERNAME_UNIQUE_INDEX.equals(constraintName)) {
				throw UserAlreadyExistsException.username(exception);
			}
			if (EMAIL_UNIQUE_CONSTRAINT.equals(constraintName)) {
				throw UserAlreadyExistsException.email(exception);
			}
			throw exception;
		}
	}

	/**
	 * Check credentials and create the token
	 * @param userDto credentials
	 */
	public void login(LoginRequestDto userDto) {

	}

	/**
	 * Find a user by its id
	 *
	 * @param userId the user id
	 * @return the corresponding user
	 * @throws UserNotFoundException error thrown if there is no user with the id
	 */
	public AppUser findUserById(Long userId) {
		return repository.findById(userId).orElseThrow(() -> new UserNotFoundException("There is no user with id: " + userId));
	}
}
