package com.personal.task_tracker.mapper;

import com.personal.task_tracker.dto.task.CreateTaskDto;
import com.personal.task_tracker.dto.task.TaskDto;
import com.personal.task_tracker.entity.Task;

public class TaskMapper {

	private TaskMapper() {}

	public static Task toEntity(CreateTaskDto dto) {
		return new Task(
				dto.title(),
				dto.description(),
				dto.type(),
				dto.status(),
				dto.priority(),
				dto.assignee()
		);
	}

	public static TaskDto toDto(Task task) {
		return new TaskDto(
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
