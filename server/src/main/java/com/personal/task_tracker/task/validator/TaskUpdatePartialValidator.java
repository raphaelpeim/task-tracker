package com.personal.task_tracker.task.validator;

import com.personal.task_tracker.task.dto.TaskRequestPartialDto;

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
		// assignee : pas de vérification — un null explicite est autorisé
	}
}
