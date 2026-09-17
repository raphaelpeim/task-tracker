package com.personal.task_tracker.validator;

import com.personal.task_tracker.dto.task.TaskRequestPartialDto;

public class TaskPartialUpdateValidator {

	private TaskPartialUpdateValidator() {}

	public static void validate(TaskRequestPartialDto dto) {
		if (dto.title().isPresent() && dto.title().get() == null) {
			throw new IllegalArgumentException("title ne peut pas être null");
		}
		if (dto.type().isPresent() && dto.type().get() == null) {
			if (dto.type().get() == null) {
				throw new IllegalArgumentException("type ne peut pas être null");
			}
			// TODO check enum
		}
		if (dto.status().isPresent() && dto.status().get() == null) {
			throw new IllegalArgumentException("status ne peut pas être null");
		}
		if (dto.priority().isPresent() && dto.priority().get() == null) {
			throw new IllegalArgumentException("priority ne peut pas être null");
		}
		// TODO assignee
	}
}
