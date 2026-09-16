package com.personal.task_tracker.task;

import com.personal.task_tracker.IntegrationTest;
import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;


class TaskControllerTest extends IntegrationTest {

	@Autowired
	private TaskRepository repository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private AppUser assignee;

	static Stream<Arguments> invalidPatchBodies() {
		return Stream.of(
				Arguments.of("{\"title\": null}", "title", "task.title.required"),
				Arguments.of("{\"title\": \"\"}", "title", "task.title.required"),
				Arguments.of("{\"title\": \"   \"}", "title", "task.title.required"),
				Arguments.of("{\"title\": \"" + "a".repeat(51) + "\"}", "title", "task.title.invalidSize"),
				Arguments.of("{\"description\": \"" + "a".repeat(256) + "\"}", "description", "task.description.invalidSize"),
				Arguments.of("{\"type\": null}", "type", "task.type.required"),
				Arguments.of("{\"status\": null}", "status", "task.status.required"),
				Arguments.of("{\"priority\": null}", "priority", "task.priority.required")
		);
	}

	@BeforeEach
	void setUp() {
		assignee = saveUser("assignee", "assignee@mail.com");
	}

	// POST

	@Test
	void shouldCreateAndRetrieveTask() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.FEATURE,
				TaskStatus.READY,
				TaskPriority.HIGH,
				assignee.getId()
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.extract()
				.jsonPath()
				.getLong("id");

		given()
				.pathParam("id", createdId)
				.when()
				.get("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.assertThat()
				.body("title", equalTo(taskDto.title()))
				.body("description", equalTo(taskDto.description()))
				.body("type", equalTo(taskDto.type().toString()))
				.body("status", equalTo(taskDto.status().toString()))
				.body("priority", equalTo(taskDto.priority().toString()))
				.body("assignee.id", equalTo(assignee.getId().intValue()));
	}

	@Test
	void shouldCreateTaskWhenFieldsAreAtMaxLength() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"a".repeat(50),
				"a".repeat(255),
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201);
	}

	@Test
	void shouldCreateTaskWithoutAssignee() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				null
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.body("assignee", nullValue())
				.extract()
				.jsonPath()
				.getLong("id");

		assertThat(repository.findById(createdId).orElseThrow().getAssignee()).isNull();
	}

	@Test
	void shouldReturn400WhenTitleIsBlank() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/title' }.code", equalTo("task.title.required"));
	}

	@Test
	void shouldReturn400WhenTitleIsTooLong() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"Title too long because it is supposed to be 50/less",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("code", equalTo("validation.failed"))
				.body("detail", equalTo("The request has validation errors"))
				.body("errors", hasSize(1))
				.body("errors[0].pointer", equalTo("#/title"))
				.body("errors[0].code", equalTo("task.title.invalidSize"))
				.body("errors[0].detail", equalTo("Title must be at most 50 characters"));
	}

	@Test
	void shouldReturn400WhenDescriptionIsTooLong() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"Title",
				"Description description description description description description description description description description description description description description description description description description description description description description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/description' }.code", equalTo("task.description.invalidSize"));
	}

	@Test
	void shouldReturn400WhenTypeIsMissing() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				null,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/type' }.code", equalTo("task.type.required"));
	}

	@Test
	void shouldReturn400WithFieldErrorWhenTitleIsNotAText() {
		given()
				.contentType(ContentType.JSON)
				.body("{\"title\": {}, \"type\": \"BUG\", \"status\": \"BACKLOG\", \"priority\": \"LOW\"}")
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("errors[0].pointer", equalTo("#/title"))
				.body("errors[0].code", equalTo("task.title.invalidFormat"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturn400WithoutFieldErrorWhenJsonIsMalformed() {
		given()
				.contentType(ContentType.JSON)
				.body("{\"title\": ")
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("code", equalTo("request.invalidBody"))
				.body("detail", equalTo("The request body is wrong or contains an invalid value"))
				.body("$", not(hasKey("errors")));
	}

	@Test
	void shouldReturnEnglishDetailWhateverTheRequestLanguage() {
		given()
				.contentType(ContentType.JSON)
				.header("Accept-Language", "fr-FR")
				.body("{}")
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/title' }.detail", equalTo("Title is required"));
	}

	@Test
	void shouldReturn400ForEachMissingRequiredField() {
		given()
				.contentType(ContentType.JSON)
				.body("{}")
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("errors.find { it.pointer == '#/title' }.code", equalTo("task.title.required"))
				.body("errors.find { it.pointer == '#/type' }.code", equalTo("task.type.required"))
				.body("errors.find { it.pointer == '#/status' }.code", equalTo("task.status.required"))
				.body("errors.find { it.pointer == '#/priority' }.code", equalTo("task.priority.required"))
				.body("errors", hasSize(4))
				.body("errors.pointer", not(hasItem("#/description")))
				.body("errors.pointer", not(hasItem("#/assigneeId")));
	}

	@Test
	void shouldCreateTaskWithoutDescription() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				null,
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				null
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.body("description", nullValue())
				.body("createdDate", notNullValue())
				.body("updatedDate", nullValue())
				.extract()
				.jsonPath()
				.getLong("id");

		assertThat(repository.findById(createdId).orElseThrow().getDescription()).isNull();
	}

	@Test
	void shouldReturn400WhenEnumValueIsUnknown() {
		String json = """
				{
				  "title": "Title",
				  "description": "Description",
				  "type": "UNKNOWN",
				  "status": "BACKLOG",
				  "priority": "LOW"
				}
				""";

		given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("code", equalTo("validation.failed"))
				.body("errors", hasSize(1))
				.body("errors[0].pointer", equalTo("#/type"))
				.body("errors[0].code", equalTo("task.type.invalidFormat"))
				.body("errors[0].detail", equalTo("Type has an unknown value"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturn404WhenAssigneeDoesNotExistOnCreate() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				999999L
		);

		given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no user with id: 999999"))
				.body("code", equalTo("user.notFound"));

		assertThat(repository.count()).isZero();
	}

	// GET

	@Test
	void shouldReturnEmptyListWhenNoTask() {
		given()
				.when()
				.get("/api/tasks")
				.then()
				.statusCode(200)
				.body("$", hasSize(0));
	}

	@Test
	void shouldReturnAllTasks() {
		saveTask("First");
		saveTask("Second");

		given()
				.when()
				.get("/api/tasks")
				.then()
				.statusCode(200)
				.body("$", hasSize(2))
				.body("title", containsInAnyOrder("First", "Second"));
	}

	/**
	 * PostgreSQL gives no order without ORDER BY, and an updated row usually moves to the end of the table
	 */
	@Test
	void shouldReturnTasksSortedByIdEvenAfterAnUpdate() {
		Task first = saveTask("First");
		saveTask("Second");
		saveTask("Third");
		first.setTitle("First updated");
		repository.save(first);

		given()
				.when()
				.get("/api/tasks")
				.then()
				.statusCode(200)
				.body("title", contains("First updated", "Second", "Third"));
	}

	@Test
	void shouldLoadTasksAndTheirAssigneesInASingleQuery() {
		AppUser otherAssignee = saveUser("other", "other@mail.com");
		saveTask("First");
		repository.save(new Task("Second", "Description", TaskType.BUG, TaskStatus.BACKLOG, TaskPriority.LOW, otherAssignee));
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		given()
				.when()
				.get("/api/tasks")
				.then()
				.statusCode(200)
				.body("assignee.username", contains("assignee", "other"));

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Test
	void shouldReturn404WhenTaskNotFound() {
		given()
				.pathParam("id", 999999L)
				.when()
				.get("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.contentType("application/problem+json")
				.body("detail", equalTo("There is no task with id: 999999"))
				.body("code", equalTo("task.notFound"));
	}

	@Test
	void shouldReturn400WhenIdIsNotNumeric() {
		given()
				.when()
				.get("/api/tasks/abc")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("code", equalTo("request.badRequest"));
	}

	@Test
	void shouldReturn405WithCodeWhenMethodIsNotSupported() {
		given()
				.when()
				.post("/api/tasks/1")
				.then()
				.statusCode(405)
				.contentType("application/problem+json")
				.body("code", equalTo("request.methodNotAllowed"));
	}

	// PUT

	@Test
	void shouldReplaceAllFieldsOnPut() {
		Task existingTask = saveTask("Title");
		AppUser newAssignee = saveUser("newassignee", "newassignee@mail.com");
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"New title",
				"New description",
				TaskType.FEATURE,
				TaskStatus.DONE,
				TaskPriority.CRITICAL,
				newAssignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(taskUpdateDto)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("id", equalTo(existingTask.getId().intValue()))
				.body("title", equalTo(taskUpdateDto.title()))
				.body("status", equalTo(taskUpdateDto.status().toString()))
				.body("assignee.id", equalTo(newAssignee.getId().intValue()));

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getTitle()).isEqualTo(taskUpdateDto.title());
		assertThat(storedTask.getDescription()).isEqualTo(taskUpdateDto.description());
		assertThat(storedTask.getType()).isEqualTo(taskUpdateDto.type());
		assertThat(storedTask.getStatus()).isEqualTo(taskUpdateDto.status());
		assertThat(storedTask.getPriority()).isEqualTo(taskUpdateDto.priority());
		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(newAssignee.getId());
		assertThat(storedTask.getCreatedDate()).isEqualTo(existingTask.getCreatedDate());
		assertThat(storedTask.getUpdatedDate()).isNotNull();
	}

	@Test
	void shouldUnassignTaskWhenPutHasNoAssignee() {
		Task existingTask = saveTask("Title");
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				null
		);

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(taskUpdateDto)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("assignee", nullValue());

		assertThat(repository.findById(existingTask.getId()).orElseThrow().getAssignee()).isNull();
	}

	@Test
	void shouldReturn400WhenPutOmitsAField() {
		// TaskRequestDto n'a pas de champ optionnel : on simule un JSON incomplet
		// en envoyant directement une chaîne JSON brute plutôt que le record complet.
		String incompleteJson = """
				{
				  "title": "Title",
				  "description": "Description",
				  "type": "BUG",
				  "status": "BACKLOG"
				}
				""";

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", 1)
				.body(incompleteJson)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(400);
	}

	@Test
	void shouldReturn404WhenUpdatingNonExistentTask() {
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", 999999L)
				.body(taskUpdateDto)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no task with id: 999999"))
				.body("code", equalTo("task.notFound"));
	}

	@Test
	void shouldReturn404AndKeepTaskUnchangedWhenPutAssigneeDoesNotExist() {
		Task existingTask = saveTask("Title");
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"New title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				999999L
		);

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(taskUpdateDto)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no user with id: 999999"))
				.body("code", equalTo("user.notFound"));

		assertThat(repository.findById(existingTask.getId()).orElseThrow().getTitle()).isEqualTo(existingTask.getTitle());
		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(assignee.getId());
	}

	// PATCH

	@Test
	void shouldUpdateOnlyStatusAndKeepOtherFieldsUnchanged() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);
		TaskRequestPartialDto taskUpdateDto = new TaskRequestPartialDto(
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.of(TaskStatus.IN_PROGRESS),
				JsonNullable.undefined(),
				JsonNullable.undefined()
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskCreateDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.extract()
				.jsonPath()
				.getLong("id");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", createdId)
				.body(taskUpdateDto)
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.assertThat()
				.body("title", equalTo(taskCreateDto.title()))
				.body("description", equalTo(taskCreateDto.description()))
				.body("type", equalTo(taskCreateDto.type().toString()))
				.body("status", equalTo(TaskStatus.IN_PROGRESS.toString()))
				.body("priority", equalTo(taskCreateDto.priority().toString()))
				.body("assignee.id", equalTo(assignee.getId().intValue()));

		Task storedTask = repository.findById(createdId).orElseThrow();
		assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
		assertThat(storedTask.getTitle()).isEqualTo(taskCreateDto.title());
		assertThat(storedTask.getDescription()).isEqualTo(taskCreateDto.description());
		assertThat(storedTask.getType()).isEqualTo(taskCreateDto.type());
		assertThat(storedTask.getPriority()).isEqualTo(taskCreateDto.priority());
		assertThat(assigneeIdOf(createdId)).isEqualTo(assignee.getId());
	}

	@Test
	void shouldKeepTaskUnchangedWhenPatchBodyIsEmpty() {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body("{}")
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("title", equalTo(existingTask.getTitle()));

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getTitle()).isEqualTo(existingTask.getTitle());
		assertThat(storedTask.getDescription()).isEqualTo(existingTask.getDescription());
		assertThat(storedTask.getType()).isEqualTo(existingTask.getType());
		assertThat(storedTask.getStatus()).isEqualTo(existingTask.getStatus());
		assertThat(storedTask.getPriority()).isEqualTo(existingTask.getPriority());
		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(assignee.getId());
	}

	@Test
	void shouldReassignTaskWhenPatchHasAssigneeId() {
		Task existingTask = saveTask("Title");
		AppUser newAssignee = saveUser("newassignee", "newassignee@mail.com");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(Map.of("assigneeId", newAssignee.getId()))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("title", equalTo(existingTask.getTitle()))
				.body("assignee.id", equalTo(newAssignee.getId().intValue()));

		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(newAssignee.getId());
	}

	@Test
	void shouldUnassignTaskWhenAssigneeIsExplicitlyNull() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);
		TaskRequestPartialDto taskUpdateDto = new TaskRequestPartialDto(
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.of(null)
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskCreateDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.extract()
				.jsonPath()
				.getLong("id");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", createdId)
				.body(taskUpdateDto)
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.assertThat()
				.body("assignee", nullValue());

		assertThat(repository.findById(createdId).orElseThrow().getAssignee()).isNull();
	}

	@Test
	void shouldReturn400WhenTitleIsExplicitlyNullInPatch() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);
		TaskRequestPartialDto taskUpdateDto = new TaskRequestPartialDto(
				JsonNullable.of(null),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined(),
				JsonNullable.undefined()
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskCreateDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.extract()
				.jsonPath()
				.getLong("id");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", createdId)
				.body(taskUpdateDto)
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(400);
	}

	@ParameterizedTest
	@MethodSource("invalidPatchBodies")
	void shouldReturn400AndKeepTaskUnchangedWhenPatchIsInvalid(String body, String field, String expectedCode) {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(body)
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("code", equalTo("validation.failed"))
				.body("detail", equalTo("The request has validation errors"))
				.body("errors", hasSize(1))
				.body("errors[0].pointer", equalTo("#/" + field))
				.body("errors[0].code", equalTo(expectedCode));

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getTitle()).isEqualTo(existingTask.getTitle());
		assertThat(storedTask.getDescription()).isEqualTo(existingTask.getDescription());
		assertThat(storedTask.getType()).isEqualTo(existingTask.getType());
		assertThat(storedTask.getStatus()).isEqualTo(existingTask.getStatus());
		assertThat(storedTask.getPriority()).isEqualTo(existingTask.getPriority());
		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(assignee.getId());
	}

	@Test
	void shouldReturn400AndKeepTaskUnchangedWhenPatchEnumValueIsUnknown() {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(Map.of("status", "UNKNOWN"))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(400)
				.body("errors[0].pointer", equalTo("#/status"))
				.body("errors[0].code", equalTo("task.status.invalidFormat"));

		assertThat(repository.findById(existingTask.getId()).orElseThrow().getStatus()).isEqualTo(existingTask.getStatus());
	}

	@Test
	void shouldReturn400AndKeepAssigneeWhenPatchAssigneeIdIsNotNumeric() {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(Map.of("assigneeId", "abc"))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(400)
				.body("code", equalTo("validation.failed"))
				.body("errors", hasSize(1))
				.body("errors[0].pointer", equalTo("#/assigneeId"))
				.body("errors[0].code", equalTo("task.assigneeId.invalidFormat"))
				.body("errors[0].detail", equalTo("Assignee id must be a number"));

		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(assignee.getId());
	}

	@Test
	void shouldRemoveDescriptionWhenPatchDescriptionIsExplicitlyNull() {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body("{\"description\": null}")
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("description", nullValue())
				.body("title", equalTo(existingTask.getTitle()));

		assertThat(repository.findById(existingTask.getId()).orElseThrow().getDescription()).isNull();
	}

	@Test
	void shouldSetUpdatedDateOnlyWhenTaskIsModified() {
		Task existingTask = saveTask("Title");
		assertThat(existingTask.getUpdatedDate()).isNull();

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body("{}")
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("updatedDate", nullValue());

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(Map.of("status", "DONE"))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.body("updatedDate", notNullValue());

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getUpdatedDate()).isNotNull();
		assertThat(storedTask.getCreatedDate()).isEqualTo(existingTask.getCreatedDate());
	}

	@Test
	void shouldReturn404AndKeepAssigneeWhenPatchAssigneeDoesNotExist() {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(Map.of("assigneeId", 999999L))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no user with id: 999999"))
				.body("code", equalTo("user.notFound"));

		assertThat(assigneeIdOf(existingTask.getId())).isEqualTo(assignee.getId());
	}

	@Test
	void shouldReturn404WhenPatchingNonExistentTask() {
		given()
				.contentType(ContentType.JSON)
				.pathParam("id", 999999L)
				.body(Map.of("status", "DONE"))
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no task with id: 999999"))
				.body("code", equalTo("task.notFound"));
	}

	// DELETE

	@Test
	void shouldDeleteTask() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee.getId()
		);

		Long createdId = given()
				.contentType(ContentType.JSON)
				.body(taskCreateDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(201)
				.extract()
				.jsonPath()
				.getLong("id");

		given()
				.pathParam("id", createdId)
				.when()
				.delete("/api/tasks/{id}")
				.then()
				.statusCode(204);

		given()
				.pathParam("id", createdId)
				.when()
				.get("/api/tasks/{id}")
				.then()
				.statusCode(404);
	}

	@Test
	void shouldReturn404WhenDeletingNonExistentTask() {
		given()
				.pathParam("id", 999999L)
				.when()
				.delete("/api/tasks/{id}")
				.then()
				.statusCode(404);
	}

	@Test
	void shouldUnassignTasksWhenAssigneeIsDeleted() {
		Task task = saveTask("Title");

		userRepository.delete(assignee);

		assertThat(repository.count()).isEqualTo(1);
		assertThat(assigneeIdOf(task.getId())).isNull();
	}

	private Task saveTask(String title) {
		return repository.save(new Task(
				title,
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				assignee
		));
	}

	private AppUser saveUser(String username, String email) {
		return userRepository.save(new AppUser("Firstname", "Lastname", username, email, "passwordHash", UserRole.USER));
	}

	private Long assigneeIdOf(Long taskId) {
		return given()
				.pathParam("id", taskId)
				.when()
				.get("/api/tasks/{id}")
				.then()
				.statusCode(200)
				.extract()
				.jsonPath()
				.getObject("assignee.id", Long.class);
	}
}
