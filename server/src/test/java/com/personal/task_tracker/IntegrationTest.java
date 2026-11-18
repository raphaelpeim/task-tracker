package com.personal.task_tracker;

import com.personal.task_tracker.auth.config.JwtProperties;
import com.personal.task_tracker.task.repository.TaskRepository;
import com.personal.task_tracker.user.repository.UserRepository;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Common configuration of the integration tests.
 * Every test class extends it so that they all share the same cached Spring context, and therefore the same
 * PostgreSQL container: any annotation that changes the context (bean override, properties...) must be declared here,
 * never on a single test class.
 * The UserRepository and PasswordEncoder spies behave like the real beans unless a test stubs them, and are reset after
 * each test.
 * Hibernate statistics are enabled so that a test can count the SQL queries of a request.
 * <p>
 * Every request is authenticated by default with a valid token: the API is protected, and most tests are about
 * something else. A test about authentication calls {@link #sendRequestsWithoutToken()} first.
 * The JWT signing key is drawn at random once per test run: no secret is stored in the repository, and the tests
 * never depend on a particular key.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.jpa.properties.hibernate.generate_statistics=true",
				"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
		}
)
@Import(TestcontainersConfiguration.class)
@MockitoSpyBean(types = {UserRepository.class, PasswordEncoder.class})
public abstract class IntegrationTest {

	/**
	 * Subject of the default token. No user is stored for it: the protected endpoints only check the token today
	 */
	protected static final String DEFAULT_SUBJECT = "1";

	/**
	 * 32 random bytes, the minimum for HS256, encoded in Base64 as app.jwt.secret expects
	 */
	private static final String JWT_SIGNING_KEY = randomBase64Key();

	@LocalServerPort
	private int port;
	@Autowired
	private TaskRepository taskRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JwtEncoder jwtEncoder;
	@Autowired
	protected JwtProperties jwtProperties;

	@DynamicPropertySource
	static void jwtProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> JWT_SIGNING_KEY);
	}

	private static String randomBase64Key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	@BeforeEach
	void setUpRestAssured() {
		RestAssured.port = port;
		RestAssured.requestSpecification = new RequestSpecBuilder()
				.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
				.build();
	}

	/**
	 * Tasks first: they reference the users
	 */
	@AfterEach
	void cleanDatabase() {
		taskRepository.deleteAll();
		userRepository.deleteAll();
	}

	@AfterEach
	void resetRestAssured() {
		RestAssured.requestSpecification = null;
	}

	/**
	 * The next requests of the test are sent without the default Authorization header
	 */
	protected void sendRequestsWithoutToken() {
		RestAssured.requestSpecification = null;
	}

	/**
	 * @return a token signed with the application key, with the application issuer, valid for the configured duration
	 */
	protected String validToken() {
		Instant now = Instant.now();
		return token(JwtClaimsSet.builder()
				.issuer(jwtProperties.issuer())
				.subject(DEFAULT_SUBJECT)
				.issuedAt(now)
				.expiresAt(now.plus(jwtProperties.expiration()))
				.build());
	}

	/**
	 * @return the claims signed in HS256 with the application key
	 */
	protected String token(JwtClaimsSet claims) {
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
