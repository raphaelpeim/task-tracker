package com.personal.task_tracker.auth;

import com.personal.task_tracker.auth.config.JwtConfig;
import com.personal.task_tracker.auth.config.JwtProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application must refuse to start with a signing key too short for HS256. The length is checked on the decoded
 * bytes, not on the Base64 text: 32 bytes give 44 Base64 characters.
 */
class JwtConfigTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static JwtConfig configWithKeyOf(int bytes) {
		byte[] key = new byte[bytes];
		RANDOM.nextBytes(key);
		String secret = Base64.getEncoder().encodeToString(key);
		return new JwtConfig(new JwtProperties(secret, "task-tracker", Duration.ofHours(1)));
	}

	@Test
	void shouldAcceptKeyOf32Bytes() {
		JwtConfig config = configWithKeyOf(32);

		assertThatNoException().isThrownBy(config::jwtEncoder);
		assertThatNoException().isThrownBy(config::jwtDecoder);
	}

	/**
	 * 31 bytes encode to 44 Base64 characters, like 32 bytes: a check on the text length would accept it
	 */
	@Test
	void shouldRejectKeyOf31Bytes() {
		JwtConfig config = configWithKeyOf(31);

		assertThatThrownBy(config::jwtEncoder)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("app.jwt.secret must decode to at least 32 bytes, got 31");
		assertThatThrownBy(config::jwtDecoder).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldNotLeakTheSecretInTheErrorMessage() {
		byte[] key = new byte[16];
		RANDOM.nextBytes(key);
		String secret = Base64.getEncoder().encodeToString(key);
		JwtConfig config = new JwtConfig(new JwtProperties(secret, "task-tracker", Duration.ofHours(1)));

		assertThatThrownBy(config::jwtEncoder)
				.isInstanceOf(IllegalStateException.class)
				.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(secret));
	}
}
