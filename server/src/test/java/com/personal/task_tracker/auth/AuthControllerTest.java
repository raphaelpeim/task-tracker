package com.personal.task_tracker.auth;

import com.personal.task_tracker.IntegrationTest;
import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class AuthControllerTest extends IntegrationTest {

	private static final String RAW_PASSWORD = "password123";
	// Spy declared in IntegrationTest: stubbable here, real behaviour elsewhere
	@Autowired
	private UserRepository repository;

	// Spy declared in IntegrationTest
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

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

	// LOGIN

	@Test
	void shouldLoginAndReturnOnlyTheToken() {
		AppUser user = saveUserWithPassword("jdoe@mail.com", RAW_PASSWORD);

		String accessToken = given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "jdoe@mail.com", "password", RAW_PASSWORD))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(200)
				.body("tokenType", equalTo("Bearer"))
				.body("expiresIn", equalTo((int) jwtProperties.expiration().toSeconds()))
				.body("keySet()", containsInAnyOrder("accessToken", "tokenType", "expiresIn"))
				.extract().path("accessToken");

		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(jwtProperties.issuer());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(jwtProperties.expiration());
		// Decision of 12/11: the role is read from the database when needed, never carried by the token
		assertThat(jwt.getClaims()).doesNotContainKey("roles");
	}

	@Test
	void shouldLoginWhenEmailIsInAnotherCase() {
		saveUserWithPassword("jdoe@mail.com", RAW_PASSWORD);

		given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "JDoe@Mail.com", "password", RAW_PASSWORD))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(200);
	}

	/**
	 * Decision of 12/11: the client cannot tell an unknown email from a wrong password
	 */
	@Test
	void shouldReturnSameErrorWhenEmailIsUnknownOrPasswordIsWrong() {
		saveUserWithPassword("jdoe@mail.com", RAW_PASSWORD);

		Map<String, Object> wrongPassword = given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "jdoe@mail.com", "password", "wrongPassword"))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(401)
				.contentType("application/problem+json")
				.extract().as(new TypeRef<>() {
				});

		Map<String, Object> unknownEmail = given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "unknown@mail.com", "password", RAW_PASSWORD))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(401)
				.contentType("application/problem+json")
				.extract().as(new TypeRef<>() {
				});

		assertThat(unknownEmail).isEqualTo(wrongPassword);
		assertThat(wrongPassword)
				.containsEntry("code", "auth.invalidCredentials")
				.containsEntry("detail", "Invalid email or password");
	}

	/**
	 * Decision of 12/11: an unknown email must take as long as a wrong password, otherwise the response time tells
	 * which emails are registered. Measuring the time would make the test flaky: it checks instead that a password
	 * comparison (the slow part, BCrypt) happens in both cases.
	 */
	@Test
	void shouldComparePasswordEvenWhenEmailIsUnknown() {
		given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "unknown@mail.com", "password", RAW_PASSWORD))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(401);

		verify(passwordEncoder).matches(eq(RAW_PASSWORD), anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"email", "password"})
	void shouldReturn400WhenLoginFieldIsMissing(String missingField) {
		Map<String, String> body = new HashMap<>(Map.of("email", "jdoe@mail.com", "password", RAW_PASSWORD));
		body.remove(missingField);

		given()
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(400)
				.contentType("application/problem+json")
				.body("errors.find { it.pointer == '#/" + missingField + "' }.code", equalTo("auth." + missingField + ".required"));
	}

	/**
	 * BCrypt ignores the bytes after the 72nd when comparing: a longer password is rejected before any comparison
	 */
	@Test
	void shouldReturn400WhenLoginPasswordExceeds72Bytes() {
		given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "jdoe@mail.com", "password", "a".repeat(73)))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(400)
				.body("errors.find { it.pointer == '#/password' }.code", equalTo("auth.password.invalidSize"))
				.body("errors.find { it.pointer == '#/password' }.detail", equalTo("Password must be at most 72 bytes"));
	}

	private AppUser saveUserWithPassword(String email, String rawPassword) {
		return repository.save(new AppUser("John", "Doe", "jdoe", email, passwordEncoder.encode(rawPassword), UserRole.USER));
	}

	private RegisterRequestDto registerRequest(String username, String email, String password) {
		return new RegisterRequestDto("John", "Doe", username, email, password);
	}

	private void saveUser(String username, String email) {
		repository.save(new AppUser("Jane", "Doe", username, email, "hash", UserRole.USER));
	}
}
