package com.personal.task_tracker.dto.task;

import com.personal.task_tracker.enums.Task.TaskPriority;
import com.personal.task_tracker.enums.Task.TaskStatus;
import com.personal.task_tracker.enums.Task.TaskType;

import java.time.LocalDateTime;

public record TaskDto(
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
