package com.personal.task_tracker.repository;

import com.personal.task_tracker.entity.Task;
import com.personal.task_tracker.enums.Task.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
	List<Task> findByStatus(TaskStatus status);
}
