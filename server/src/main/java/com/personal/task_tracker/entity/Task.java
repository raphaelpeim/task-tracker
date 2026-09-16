package com.personal.task_tracker.entity;

import com.personal.task_tracker.dto.Task.CreateTaskDto;
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

	public Task(CreateTaskDto createTaskDto) {
		this.title = createTaskDto.title();
		this.description = createTaskDto.description();
		this.type = createTaskDto.type();
		this.status = createTaskDto.status();
		this.priority = createTaskDto.priority();
		this.assignee = createTaskDto.assignee();
	}

	public Task() {

	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public TaskType getType() {
		return type;
	}

	public void setType(TaskType type) {
		this.type = type;
	}

	public TaskStatus getStatus() {
		return status;
	}

	public void setStatus(TaskStatus status) {
		this.status = status;
	}

	public TaskPriority getPriority() {
		return priority;
	}

	public void setPriority(TaskPriority priority) {
		this.priority = priority;
	}

	public String getAssignee() {
		return assignee;
	}

	public void setAssignee(String assignee) {
		this.assignee = assignee;
	}

	public LocalDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(LocalDateTime createdDate) {
		this.createdDate = createdDate;
	}

	public LocalDateTime getUpdatedDate() {
		return updatedDate;
	}

	public void setUpdatedDate(LocalDateTime updatedDate) {
		this.updatedDate = updatedDate;
	}
}
