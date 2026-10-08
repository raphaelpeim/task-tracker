package com.personal.task_tracker.task.mapper;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.mapper.UserMapper;
import jakarta.annotation.Nullable;

public class TaskMapper {

	private TaskMapper() {
	}

	public static Task toEntity(TaskRequestDto taskDto) {
		return new Task(
				taskDto.title(),
				taskDto.description(),
				taskDto.type(),
				taskDto.status(),
				taskDto.priority(),
				null
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
				task.getAssignee() != null ? UserMapper.toDto(task.getAssignee()) : null,
				task.getCreatedDate(),
				task.getUpdatedDate()
		);
	}

	public static void applyUpdate(Task task, TaskRequestDto taskDto) {
		task.setTitle(taskDto.title());
		task.setDescription(taskDto.description());
		task.setType(taskDto.type());
		task.setStatus(taskDto.status());
		task.setPriority(taskDto.priority());
	}

	public static void applyPartialUpdate(Task task, TaskRequestPartialDto taskDto) {
		taskDto.title().ifPresent(task::setTitle);
		taskDto.description().ifPresent(task::setDescription);
		taskDto.type().ifPresent(task::setType);
		taskDto.status().ifPresent(task::setStatus);
		taskDto.priority().ifPresent(task::setPriority);
	}
}
