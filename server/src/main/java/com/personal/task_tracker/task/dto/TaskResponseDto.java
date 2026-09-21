package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;

import java.time.LocalDateTime;

public record TaskResponseDto(
		Long id,
		String title,
		String description,
		TaskType type,
		TaskStatus status,
		TaskPriority priority,
		String assignee,
		LocalDateTime createdDate,
		LocalDateTime updatedDate
) {
}
