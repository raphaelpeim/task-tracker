package com.personal.task_tracker.dto.task;

import com.personal.task_tracker.enums.Task.TaskPriority;
import com.personal.task_tracker.enums.Task.TaskStatus;
import com.personal.task_tracker.enums.Task.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskDto(
		@NotBlank @Size(max = 50) String title,
		@NotBlank @Size(max = 255) String description,
		@NotNull TaskType type,
		@NotNull TaskStatus status,
		@NotNull TaskPriority priority,
		String assignee
) {}
