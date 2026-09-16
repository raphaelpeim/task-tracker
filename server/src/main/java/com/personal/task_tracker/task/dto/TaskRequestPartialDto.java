package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

public record TaskRequestPartialDto(
		@NotBlank(message = "{task.title.required}")
		@Size(max = 50, message = "{task.title.invalidSize}")
		JsonNullable<String> title,

		@Size(max = 255, message = "{task.description.invalidSize}")
		JsonNullable<String> description,

		@NotNull(message = "{task.type.required}")
		JsonNullable<TaskType> type,

		@NotNull(message = "{task.status.required}")
		JsonNullable<TaskStatus> status,

		@NotNull(message = "{task.priority.required}")
		JsonNullable<TaskPriority> priority,

		JsonNullable<Long> assigneeId
) {
}
