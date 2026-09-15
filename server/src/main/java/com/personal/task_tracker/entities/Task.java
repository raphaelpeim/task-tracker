package com.personal.task_tracker.entities;

import com.personal.task_tracker.enums.Task.TaskPriority;
import com.personal.task_tracker.enums.Task.TaskStatus;
import com.personal.task_tracker.enums.Task.TaskType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
public class Task {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name="title", length=50, nullable=false)
	private String title;

	@Column(name="description", nullable=false)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name="type", length=10, nullable=false)
	private TaskType type;

	@Enumerated(EnumType.STRING)
	@Column(name="status", length=20, nullable=false)
	private TaskStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name="priority", length=10, nullable=false)
	private TaskPriority priority;

	@Column(name="assignee", length=50)
	private String assignee; // TODO Link with User table

	@CreationTimestamp
	@Column(name="created_date", nullable=false, updatable=false)
	private LocalDateTime createdDate;

	@UpdateTimestamp
	@Column(name="updated_date")
	private LocalDateTime updatedDate;

	// other fields, getters and setters
}
