package com.personal.task_tracker.task.validator;

import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.exception.InvalidTaskUpdateException;

public class TaskUpdatePartialValidator {

	private static final int TITLE_MAX_LENGTH = 50;
	private static final int DESCRIPTION_MAX_LENGTH = 255;
	private static final int ASSIGNEE_MAX_LENGTH = 50;

	private TaskUpdatePartialValidator() {
	}

	public static void validate(TaskRequestPartialDto dto) {
		if (dto.title().isPresent()) {
			String title = dto.title().get();
			if (title == null || title.isBlank()) {
				throw new InvalidTaskUpdateException("title cannot be null nor blank");
			}
			if (title.length() > TITLE_MAX_LENGTH) {
				throw new InvalidTaskUpdateException("title cannot exceed " + TITLE_MAX_LENGTH + " characters");
			}
		}
		if (dto.description().isPresent()) {
			String description = dto.description().get();
			if (description == null || description.isBlank()) {
				throw new InvalidTaskUpdateException("description cannot be null nor blank");
			}
			if (description.length() > DESCRIPTION_MAX_LENGTH) {
				throw new InvalidTaskUpdateException("description cannot exceed " + DESCRIPTION_MAX_LENGTH + " characters");
			}
		}
		if (dto.type().isPresent() && dto.type().get() == null) {
			throw new InvalidTaskUpdateException("type cannot be null");
		}
		if (dto.status().isPresent() && dto.status().get() == null) {
			throw new InvalidTaskUpdateException("status cannot be null");
		}
		if (dto.priority().isPresent() && dto.priority().get() == null) {
			throw new InvalidTaskUpdateException("priority cannot be null");
		}
	}
}
