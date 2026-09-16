package com.personal.task_tracker;

import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Common configuration of the integration tests.
 * Every test class extends it so that they all share the same cached Spring context, and therefore the same
 * PostgreSQL container: any annotation that changes the context (bean override, properties...) must be declared here,
 * never on a single test class.
 * The UserRepository spy behaves like the real repository unless a test stubs it, and is reset after each test.
 * Hibernate statistics are enabled so that a test can count the SQL queries of a request.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.jpa.properties.hibernate.generate_statistics=true",
				"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
		}
)
@Import(TestcontainersConfiguration.class)
@MockitoSpyBean(types = UserRepository.class)
public abstract class IntegrationTest {

	@LocalServerPort
	private int port;
	@Autowired
	private TaskRepository taskRepository;
	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void setUpRestAssured() {
		RestAssured.port = port;
	}

	/**
	 * Tasks first: they reference the users
	 */
	@AfterEach
	void cleanDatabase() {
		taskRepository.deleteAll();
		userRepository.deleteAll();
	}
}
