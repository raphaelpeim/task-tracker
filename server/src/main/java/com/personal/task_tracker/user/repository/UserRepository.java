package com.personal.task_tracker.user.repository;

import com.personal.task_tracker.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {
	Optional<AppUser> findByEmail(String email);

	Boolean existsByEmail(String email);

	Boolean existsByUsername(String username);
}
