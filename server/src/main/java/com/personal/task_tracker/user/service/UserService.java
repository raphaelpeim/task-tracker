package com.personal.task_tracker.user.service;

import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.exception.UserNotFoundException;
import com.personal.task_tracker.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository repository;

	public UserService(UserRepository repository) {
		this.repository = repository;
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
