package com.personal.task_tracker.auth.service;

import com.personal.task_tracker.auth.config.JwtProperties;
import com.personal.task_tracker.user.entity.AppUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TokenService {
	private final JwtEncoder encoder;
	private final JwtProperties properties;

	public TokenService(JwtEncoder encoder, JwtProperties properties) {
		this.encoder = encoder;
		this.properties = properties;
	}

	/**
	 * Create a signed token for the user
	 *
	 * @param user the authenticated user
	 * @return the encoded token
	 */
	public String generateToken(AppUser user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plus(properties.expiration()))
				.claim("roles", List.of(user.getRole().name()))
				.build();

		// The header must name HS256: the encoder defaults to RS256 and would fail with a secret key
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
