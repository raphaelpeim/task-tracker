package com.personal.task_tracker.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.personal.task_tracker.IntegrationTest;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Protection of the API by the JWT resource server: which requests reach a protected endpoint, and the body of the
 * 401 when they do not. GET /api/tasks stands for any protected endpoint.
 */
class JwtAuthenticationTest extends IntegrationTest {

	private static final String PROTECTED_URL = "/api/tasks";

	@Autowired
	private UserRepository repository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void removeDefaultToken() {
		sendRequestsWithoutToken();
	}

	@Test
	void shouldReachProtectedEndpointWithValidToken() {
		given()
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
				.when()
				.get(PROTECTED_URL)
				.then()
				.statusCode(200);
	}

	@Test
	void shouldReturn401WithoutToken() {
		assertUnauthorized(given());
	}

	/**
	 * RFC 9110 requires a challenge on every 401; RFC 6750 defines the Bearer one. No error without a token. Spring
	 * Security 7 also adds a resource_metadata parameter (RFC 9728), not checked here
	 */
	@Test
	void shouldSendBearerChallengeWithoutToken() {
		given()
				.when()
				.get(PROTECTED_URL)
				.then()
				.statusCode(401)
				.header(HttpHeaders.WWW_AUTHENTICATE, allOf(startsWith("Bearer"), not(containsString("error="))));
	}

	@Test
	void shouldSendInvalidTokenChallengeWhenTokenIsRejected() {
		given()
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
				.when()
				.get(PROTECTED_URL)
				.then()
				.statusCode(401)
				.header(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\""));
	}

	@Test
	void shouldReturn401WhenTokenIsSignedWithAnotherKey() {
		byte[] otherKey = "another-key-of-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);
		NimbusJwtEncoder otherEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
		String forgedToken = otherEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(),
				claims(jwtProperties.issuer(), Instant.now().plus(Duration.ofHours(1)))
		)).getTokenValue();

		assertUnauthorized(given().header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken));
	}

	/**
	 * Expired one hour ago: far beyond the 60 seconds of clock skew tolerated by the decoder
	 */
	@Test
	void shouldReturn401WhenTokenIsExpired() {
		Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
		String expiredToken = token(JwtClaimsSet.builder()
				.issuer(jwtProperties.issuer())
				.subject(DEFAULT_SUBJECT)
				.issuedAt(oneHourAgo.minus(Duration.ofHours(1)))
				.expiresAt(oneHourAgo)
				.build());

		assertUnauthorized(given().header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken));
	}

	@Test
	void shouldReturn401WhenIssuerIsWrong() {
		String token = token(claims("another-issuer", Instant.now().plus(Duration.ofHours(1))));

		assertUnauthorized(given().header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	@Test
	void shouldReturn401WhenTokenIsNotAJwt() {
		assertUnauthorized(given().header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));
	}

	/**
	 * A valid token sent with another scheme is ignored: the request is anonymous
	 */
	@Test
	void shouldReturn401WhenSchemeIsNotBearer() {
		assertUnauthorized(given().header(HttpHeaders.AUTHORIZATION, "Token " + validToken()));
	}

	@Test
	void shouldRegisterWithoutToken() {
		given()
				.contentType(ContentType.JSON)
				.body(Map.of(
						"firstname", "John",
						"lastname", "Doe",
						"username", "jdoe",
						"email", "jdoe@mail.com",
						"password", "password123"
				))
				.when()
				.post("/api/auth/register")
				.then()
				.statusCode(201);
	}

	@Test
	void shouldLoginWithoutToken() {
		repository.save(new AppUser("John", "Doe", "jdoe", "jdoe@mail.com", passwordEncoder.encode("password123"), UserRole.USER));

		given()
				.contentType(ContentType.JSON)
				.body(Map.of("email", "jdoe@mail.com", "password", "password123"))
				.when()
				.post("/api/auth/login")
				.then()
				.statusCode(200);
	}

	/**
	 * Only POST is public on the auth routes
	 */
	@Test
	void shouldReturn401WhenAuthRouteIsCalledWithAnotherMethod() {
		assertUnauthorized(given(), "/api/auth/login");
	}

	private static JwtClaimsSet claims(String issuer, Instant expiresAt) {
		return JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(DEFAULT_SUBJECT)
				.issuedAt(Instant.now())
				.expiresAt(expiresAt)
				.build();
	}

	private static void assertUnauthorized(RequestSpecification request) {
		assertUnauthorized(request, PROTECTED_URL);
	}

	private static void assertUnauthorized(RequestSpecification request, String url) {
		request
				.when()
				.get(url)
				.then()
				.statusCode(401)
				.contentType("application/problem+json")
				.body("code", equalTo("request.unauthorized"))
				.body("detail", equalTo("Authentication is required"))
				.body("instance", equalTo(url));
	}
}
