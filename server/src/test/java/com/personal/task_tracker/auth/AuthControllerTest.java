package com.personal.task_tracker.auth;

import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthControllerTest {

	private static final String RAW_PASSWORD = "password123";

	@LocalServerPort
	private int port;

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository repository;

	@Autowired
	private PasswordEncoder passwordEncoder;

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
	void shouldRegisterUserWithoutExposingPassword() {
		RegisterRequestDto registerDto = registerRequest("jdoe", "jdoe@mail.com", RAW_PASSWORD);

		given()
				.contentType(ContentType.JSON)
				.body(registerDto)
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201)
				.body("id", notNullValue())
				.body("firstname", equalTo(registerDto.firstname()))
				.body("lastname", equalTo(registerDto.lastname()))
				.body("username", equalTo(registerDto.username()))
				.body("email", equalTo(registerDto.email()))
				.body("role", equalTo(UserRole.USER.name()))
				.body("$", not(hasKey("password")))
				.body("$", not(hasKey("rawPassword")));
	}

	@Test
	void shouldStoreHashedPassword() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201);

		AppUser storedUser = repository.findByEmail("jdoe@mail.com").orElseThrow();
		assertThat(storedUser.getPassword()).isNotEqualTo(RAW_PASSWORD);
		assertThat(passwordEncoder.matches(RAW_PASSWORD, storedUser.getPassword())).isTrue();
	}

	@Test
	void shouldReturn409WhenUsernameAlreadyExists() {
		saveUser("jdoe", "jdoe@mail.com");

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "other@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("error", equalTo("Username already exists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn409WhenEmailAlreadyExists() {
		saveUser("jdoe", "jdoe@mail.com");

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("other", "jdoe@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("error", equalTo("Email already exists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn400WhenEmailIsInvalid() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "not-an-email", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("email", equalTo("must be a well-formed email address"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturn400ForEachMissingField() {
		given()
				.contentType(ContentType.JSON)
				.body("{}")
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("firstname", equalTo("must not be blank"))
				.body("lastname", equalTo("must not be blank"))
				.body("username", equalTo("must not be blank"))
				.body("email", equalTo("must not be blank"))
				.body("password", equalTo("must not be blank"));
	}

	@ParameterizedTest
	@ValueSource(ints = {8, 72})
	void shouldRegisterWhenPasswordLengthIsAtBounds(int length) {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "a".repeat(length)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201);
	}

	@ParameterizedTest
	@ValueSource(ints = {7, 73})
	void shouldReturn400WhenPasswordLengthIsOutOfBounds(int length) {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "a".repeat(length)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("password", equalTo("size must be between 8 and 72"));

		assertThat(repository.count()).isZero();
	}

	private RegisterRequestDto registerRequest(String username, String email, String password) {
		return new RegisterRequestDto("John", "Doe", username, email, password);
	}

	private void saveUser(String username, String email) {
		repository.save(new AppUser("Jane", "Doe", username, email, "hash", UserRole.USER));
	}
}
