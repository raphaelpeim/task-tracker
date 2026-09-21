package com.personal.task_tracker.task.mapper;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.entity.Task;

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

	public static void applyPartialUpdate(Task task, TaskRequestPartialDto dto) {
		dto.title().ifPresent(task::setTitle);
		dto.description().ifPresent(task::setDescription);
		dto.type().ifPresent(task::setType);
		dto.status().ifPresent(task::setStatus);
		dto.priority().ifPresent(task::setPriority);
		dto.assignee().ifPresent(task::setAssignee);
	}
}
