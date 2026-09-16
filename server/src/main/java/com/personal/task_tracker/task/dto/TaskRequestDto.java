package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskRequestDto(
		@NotBlank(message = "{task.title.required}")
		@Size(max = 50, message = "{task.title.invalidSize}")
		String title,

		@Size(max = 255, message = "{task.description.invalidSize}")
		String description,

		@NotNull(message = "{task.type.required}")
		TaskType type,

		@NotNull(message = "{task.status.required}")
		TaskStatus status,

		@NotNull(message = "{task.priority.required}")
		TaskPriority priority,

		Long assigneeId
) {
}
