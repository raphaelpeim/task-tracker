package com.personal.task_tracker.task;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.entity.Task;
import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TaskControllerTest {

	@LocalServerPort
	private int port;

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private TaskRepository repository;

	@BeforeEach
	void setUp() {
		RestAssured.port = port;
		Locale.setDefault(Locale.ENGLISH);
	}

	@AfterEach
	void cleanUp() {
		repository.deleteAll();
	}

	@Test
	void shouldCreateAndRetrieveTask(){
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.FEATURE,
				TaskStatus.READY,
				TaskPriority.HIGH,
				1L
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
				.body("assignee", equalTo(taskDto.assigneeId()));
	}

	@Test
	void shouldReturn400WhenTitleIsBlank() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("title", equalTo("must not be blank"));
	}

	@Test
	void shouldReturn400WhenTitleIsTooLong() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"Title too long because it is supposed to be 50/less",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("title", equalTo("size must be between 0 and 50"));
	}

	@Test
	void shouldReturn400WhenDescriptionIsTooLong() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"Title",
				"Description description description description description description description description description description description description description description description description description description description description description description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("description", equalTo("size must be between 0 and 255"));
	}

	@Test
	void shouldReturn400WhenTypeIsMissing() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				null,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.body(taskDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("type", equalTo("must not be null"));
	}

	@Test
	void shouldReturn404WhenTaskNotFound() {
		given()
				.pathParam("id", 999999L)
				.when()
				.get("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("error", equalTo("There is no task with id: 999999"));
	}

	@Test
	void shouldUpdateOnlyStatusAndKeepOtherFieldsUnchanged() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
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
				.body("assignee", equalTo(taskCreateDto.assigneeId()));

		Task storedTask = repository.findById(createdId).orElseThrow();
		assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
		assertThat(storedTask.getTitle()).isEqualTo(taskCreateDto.title());
		assertThat(storedTask.getDescription()).isEqualTo(taskCreateDto.description());
		assertThat(storedTask.getType()).isEqualTo(taskCreateDto.type());
		assertThat(storedTask.getPriority()).isEqualTo(taskCreateDto.priority());
		assertThat(storedTask.getAssignee()).isEqualTo(taskCreateDto.assigneeId());
	}

	@Test
	void shouldUnassignTaskWhenAssigneeIsExplicitlyNull() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
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
				.body("assignee", equalTo(null));

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
				1L
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
	void shouldDeleteTask() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
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

	// POST

	@Test
	void shouldCreateTaskWhenFieldsAreAtMaxLength() {
		TaskRequestDto taskDto = new TaskRequestDto(
				"a".repeat(50),
				"a".repeat(255),
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
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
	void shouldReturn400WhenAssigneeIsTooLong() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.body(invalidDto)
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("assignee", equalTo("size must be between 0 and 50"));
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
	void shouldReturn400ForEachMissingRequiredField() {
		given()
				.contentType(ContentType.JSON)
				.body("{}")
				.when()
				.post("/api/tasks")
				.then()
				.statusCode(400)
				.body("title", equalTo("must not be blank"))
				.body("description", equalTo("must not be blank"))
				.body("type", equalTo("must not be null"))
				.body("status", equalTo("must not be null"))
				.body("priority", equalTo("must not be null"))
				.body("$", not(hasKey("assignee")));
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
				.body("error", equalTo("The request body is wrong or contains an invalid value"));

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

	@Test
	void shouldReturn400WhenIdIsNotNumeric() {
		given()
				.when()
				.get("/api/tasks/abc")
				.then()
				.statusCode(400);
	}

	// PUT

	@Test
	void shouldReplaceAllFieldsOnPut() {
		Task existingTask = saveTask("Title");
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"New title",
				"New description",
				TaskType.FEATURE,
				TaskStatus.DONE,
				TaskPriority.CRITICAL,
				1L
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
				.body("status", equalTo(taskUpdateDto.status().toString()));

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getTitle()).isEqualTo(taskUpdateDto.title());
		assertThat(storedTask.getDescription()).isEqualTo(taskUpdateDto.description());
		assertThat(storedTask.getType()).isEqualTo(taskUpdateDto.type());
		assertThat(storedTask.getStatus()).isEqualTo(taskUpdateDto.status());
		assertThat(storedTask.getPriority()).isEqualTo(taskUpdateDto.priority());
		assertThat(storedTask.getAssignee()).isEqualTo(taskUpdateDto.assigneeId());
		assertThat(storedTask.getCreatedDate()).isEqualTo(existingTask.getCreatedDate());
		assertThat(storedTask.getUpdatedDate()).isAfterOrEqualTo(existingTask.getUpdatedDate());
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
	void shouldReturn404WhenUpdatingNonExistentTask() {
		TaskRequestDto taskUpdateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				1L
		);

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", 999999L)
				.body(taskUpdateDto)
				.when()
				.put("/api/tasks/{id}")
				.then()
				.statusCode(404)
				.body("error", equalTo("There is no task with id: 999999"));
	}

	// PATCH

	static Stream<Arguments> invalidPatchBodies() {
		return Stream.of(
				Arguments.of(Map.of("title", ""), "title cannot be null nor blank"),
				Arguments.of(Map.of("title", "   "), "title cannot be null nor blank"),
				Arguments.of(Map.of("title", "a".repeat(51)), "title cannot exceed 50 characters"),
				Arguments.of(Map.of("description", "   "), "description cannot be null nor blank"),
				Arguments.of(Map.of("description", "a".repeat(256)), "description cannot exceed 255 characters"),
				Arguments.of(Map.of("assignee", "a".repeat(51)), "assignee cannot exceed 50 characters")
		);
	}

	@ParameterizedTest
	@MethodSource("invalidPatchBodies")
	void shouldReturn400AndKeepTaskUnchangedWhenPatchIsInvalid(Map<String, String> body, String expectedError) {
		Task existingTask = saveTask("Title");

		given()
				.contentType(ContentType.JSON)
				.pathParam("id", existingTask.getId())
				.body(body)
				.when()
				.patch("/api/tasks/{id}")
				.then()
				.statusCode(400)
				.body("error", equalTo(expectedError));

		Task storedTask = repository.findById(existingTask.getId()).orElseThrow();
		assertThat(storedTask.getTitle()).isEqualTo(existingTask.getTitle());
		assertThat(storedTask.getDescription()).isEqualTo(existingTask.getDescription());
		assertThat(storedTask.getAssignee()).isEqualTo(existingTask.getAssignee());
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
		assertThat(storedTask.getAssignee()).isEqualTo(existingTask.getAssignee());
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
				.body("error", equalTo("There is no task with id: 999999"));
	}

	private Task saveTask(String title) {
		return repository.save(new Task(
				title,
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				new AppUser(
						"Firstname",
						"Lastname",
						"Username",
						"email@mail.com",
						"passwordHash",
						UserRole.USER
				)
		));
	}
}
