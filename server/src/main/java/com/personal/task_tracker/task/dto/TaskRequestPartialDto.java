package com.personal.task_tracker.task.dto;

import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import org.openapitools.jackson.nullable.JsonNullable;

public record TaskRequestPartialDto(
		JsonNullable<String> title,
		JsonNullable<String> description,
		JsonNullable<TaskType> type,
		JsonNullable<TaskStatus> status,
		JsonNullable<TaskPriority> priority,
		JsonNullable<String> assignee
) {}
