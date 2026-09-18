package com.personal.task_tracker.validator;

import com.personal.task_tracker.dto.task.TaskRequestPartialDto;

public class TaskUpdatePartialValidator {

	private TaskUpdatePartialValidator() {
	}

	public static void validate(TaskRequestPartialDto dto) {
		if (dto.title().isPresent() && dto.title().get() == null) {
			throw new IllegalArgumentException("title cannot be null");
		}
		if (dto.type().isPresent() && dto.type().get() == null) {
			throw new IllegalArgumentException("type cannot be null");
		}
		if (dto.status().isPresent() && dto.status().get() == null) {
			throw new IllegalArgumentException("status cannot be null");
		}
		if (dto.priority().isPresent() && dto.priority().get() == null) {
			throw new IllegalArgumentException("priority cannot be null");
		}
		// TODO assignee
	}
}
