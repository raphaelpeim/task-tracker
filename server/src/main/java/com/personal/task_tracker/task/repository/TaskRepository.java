package com.personal.task_tracker.task.repository;

import com.personal.task_tracker.task.entity.Task;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

	/**
	 * Loads the assignee in the same query: the mapper reads it for every task
	 */
	@Override
	@EntityGraph(attributePaths = "assignee")
	@NonNull List<Task> findAll(@NonNull Sort sort);
}
