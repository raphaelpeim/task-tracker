package com.personal.task_tracker.auth;

import com.personal.task_tracker.IntegrationTest;
import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

class AuthControllerTest extends IntegrationTest {

	private static final String RAW_PASSWORD = "password123";
	// Spy declared in IntegrationTest: stubbable here, real behaviour elsewhere
	@Autowired
	private UserRepository repository;

	@Autowired
	private PasswordEncoder passwordEncoder;

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

	/**
	 * The body is a raw map: RegisterRequestDto has no role field, so it could not send one.
	 * Protects against a role added to the request DTO later.
	 */
	@Test
	void shouldIgnoreRoleSentInBody() {
		Map<String, String> body = Map.of(
				"firstname", "John",
				"lastname", "Doe",
				"username", "jdoe",
				"email", "jdoe@mail.com",
				"password", RAW_PASSWORD,
				"role", UserRole.ADMIN.name()
		);

		given()
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201)
				.body("role", equalTo(UserRole.USER.name()));

		assertThat(repository.findAll().getFirst().getRole()).isEqualTo(UserRole.USER);
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

		AppUser storedUser = repository.findAll().getFirst();
		assertThat(storedUser.getPassword()).isNotEqualTo(RAW_PASSWORD);
		assertThat(storedUser.getPassword()).hasSizeLessThanOrEqualTo(255);
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
				.contentType("application/problem+json")
				.body("detail", equalTo("Username already exists"))
				.body("code", equalTo("user.username.alreadyExists"));

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
				.body("detail", equalTo("Email already exists"))
				.body("code", equalTo("user.email.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn409WhenUsernameDiffersOnlyByCase() {
		saveUser("JDoe", "jdoe@mail.com");

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "other@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("detail", equalTo("Username already exists"))
				.body("code", equalTo("user.username.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn409WhenEmailDiffersOnlyByCase() {
		saveUser("jdoe", "jdoe@mail.com");

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("other", "JDoe@Mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("detail", equalTo("Email already exists"))
				.body("code", equalTo("user.email.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn409WhenSameMixedCaseEmailIsRegisteredTwice() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "JDoe@Mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201);

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("other", "JDoe@Mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("detail", equalTo("Email already exists"))
				.body("code", equalTo("user.email.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldKeepUsernameCaseAndStoreLowercaseEmail() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("  JDoe  ", "JDoe@Mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201)
				.body("username", equalTo("JDoe"))
				.body("email", equalTo("jdoe@mail.com"));

		AppUser storedUser = repository.findAll().getFirst();
		assertThat(storedUser.getUsername()).isEqualTo("JDoe");
	}

	@Test
	void shouldRejectUsernameDifferingOnlyByCaseAtDatabaseLevel() {
		saveUser("JDoe", "jdoe@mail.com");

		assertThatThrownBy(() -> saveUser("jdoe", "other@mail.com"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex() {
		saveUser("JDoe", "jdoe@mail.com");
		// Simulates a concurrent registration: the check passes, the insert hits the unique index
		doReturn(false).when(repository).existsByUsernameIgnoreCase(anyString());

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "other@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.contentType("application/problem+json")
				.body("detail", equalTo("Username already exists"))
				.body("code", equalTo("user.username.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn409WhenConcurrentRegistrationHitsEmailConstraint() {
		saveUser("jdoe", "jdoe@mail.com");
		// Simulates a concurrent registration: the check passes, the insert hits the unique constraint
		doReturn(false).when(repository).existsByEmail(anyString());

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("other", "jdoe@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(409)
				.body("detail", equalTo("Email already exists"))
				.body("code", equalTo("user.email.alreadyExists"));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void shouldReturn500WhenAnotherConstraintIsViolated() {
		doThrow(new DataIntegrityViolationException("other constraint")).when(repository).saveAndFlush(any());

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(500)
				.contentType("application/problem+json")
				.body("status", equalTo(500))
				.body("code", equalTo("server.internalError"))
				.body("detail", equalTo("An unexpected error occurred"));
	}

	@Test
	void shouldReturn500WithoutInternalMessageWhenUnexpectedErrorOccurs() {
		doThrow(new IllegalStateException("internal message")).when(repository).existsByUsernameIgnoreCase(anyString());

		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", RAW_PASSWORD))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(500)
				.contentType("application/problem+json")
				.body("title", equalTo("Internal Server Error"))
				.body("code", equalTo("server.internalError"))
				.body("detail", equalTo("An unexpected error occurred"));
	}

	@Test
	void shouldReturn400WithFieldErrorWhenFirstnameIsNotAText() {
		given()
				.contentType(ContentType.JSON)
				.body("{\"firstname\": [\"John\"], \"lastname\": \"Doe\", \"username\": \"jdoe\", \"email\": \"jdoe@mail.com\", \"password\": \"password123\"}")
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("code", equalTo("validation.failed"))
				.body("errors[0].pointer", equalTo("#/firstname"))
				.body("errors[0].code", equalTo("user.firstname.invalidFormat"));

		assertThat(repository.count()).isZero();
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
				.body("errors.find { it.pointer == '#/email' }.code", equalTo("user.email.invalidFormat"));

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
				.body("errors.find { it.pointer == '#/firstname' }.code", equalTo("user.firstname.required"))
				.body("errors.find { it.pointer == '#/lastname' }.code", equalTo("user.lastname.required"))
				.body("errors.find { it.pointer == '#/username' }.code", equalTo("user.username.required"))
				.body("errors.find { it.pointer == '#/email' }.code", equalTo("user.email.required"))
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("user.password.required"));
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

	@Test
	void shouldReturn400WhenPasswordIsTooShort() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "a".repeat(7)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("user.password.invalidSize"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturnOnlyNotBlankErrorWhenPasswordIsBlankAndTooShort() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "   "))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("user.password.required"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturn400WhenPasswordExceeds72Characters() {
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "a".repeat(73)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("user.password.invalidSize"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldReturn400WhenPasswordExceeds72BytesWithFewerCharacters() {
		// 40 accented characters = 80 bytes in UTF-8
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "é".repeat(40)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("user.password.invalidSize"))
				.body("errors.find { it.pointer == '#/password' }.detail", equalTo("Password must contain at least 8 characters and at most 72 bytes"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void shouldRegisterWhenPasswordIsExactly72Bytes() {
		// 36 accented characters = 72 bytes in UTF-8
		given()
				.contentType(ContentType.JSON)
				.body(registerRequest("jdoe", "jdoe@mail.com", "é".repeat(36)))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201);
	}

	private RegisterRequestDto registerRequest(String username, String email, String password) {
		return new RegisterRequestDto("John", "Doe", username, email, password);
	}

	private void saveUser(String username, String email) {
		repository.save(new AppUser("Jane", "Doe", username, email, "hash", UserRole.USER));
	}
}
