package com.personal.task_tracker.dto.task;

import com.personal.task_tracker.enums.Task.TaskPriority;
import com.personal.task_tracker.enums.Task.TaskStatus;
import com.personal.task_tracker.enums.Task.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.util.Optional;

public record TaskRequestPartialDto(
		JsonNullable<String> title,
		JsonNullable<String> description,
		JsonNullable<TaskType> type,
		JsonNullable<TaskStatus> status,
		JsonNullable<TaskPriority> priority,
		JsonNullable<String> assignee
) {}
