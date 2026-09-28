package com.personal.task_tracker.user.repository;

import com.personal.task_tracker.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {
	boolean existsByUsernameIgnoreCase(String username);

	boolean existsByEmail(String email);
}
