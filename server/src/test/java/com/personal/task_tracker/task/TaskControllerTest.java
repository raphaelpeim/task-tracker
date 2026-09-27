package com.personal.task_tracker.task;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.dto.TaskRequestPartialDto;
import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import com.personal.task_tracker.task.repository.TaskRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TaskControllerTest {

	@LocalServerPort
	private int port;

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private TaskRepository taskRepository;

	@BeforeEach
	void setUp() {
		RestAssured.port = port;
		Locale.setDefault(Locale.ENGLISH);
	}

	@AfterEach
	void cleanUp() {
		taskRepository.deleteAll();
	}

	@Test
	void shouldCreateAndRetrieveTask(){
		TaskRequestDto taskDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.FEATURE,
				TaskStatus.READY,
				TaskPriority.HIGH,
				"Assignee"
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
				.body("assignee", equalTo(taskDto.assignee()));
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
	void shouldReturn400WhenTitleIsBlank() {
		TaskRequestDto invalidDto = new TaskRequestDto(
				"",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				"Assignee"
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
	void shouldUpdateOnlyStatusAndKeepOtherFieldsUnchanged() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				"Assignee"
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
				.body("assignee", equalTo(taskCreateDto.assignee()));
	}

	@Test
	void shouldUnassignTaskWhenAssigneeIsExplicitlyNull() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				"Assignee"
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
	}

	@Test
	void shouldReturn400WhenTitleIsExplicitlyNullInPatch() {
		TaskRequestDto taskCreateDto = new TaskRequestDto(
				"Title",
				"Description",
				TaskType.BUG,
				TaskStatus.BACKLOG,
				TaskPriority.LOW,
				"Assignee"
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
				"Assignee"
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
}
