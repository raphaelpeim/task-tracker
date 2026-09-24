package com.personal.task_tracker.task;

import com.personal.task_tracker.task.dto.TaskRequestDto;
import com.personal.task_tracker.task.enums.TaskPriority;
import com.personal.task_tracker.task.enums.TaskStatus;
import com.personal.task_tracker.task.enums.TaskType;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

	@BeforeEach
	void setUp() {
		RestAssured.port = port;
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
				.body("assignee", equalTo(taskDto.assignee()))
		;
	}
}
