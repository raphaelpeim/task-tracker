package com.personal.task_tracker.task.repository;

import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
	List<Task> findByStatus(TaskStatus status);
}
