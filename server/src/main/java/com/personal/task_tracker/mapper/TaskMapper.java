package com.personal.task_tracker.mapper;

import com.personal.task_tracker.dto.task.TaskRequestDto;
import com.personal.task_tracker.dto.task.TaskResponseDto;
import com.personal.task_tracker.entity.Task;

public class TaskMapper {

	private TaskMapper() {}

	public static Task toEntity(TaskRequestDto dto) {
		return new Task(
				dto.title(),
				dto.description(),
				dto.type(),
				dto.status(),
				dto.priority(),
				dto.assignee()
		);
	}

	public static TaskResponseDto toDto(Task task) {
		return new TaskResponseDto(
				task.getId(),
				task.getTitle(),
				task.getDescription(),
				task.getType(),
				task.getStatus(),
				task.getPriority(),
				task.getAssignee(),
				task.getCreatedDate(),
				task.getUpdatedDate()
		);
	}
}
