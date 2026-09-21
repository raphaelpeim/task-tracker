package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskRequestDto(
		@NotBlank @Size(max = 50) String title,
		@NotBlank @Size(max = 255) String description,
		@NotNull TaskType type,
		@NotNull TaskStatus status,
		@NotNull TaskPriority priority,
		@NotBlank String assignee
) {}
