package com.personal.task_tracker.commons.exception;

import com.personal.task_tracker.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Security is still disabled (permitAll), so the security exceptions are raised by SecurityErrorsTestController,
 * and the filter side is checked by calling SecurityProblemHandler directly.
 */
class SecurityErrorsTest extends IntegrationTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	// RAISED IN A CONTROLLER

	@Test
	void shouldReturn401WhenAuthenticationExceptionIsRaisedInAController() {
		given()
				.when()
				.get("/test/security/unauthorized")
				.then()
				.statusCode(401)
				.contentType("application/problem+json")
				.body("code", equalTo("request.unauthorized"))
				.body("detail", equalTo("Authentication is required"));
	}

	@Test
	void shouldReturn403WhenAccessDeniedExceptionIsRaisedInAController() {
		given()
				.when()
				.get("/test/security/forbidden")
				.then()
				.statusCode(403)
				.contentType("application/problem+json")
				.body("code", equalTo("request.forbidden"))
				.body("detail", equalTo("Access is denied"));
	}

	// RAISED BY THE SECURITY FILTERS

	@Test
	void shouldWrite401ProblemDetailFromTheEntryPoint() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
		MockHttpServletResponse response = new MockHttpServletResponse();

		new SecurityProblemHandler().commence(request, response, new InsufficientAuthenticationException("internal message"));

		assertProblem(response, 401, "request.unauthorized", "Authentication is required");
	}

	@Test
	void shouldWrite403ProblemDetailFromTheAccessDeniedHandler() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
		MockHttpServletResponse response = new MockHttpServletResponse();

		new SecurityProblemHandler().handle(request, response, new AccessDeniedException("internal message"));

		assertProblem(response, 403, "request.forbidden", "Access is denied");
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code, String detail)
			throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		JsonNode body = MAPPER.readTree(response.getContentAsString());
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("detail").asString()).isEqualTo(detail);
		assertThat(body.get("instance").asString()).isEqualTo("/api/tasks");
		assertThat(body.has("properties")).isFalse();
	}
}
