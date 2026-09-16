package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import com.personal.task_tracker.user.dto.UserResponseDto;

import java.time.LocalDateTime;

public record TaskResponseDto(
		Long id,
		String title,
		String description,
		TaskType type,
		TaskStatus status,
		TaskPriority priority,
		UserResponseDto assignee,
		LocalDateTime createdDate,
		LocalDateTime updatedDate
) {
}
