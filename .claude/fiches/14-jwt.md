# 14. JWT : ajouter l'authentification au task-tracker

Ce guide part de l'état actuel du projet (Spring Boot 4.1.1, Spring Security 7, Java 25) et explique pas à pas
comment émettre un JWT au login, puis l'exiger sur les end-points. Chaque étape cite les fichiers concernés.

---

## 1. Comprendre ce qu'on construit

### Le principe

1. Le client envoie `POST /api/auth/login` avec email + mot de passe.
2. Le serveur vérifie le mot de passe (BCrypt, déjà en place via `PasswordEncoder`).
3. S'il est bon, le serveur **signe** un JWT et le renvoie.
4. Pour chaque appel suivant, le client ajoute l'en-tête :
   `Authorization: Bearer <token>`
5. Spring Security vérifie la signature et la date d'expiration du token **avant** d'atteindre le contrôleur.
   Token absent ou invalide : 401. Token valide mais rôle insuffisant : 403.

Aucune session côté serveur : tout ce qu'il faut savoir sur l'utilisateur est dans le token. On parle d'API
**stateless**.

### Anatomie d'un JWT

Un JWT est trois blocs Base64URL séparés par des points : `header.payload.signature`.

```json
// header
{ "alg": "HS256", "typ": "JWT" }

// payload (les "claims")
{
  "iss": "task-tracker",      // qui a émis le token
  "sub": "42",                // l'id de l'utilisateur
  "iat": 1761645600,          // émis à (secondes epoch)
  "exp": 1761649200,          // expire à
  "roles": ["USER"]           // claim personnalisé
}
```

La signature est calculée avec une clé secrète connue du seul serveur. Le payload est **lisible par tout le monde**
(il est encodé, pas chiffré) : ne jamais y mettre de mot de passe ni de donnée sensible.

### Pourquoi la dépendance déjà ajoutée suffit

`spring-boot-starter-security-oauth2-resource-server` (ajoutée dans `server/pom.xml`) apporte :

- le filtre qui lit l'en-tête `Authorization: Bearer` et valide le token (`BearerTokenAuthenticationFilter`) ;
- `JwtDecoder` / `JwtEncoder` et l'implémentation Nimbus pour signer et vérifier.

Pas besoin d'une lib tierce type `jjwt` : c'est un starter Spring, conforme au guardrail dépendances.

### Le choix de l'algorithme

| Option | Principe | Quand l'utiliser |
|---|---|---|
| **HS256** (clé symétrique) | Une seule clé secrète signe et vérifie | Un seul service émet et lit les tokens : notre cas |
| RS256 (paire de clés) | Clé privée pour signer, publique pour vérifier | Plusieurs services vérifient les tokens |

Le guide utilise **HS256**.

---

## 2. Configuration : la clé secrète et la durée de vie

### 2.1 Générer une clé

HS256 exige une clé d'au moins 256 bits (32 octets). Pour en générer une :

```bash
openssl rand -base64 32
```

### 2.2 Externaliser la configuration

La clé ne doit **jamais** être commitée. Elle passe par une variable d'environnement.

`server/src/main/resources/application.properties` :

```properties
app.jwt.secret=${JWT_SECRET}
app.jwt.issuer=task-tracker
app.jwt.expiration=PT1H
```

- `PT1H` est une `Duration` ISO-8601 (1 heure). Spring la convertit toute seule.
- Si `JWT_SECRET` n'est pas définie, l'application refuse de démarrer : c'est voulu.

En local, exporter la variable avant de lancer l'application (ou la déclarer dans la configuration de lancement de
l'IDE). Si tu préfères la passer par `application-local.properties`, documenter la clé `app.jwt.secret=`
**sans valeur** dans `application-local.properties.example`, et mettre à jour le README.

### 2.3 `JwtProperties` : lire la configuration de façon typée

Le fichier `auth/config/JwtProperties.java` existe déjà mais est vide. Un `record` suffit :

```java
package com.personal.task_tracker.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
		@NotBlank
		String secret,
		@NotBlank
		String issuer,
		@NotNull
		Duration expiration
) {
}
```

Puis l'activer, par exemple sur `TaskTrackerApplication` :

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskTrackerApplication { ... }
```

---

## 3. Les beans `JwtEncoder` et `JwtDecoder`

Nouveau fichier `auth/config/JwtConfig.java` :

```java
package com.personal.task_tracker.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfig {

	private final JwtProperties properties;

	public JwtConfig(JwtProperties properties) {
		this.properties = properties;
	}

	@Bean
	public JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// Checks exp/nbf (by default) and the issuer
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
		return decoder;
	}

	private SecretKey secretKey() {
		byte[] bytes = Base64.getDecoder().decode(properties.secret());
		return new SecretKeySpec(bytes, "HmacSHA256");
	}
}
```

Points à retenir :

- Le **même** `SecretKey` sert à signer et à vérifier (c'est le principe de HS256).
- Le décodeur vérifie automatiquement la signature et l'expiration. On ajoute la vérification de l'émetteur.
- Dès qu'un bean `JwtDecoder` existe, Spring Security sait valider les tokens entrants.

---

## 4. Le end-point de login

### 4.1 Le DTO de réponse

Nouveau fichier `auth/dto/LoginResponseDto.java` :

```java
package com.personal.task_tracker.auth.dto;

public record LoginResponseDto(
		String accessToken,
		String tokenType,
		long expiresIn
) {
}
```

`tokenType` vaut `"Bearer"` et `expiresIn` est en secondes : c'est la forme conventionnelle (OAuth2) que les clients
savent lire.

`LoginRequestDto` existe déjà. Remarque : `@NonNull` (jspecify) n'est **pas** une contrainte de validation, elle
n'est pas vérifiée par `@Valid`. Utiliser `@NotBlank` (jakarta.validation) pour obtenir un 400 si un champ manque.

### 4.2 Retrouver l'utilisateur par email

`UserRepository` n'a pas encore de recherche par email :

```java
Optional<AppUser> findByEmail(String email);
```

### 4.3 Vérifier les identifiants

Dans `UserService`, remplacer la méthode `login` vide :

```java
/**
 * Check the credentials
 *
 * @param email       the email, normalized like at registration
 * @param rawPassword the password sent by the client
 * @return the authenticated user
 * @throws BadCredentialsException if the email is unknown or the password is wrong
 */
public AppUser authenticate(String email, String rawPassword) {
	String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
	return repository.findByEmail(normalizedEmail)
			.filter(user -> passwordEncoder.matches(rawPassword, user.getPassword()))
			.orElseThrow(() -> new BadCredentialsException("Bad credentials"));
}
```

Deux règles de sécurité :

- **Même erreur** que l'email soit inconnu ou le mot de passe faux. Sinon un attaquant peut tester quels emails
  existent (énumération d'utilisateurs).
- Normaliser l'email **exactement** comme dans `createUser` (`trim` + `toLowerCase(Locale.ROOT)`), sinon un
  utilisateur inscrit avec `JDoe@Mail.com` ne pourra jamais se connecter.

`BadCredentialsException` est une `AuthenticationException` : le `GlobalExceptionHandler` la transforme déjà en
401 `request.unauthorized`. Rien à ajouter côté erreurs.

### 4.4 Générer le token

Nouveau fichier `auth/service/TokenService.java` :

```java
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
```

Pourquoi `sub` = id et pas email : l'id ne change jamais, l'email peut être modifié par l'utilisateur.

### 4.5 Brancher `AuthService` et `AuthController`

`AuthService` (remplacer le bloc commenté) :

```java
/**
 * Check credentials and create the token
 *
 * @param userDto credentials
 * @return the token and its lifetime
 */
public LoginResponseDto login(LoginRequestDto userDto) {
	AppUser user = userService.authenticate(userDto.email(), userDto.password());
	return new LoginResponseDto(
			tokenService.generateToken(user),
			"Bearer",
			jwtProperties.expiration().toSeconds()
	);
}
```

(injecter `TokenService` et `JwtProperties` dans le constructeur.)

`AuthController` (remplacer le bloc commenté) :

```java
@PostMapping("/login")
public ResponseEntity<LoginResponseDto> login(
		@RequestBody @Valid LoginRequestDto userDto
) {
	LoginResponseDto loginResponseDto = service.login(userDto);
	return new ResponseEntity<>(loginResponseDto, HttpStatus.OK);
}
```

---

## 5. Protéger les end-points : `SecurityConfig`

C'est ici que tout se joue. Version cible de `commons/config/SecurityConfig.java` :

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandler problemHandler) {
	http.authorizeHttpRequests(auth -> auth
					.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
					.anyRequest().authenticated())
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.oauth2ResourceServer(oauth2 -> oauth2
					.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
					.authenticationEntryPoint(problemHandler)
					.accessDeniedHandler(problemHandler))
			.exceptionHandling(exceptions -> exceptions
					.authenticationEntryPoint(problemHandler)
					.accessDeniedHandler(problemHandler));
	return http.build();
}

/**
 * Turns the "roles" claim into ROLE_xxx authorities, so that hasRole("ADMIN") works
 */
private JwtAuthenticationConverter jwtAuthenticationConverter() {
	JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
	authorities.setAuthoritiesClaimName("roles");
	authorities.setAuthorityPrefix("ROLE_");

	JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
	converter.setJwtGrantedAuthoritiesConverter(authorities);
	return converter;
}
```

Ligne par ligne :

| Élément | Rôle |
|---|---|
| `requestMatchers(...).permitAll()` | Register et login restent publics (sinon impossible d'obtenir un token) |
| `anyRequest().authenticated()` | Tout le reste exige un token valide |
| `csrf.disable` | Le CSRF protège les cookies de session ; avec un Bearer token il est inutile |
| `STATELESS` | Spring ne crée jamais de `HttpSession` |
| `oauth2ResourceServer.jwt(...)` | Active le filtre Bearer + le `JwtDecoder` de l'étape 3 |
| `oauth2.authenticationEntryPoint(problemHandler)` | **Important** : sans ça, un token invalide renvoie un 401 vide (format Spring par défaut) au lieu de votre `ProblemDetail` |
| `jwtAuthenticationConverter` | Par défaut Spring lit le claim `scope` et préfixe `SCOPE_`. On lui dit de lire `roles` et de préfixer `ROLE_` |

### Restreindre par rôle

Deux façons, au choix :

**a) Dans `SecurityConfig`** (règles centralisées, l'ordre compte : la première règle qui correspond gagne) :

```java
.requestMatchers("/api/users/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

**b) Sur les méthodes** (règle à côté du code) : ajouter `@EnableMethodSecurity` sur `SecurityConfig`, puis :

```java
@PreAuthorize("hasRole('ADMIN')")
@GetMapping
public ResponseEntity<List<UserResponseDto>> getAllUsers() { ... }
```

`hasRole('ADMIN')` cherche l'autorité `ROLE_ADMIN`, d'où le préfixe configuré plus haut.

---

## 6. Utiliser l'utilisateur connecté dans un contrôleur

Le token décodé est injectable directement :

```java
@GetMapping("/me")
public ResponseEntity<UserResponseDto> me(@AuthenticationPrincipal Jwt jwt) {
	Long userId = Long.valueOf(jwt.getSubject());
	return new ResponseEntity<>(service.getUserById(userId), HttpStatus.OK);
}
```

Utile plus tard pour, par exemple, attribuer une tâche créée à son auteur, ou vérifier qu'un utilisateur ne modifie
que ses propres tâches. (Ce sont des évolutions de story, pas à faire en même temps que le JWT.)

---

## 7. Tester à la main

Remplacer `<port>`, `<email>`, `<mot-de-passe>` et `<token>` par tes valeurs.

```bash
# 1. S'inscrire
curl -i -X POST localhost:<port>/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"firstname":"John","lastname":"Doe","username":"jdoe","email":"<email>","password":"<mot-de-passe>"}'

# 2. Se connecter et récupérer le token
curl -s -X POST localhost:<port>/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<email>","password":"<mot-de-passe>"}'

# 3. Appeler un end-point protégé
curl -i localhost:<port>/api/tasks -H "Authorization: Bearer <token>"

# 4. Sans token : 401 attendu
curl -i localhost:<port>/api/tasks
```

Pour lire le contenu d'un token : décoder en Base64 la partie du milieu (le payload). Éviter de coller un vrai token
dans un site en ligne.

---

## 8. Les tests automatisés

### 8.1 Fournir la clé aux tests

Le contexte de test doit avoir une clé, sinon il ne démarre pas. Plutôt que d'écrire une clé en dur, la générer
au démarrage du contexte, dans `IntegrationTest` (c'est l'endroit unique prévu pour ce qui modifie le contexte) :

```java
/**
 * Random key for the whole test context: no key is written in the code
 */
@DynamicPropertySource
static void jwtProperties(DynamicPropertyRegistry registry) {
	byte[] key = new byte[32];
	new SecureRandom().nextBytes(key);
	String encodedKey = Base64.getEncoder().encodeToString(key);
	registry.add("app.jwt.secret", () -> encodedKey);
}
```

Déclarée dans la classe mère, elle est identique pour toutes les classes de test : le contexte Spring reste partagé.

### 8.2 Les tests existants vont casser

Dès que `anyRequest().authenticated()` est actif, `TaskControllerTest` et `UserControllerTest` reçoivent des 401.
Il faut leur fournir un token. Le plus simple avec RestAssured : un helper dans `IntegrationTest` qui génère un
token via `TokenService` pour un utilisateur créé en base, puis l'ajoute en en-tête `Authorization: Bearer ...`
de chaque requête.

`SecurityErrorsTest` dit aussi en commentaire que la sécurité est en `permitAll` : il faudra le relire.

### 8.3 Cas à couvrir

| Cas | Attendu |
|---|---|
| Login avec bons identifiants | 200, `accessToken` non vide, `tokenType` = `Bearer`, `expiresIn` = 3600 |
| Login, email inconnu | 401 `request.unauthorized` |
| Login, mauvais mot de passe | 401, **même corps** que l'email inconnu |
| Login, email avec majuscules / espaces | 200 |
| Login, champ manquant | 400 |
| Appel protégé sans token | 401 `ProblemDetail` |
| Appel protégé avec token falsifié (signature modifiée) | 401 `ProblemDetail` |
| Appel protégé avec token expiré | 401 `ProblemDetail` |
| Appel protégé avec token valide | 200 |
| Register / login sans token | accessibles |
| (si règles de rôle) USER sur une route ADMIN | 403 `ProblemDetail` |

**Token expiré sans dépendre du temps** (règle : aucun test ne dépend du temps ni d'un `sleep`) : construire le token
directement avec le `JwtEncoder`, avec `issuedAt` et `expiresAt` dans le passé (par exemple il y a 2 h et il y a
1 h). Le décodeur tolère 60 s de décalage d'horloge par défaut, donc prendre une marge large.

---

## 9. Pièges fréquents

| Symptôme | Cause probable |
|---|---|
| `Failed to select a JWK signing key` à l'encodage | `JwsHeader` absent : l'encodeur tente RS256 |
| L'application ne démarre pas, `Could not resolve placeholder 'JWT_SECRET'` | Variable d'environnement non définie |
| `The secret length must be at least 256 bits` | Clé trop courte, regénérer avec `openssl rand -base64 32` |
| 401 au corps vide au lieu du `ProblemDetail` | `authenticationEntryPoint` non déclaré dans `oauth2ResourceServer` |
| 403 alors que l'utilisateur est ADMIN | Préfixe `ROLE_` manquant, ou claim `roles` non mappé |
| Login toujours en 401 | Email non normalisé comme à l'inscription |

---

## 10. Décisions qui te reviennent avant de coder

Ce guide propose des valeurs par défaut, mais ces points relèvent de la story et sont à trancher :

1. **Quelles routes sont publiques** ? Le guide ne laisse ouverts que register et login.
2. **Règles par rôle** : `/api/users` réservé aux ADMIN ? Que peut faire un GUEST ?
3. **Durée de vie du token** : 1 h proposée.
4. **Refresh token** : non couvert ici. Sans lui, l'utilisateur se reconnecte à l'expiration. C'est une story à part.
5. **Déconnexion** : un JWT ne peut pas être révoqué côté serveur sans liste noire. Avec une durée courte, la
   déconnexion consiste simplement à ce que le client oublie le token.

---

## Récapitulatif des fichiers

| Fichier | Action |
|---|---|
| `application.properties` | Ajouter `app.jwt.*` |
| `TaskTrackerApplication.java` | `@ConfigurationPropertiesScan` |
| `auth/config/JwtProperties.java` | Remplir le record |
| `auth/config/JwtConfig.java` | Créer : `JwtEncoder` + `JwtDecoder` |
| `auth/service/TokenService.java` | Créer : génération du token |
| `auth/dto/LoginRequestDto.java` | `@NonNull` vers `@NotBlank` |
| `auth/dto/LoginResponseDto.java` | Créer |
| `user/repository/UserRepository.java` | `findByEmail` |
| `user/service/UserService.java` | `authenticate` à la place de `login` vide |
| `auth/service/AuthService.java` | `login` |
| `auth/controller/AuthController.java` | `POST /login` |
| `commons/config/SecurityConfig.java` | Resource server, stateless, règles d'accès |
| `IntegrationTest.java` + tests | Clé générée, helper token, nouveaux tests |
