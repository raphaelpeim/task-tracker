# 14. JWT : ajouter l'authentification au task-tracker

Ce guide part de l'état actuel du projet (Spring Boot 4.1.1, Spring Security 7, Java 25) et explique pas à pas
comment émettre un JWT au login, puis l'exiger sur les end-points. Chaque étape cite les fichiers concernés.

Ce guide ne suit pas le plan des autres fiches. Sa partie "Sous le capot" (ce qui se passe réellement derrière
les annotations et le starter, pourquoi chaque pièce est nécessaire, et de quoi l'observer) est donc placée à la
fin, en [section 11](#11-sous-le-capot), pour ne pas décaler la numérotation à laquelle renvoient les autres
fiches. Lis-la après les étapes 2 et 3, déjà présentes dans le projet.

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
// header (celui que produit le JwtEncoder du projet ; d'autres émetteurs ajoutent "typ": "JWT")
{ "alg": "HS256" }

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

- `PT1H` est une `Duration` ISO-8601 (1 heure). Spring la convertit toute seule (la forme courte `1h` est acceptée
  aussi, vérifié).
- Si `JWT_SECRET` n'est pas définie, l'application refuse de démarrer : c'est voulu.

État actuel du projet : `application.properties` contient `app.jwt.issuer=task-tracker` et
`app.jwt.expiration=PT1H`, mais **pas** de ligne `app.jwt.secret` ; la clé doit donc être fournie à part. Si elle
manque, le démarrage échoue sur la validation de `JwtProperties` (message réel en 11.3).

En local, exporter la variable avant de lancer l'application (ou la déclarer dans la configuration de lancement de
l'IDE). Si tu préfères la passer par `application-local.properties`, documenter la clé `app.jwt.secret=`
**sans valeur** dans `application-local.properties.example`, et mettre à jour le README.

### 2.3 `JwtProperties` : lire la configuration de façon typée

Le fichier `auth/config/JwtProperties.java` contient aujourd'hui ce `record` (commit `feat(jwt): WIP: configuration`) :

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

Il est activé sur `TaskTrackerApplication` (ce que fait chaque annotation : section 11.3) :

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskTrackerApplication { ... }
```

---

## 3. Les beans `JwtEncoder` et `JwtDecoder`

Fichier `auth/config/JwtConfig.java`, déjà présent dans le projet :

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
| `The secret length must be at least 256 bits`, au premier login et pas au démarrage | Clé trop courte, regénérer avec `openssl rand -base64 32` (11.2) |
| L'application ne démarre pas, `Illegal base64 character 2d` (ou un autre code) | La clé n'est pas du base64 standard : ici `2d` est le code hexadécimal du caractère `-` (11.2) |
| L'application ne démarre pas, `Binding to target ...JwtProperties failed`, `Property: app.jwt.secret` | Propriété absente : c'est `@Validated` qui bloque (11.3) |
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

## 11. Sous le capot

Les sections 1 à 10 disent **quoi** écrire. Celle-ci montre ce que font réellement les pièces, pourquoi chacune est
nécessaire, ce qui se passe si on la retire, et comment l'observer. Même plan que la section 4 de
[02. Beans et injection](02-beans-injection.md) et de [10. Sécurité](10-securite.md) : le mécanisme, l'observer,
la source. Le fonctionnement de la chaîne de filtres elle-même (`DelegatingFilterProxy`, `FilterChainProxy`,
`ExceptionTranslationFilter`, `SecurityContextHolder`) est expliqué dans la fiche 10, section 4 : il n'est pas
répété ici.

**Ce qui a été observé, et comment.** Tout ce qui concerne `JwtProperties`, `JwtConfig`, l'encodeur et le décodeur
a été relevé sur le code actuel du projet, dans un test d'intégration temporaire (clé aléatoire générée pour
l'occasion, puis jetée). Les sections 4 à 8 du guide ne sont pas encore codées : pour 11.4, le `SecurityConfig` a
été modifié **temporairement** (ajout de `oauth2ResourceServer(o -> o.jwt(withDefaults()))` et `authenticated()`),
puis remis en place. C'est signalé à chaque fois.

### 11.1 Un JWT, morceau par morceau

**Le mécanisme.** Un JWT signé (format JWS compact) est la concaténation de trois morceaux séparés par des points :

```
BASE64URL(header) . BASE64URL(payload) . BASE64URL(signature)
```

- le **header** et le **payload** sont du JSON, simplement encodés : n'importe qui peut les lire ;
- la **signature** est calculée sur les deux premiers morceaux **tels qu'encodés** (la chaîne `header.payload`) ;
- **Base64URL** est la variante "sûre pour les URL" de base64 : `-` et `_` à la place de `+` et `/`, et pas de
  `=` final. Un token peut ainsi voyager dans un en-tête HTTP ou une URL sans être échappé.

**L'observer.** Un token produit par le `JwtEncoder` du projet (avec une clé de test jetée depuis) a cette forme :
trois morceaux de 20, 103 et 43 caractères. Décodés avec `Base64.getUrlDecoder()` :

```
header    {"alg":"HS256"}
payload   {"iss":"probe","sub":"42","exp":1793285325,"iat":1793281725,"roles":["USER"]}
signature 32 octets (256 bits : la taille d'un HMAC-SHA256)
```

Chaque morceau JSON encodé commence par `eyJ` : c'est l'encodage de `{"`. Tu reconnaîtras désormais un JWT au
premier coup d'oeil. Pour décoder toi-même le payload en ligne de commande, sans site en ligne :
`echo '<partie du milieu>' | tr '_-' '/+' | base64 -d` (ajoute un ou deux `=` à la fin si `base64` se plaint du
padding).

**La source.** [RFC 7519 (JWT)](https://www.rfc-editor.org/rfc/rfc7519.html),
[RFC 7515 (JWS), section 7.1 "JWS Compact Serialization"](https://www.rfc-editor.org/rfc/rfc7515.html#section-7.1),
[RFC 4648, section 5 (Base64URL)](https://www.rfc-editor.org/rfc/rfc4648.html#section-5).

### 11.2 La signature HMAC, et pourquoi la clé est en base64 et fait au moins 32 octets

**Le mécanisme.** HS256 = HMAC avec SHA-256. Le serveur calcule `HMAC-SHA256(clé, "header.payload")` et place le
résultat en troisième morceau. Pour vérifier, il **refait le même calcul** et compare. Sans la clé, impossible de
produire une signature valide ; et modifier un seul caractère du payload change complètement le résultat. Ce
n'est pas du chiffrement : le contenu reste lisible, il est seulement **infalsifiable**.

C'est tout ce que fait `JwtConfig` : une seule `SecretKey`, donnée à l'encodeur (qui signe) et au décodeur (qui
vérifie).

**Pourquoi la clé est en base64.** Une clé HMAC est une suite d'**octets** aléatoires, pas du texte : beaucoup de
ces octets ne sont pas des caractères imprimables. Pour la ranger dans une propriété (du texte), on l'encode en
base64 ; `JwtConfig.secretKey()` (ligne 42) fait le chemin inverse avec `Base64.getDecoder().decode(...)`.
`openssl rand -base64 32` produit exactement ce format : 32 octets aléatoires, encodés.

Ce décodeur est le base64 **standard** (`+`, `/`, `=`), pas le Base64URL des tokens. Vérifié sur le projet avec
la valeur `abc-def` pour `app.jwt.secret` : le contexte ne démarre pas.

```
Error creating bean with name 'jwtEncoder' defined in class path resource
[com/personal/task_tracker/auth/config/JwtConfig.class]: Failed to instantiate
[org.springframework.security.oauth2.jwt.JwtEncoder]: Factory method 'jwtEncoder' threw exception with message:
Illegal base64 character 2d
```

`2d` est le code hexadécimal du caractère `-`, interdit en base64 standard. L'erreur survient au **démarrage**,
parce que `secretKey()` est appelée par la méthode `@Bean jwtEncoder()`.

**Pourquoi 32 octets minimum.** La RFC 7518 impose, pour HS256, une clé au moins aussi longue que la sortie du
hash : 256 bits. La bibliothèque Nimbus (utilisée par Spring) refuse les clés plus courtes. Mais attention au
**moment** où elle le fait. Vérifié avec une valeur valide en base64 mais de 16 octets seulement :

- l'application **démarre** normalement : construire l'encodeur et le décodeur ne vérifie pas la longueur ;
- le premier `encode` échoue :
  `JwtEncodingException: An error occurred while attempting to encode the Jwt: Failed to create a JWS Signer -> The secret length must be at least 256 bits` ;
- le premier `decode` aussi :
  `JwtException: An error occurred while attempting to decode the Jwt: The secret length must be at least 256 bits`.

Autrement dit, une clé trop courte passe tous les contrôles de démarrage et ne casse qu'au premier login. Une
contrainte de taille sur `JwtProperties` (un validateur maison, comme `@MaxBytes` dans la fiche 06) la ferait
échouer dès le démarrage ; c'est une décision à prendre, pas une obligation.

**Ce que le décodeur refuse d'autre** (vérifié, avec les messages réels) :

| Token envoyé | Résultat de `jwtDecoder.decode(...)` |
|---|---|
| Payload modifié (`"USER"` remplacé par `"ADMIN"`), signature d'origine | `BadJwtException: ... Signed JWT rejected: Invalid signature` |
| Header remplacé par `{"alg":"none"}` et signature vide | `BadJwtException: Unsupported algorithm of none` |
| `abc` (pas de points) | `BadJwtException: ... Malformed token` (cause : `Invalid JWT serialization: Missing dot delimiter(s)`) |

La ligne `alg: none` est une attaque historique : certaines bibliothèques acceptaient un token "non signé" si le
header le demandait. Ici, `macAlgorithm(MacAlgorithm.HS256)` (ligne 34 de `JwtConfig`) fixe l'algorithme attendu ;
le header du token n'a pas son mot à dire.

Enfin, le piège de la section 4.4 (`JwsHeader` obligatoire) se vérifie : sans header explicite, l'encodeur échoue
avec `JwtEncodingException: An error occurred while attempting to encode the Jwt: Failed to select a JWK signing key`.

**Refais-le toi-même.** Le test temporaire a recalculé la signature **sans** Spring, en quelques lignes, et obtenu
exactement le troisième morceau du token :

```java
String[] parts = token.split("\\.");
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(Base64.getDecoder().decode(jwtProperties.secret()), "HmacSHA256"));
byte[] signature = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
String recomputed = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
// recomputed.equals(parts[2]) == true
```

C'est tout HS256. Le reste de Nimbus gère les autres algorithmes, les formats de clés et les cas d'erreur.

**La source.** [RFC 7518 (JWA), section 3.2 "HMAC with SHA-2 Functions"](https://www.rfc-editor.org/rfc/rfc7518.html#section-3.2),
[RFC 7515, section 5 "Producing and Consuming JWSs"](https://www.rfc-editor.org/rfc/rfc7515.html#section-5).

### 11.3 `JwtProperties` : ce que font les trois annotations

**Le mécanisme.**

| Annotation | Où | Ce qu'elle déclenche |
|---|---|---|
| `@ConfigurationProperties(prefix = "app.jwt")` | `JwtProperties` | Marque la classe comme "à remplir depuis la configuration". Seule, elle ne crée **rien** |
| `@ConfigurationPropertiesScan` | `TaskTrackerApplication` (ligne 8) | Scanne le package de l'application à la recherche de classes `@ConfigurationProperties` et enregistre un bean pour chacune |
| `@Validated` | `JwtProperties` | Après le remplissage, passe l'objet au validateur Jakarta Validation (`@NotBlank`, `@NotNull`) ; une violation **arrête le démarrage** |

Le remplissage (le *binding*) cherche, pour chaque composant du record, une propriété `app.jwt.<nom>` dans toutes
les sources de configuration (fichiers `.properties`, variables d'environnement, arguments `-D...`), en tolérant
plusieurs écritures (par exemple la variable d'environnement `APP_JWT_SECRET` pour `app.jwt.secret`). Il convertit
aussi le texte vers le type Java : c'est ainsi que `PT1H` ou `1h` deviennent une `Duration`. Pour un record, Boot
appelle directement le constructeur avec les valeurs trouvées : l'objet est immuable.

**L'observer.**

- *Le bean créé.* Son nom réel, lu dans le contexte : `app.jwt-com.personal.task_tracker.auth.config.JwtProperties`
  (préfixe + nom complet de la classe, la convention de Boot pour ces beans). Valeurs relues sans surcharge :
  `issuer=task-tracker`, `expiration=PT1H`.
- *Sans valeur pour le secret*, sortie réelle :
  ```
  ***************************
  APPLICATION FAILED TO START
  ***************************

  Description:

  Binding to target com.personal.task_tracker.auth.config.JwtProperties failed:

      Property: app.jwt.secret
      Value: "null"
      Reason: ne doit pas être vide

  Action:

  Update your application's configuration
  ```
  `ne doit pas être vide` est le message par défaut de `@NotBlank`, en français parce que la JVM tourne avec une
  locale française.

**Sans chaque annotation** (expériences faites sur le projet, code remis en place ensuite) :

| On retire... | Ce qui se passe | Pourquoi |
|---|---|---|
| `@Validated` (et pas de secret) | Le démarrage échoue quand même, mais plus loin et moins clairement : `Factory method 'jwtEncoder' threw exception with message: Cannot invoke "String.getBytes(java.nio.charset.Charset)" because "src" is null` | Le record est créé avec un secret `null`, et c'est `Base64.getDecoder().decode(null)` dans `JwtConfig` qui explose. Il faut lire la pile pour comprendre ; avec `@Validated`, le message nomme la propriété fautive |
| `@ConfigurationPropertiesScan` | `Parameter 0 of constructor in com.personal.task_tracker.auth.config.JwtConfig required a bean of type 'com.personal.task_tracker.auth.config.JwtProperties' that could not be found.` | `@ConfigurationProperties` seul n'enregistre aucun bean, et `JwtProperties` n'est pas un `@Component` : le scan ordinaire ne le voit pas |

Une erreur de configuration doit apparaître **au démarrage**, avec un message qui nomme la propriété : c'est
exactement ce qu'apporte `@Validated`. Une application qui démarre avec une configuration incomplète échoue plus
tard, chez l'utilisateur.

**La source.** [Externalized Configuration, "Type-safe Configuration Properties"](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties),
en particulier
["@ConfigurationProperties Validation"](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation).
Le fonctionnement général des beans et du scan : [02. Beans et injection](02-beans-injection.md), section 4.1.

### 11.4 Ce que le starter resource-server ajoute (ou non)

**Le mécanisme.** `spring-boot-starter-security-oauth2-resource-server` apporte deux choses distinctes :

1. **Des classes** (Spring Security OAuth2 Resource Server, Spring Security JOSE, Nimbus) : `NimbusJwtEncoder`,
   `NimbusJwtDecoder`, `BearerTokenAuthenticationFilter`, `JwtAuthenticationProvider`, `JwtAuthenticationConverter`...
2. **Des auto-configurations** (module `spring-boot-security-oauth2-resource-server`), qui ne s'appliquent que sous
   conditions (principe : [02. Beans et injection](02-beans-injection.md), section 4.6). Celles qui comptent ici,
   avec leur décision réelle sur le projet (rapport des conditions) :

| Auto-configuration | Ce qu'elle ferait | Décision sur le projet |
|---|---|---|
| `JwtDecoderConfiguration` | Créer un `JwtDecoder` à partir des propriétés `spring.security.oauth2.resourceserver.jwt.*` (URL de clés, clé publique...) | **Écartée** : `@ConditionalOnMissingBean (types: org.springframework.security.oauth2.jwt.JwtDecoder; ...) found beans of type ...`. Ton `jwtDecoder` la remplace |
| `JwtConverterConfiguration` | Créer un `JwtAuthenticationConverter` configuré par propriétés | **Écartée** : aucune des propriétés `authority-prefix`, `authorities-claim-name`, `principal-claim-name`, `authorities-claim-expressions` n'est définie |
| `OAuth2ResourceServerWebSecurityAutoConfiguration` | Créer une `SecurityFilterChain` JWT par défaut | **Écartée** tant que ton `filterChain` existe (détail et expérience : fiche 10, section 4.6) |

Conséquence importante : **aujourd'hui, aucun filtre ne lit l'en-tête `Authorization`**. La chaîne réelle du
projet (fiche 10, 4.2) ne contient pas de `BearerTokenAuthenticationFilter`, et un `Bearer abc` est ignoré
(vérifié). Le filtre n'est ajouté que par la ligne `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` de la
section 5. Avoir un bean `JwtDecoder` ne suffit pas quand tu déclares ta propre chaîne : c'est la chaîne qui
décide quels filtres existent.

**Ce que fait le filtre, une fois branché.** Pour chaque requête :

1. `BearerTokenAuthenticationFilter` cherche `Authorization: Bearer <token>`. Pas d'en-tête : il laisse passer sans
   rien faire (la requête reste anonyme, et c'est l'`AuthorizationFilter` qui refusera plus loin).
2. Il confie le token au `JwtAuthenticationProvider`, qui appelle **ton** bean `jwtDecoder` (signature, puis
   validateurs de 11.5).
3. Le `Jwt` obtenu passe dans un `JwtAuthenticationConverter`, qui fabrique un `JwtAuthenticationToken` avec des
   autorités.
4. Ce token est rangé dans le `SecurityContextHolder` (fiche 10, 4.4), où le contrôleur et `@PreAuthorize` le
   trouveront.
5. En cas d'échec à l'étape 2, le filtre appelle **son propre** entry point, celui du bloc
   `oauth2ResourceServer(...)`, pas celui d'`exceptionHandling(...)`.

**L'observer.** `SecurityConfig` modifié temporairement : `authenticated()` et
`oauth2ResourceServer(o -> o.jwt(withDefaults()))`, **sans** `authenticationEntryPoint` dans ce bloc (le bloc
`exceptionHandling` du projet, lui, reste en place). Un contrôleur de test renvoyait le contenu du
`SecurityContextHolder`.

- La chaîne passe de 10 à 12 filtres :
  `..., LogoutFilter, OAuth2ProtectedResourceMetadataFilter, BearerTokenAuthenticationFilter, RequestCacheAwareFilter, ...`
- Quatre requêtes, réponses réelles :

  | Requête | Statut | Corps | `WWW-Authenticate` |
  |---|---|---|---|
  | Sans en-tête | 401 | Le `ProblemDetail` du projet (`"code":"request.unauthorized"`) | absent |
  | `Bearer abc` | 401 | **vide** | `Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token", ...` |
  | Token à la signature modifiée | 401 | **vide** | `Bearer error="invalid_token", error_description="... Signed JWT rejected: Invalid signature", ...` |
  | Token valide, claims `roles: ["ADMIN"]` et `scope: "tasks:read"` | 200 | voir ci-dessous | absent |

  Les lignes 2 et 3 confirment le piège des sections 5 et 9 : sans `oauth2.authenticationEntryPoint(problemHandler)`,
  un token invalide donne un 401 au **corps vide**. La ligne 1 montre que le cas "pas de token" passe, lui, par
  l'`exceptionHandling` (fiche 10, 4.3).
- Ce que le contrôleur a lu dans le `SecurityContextHolder`, pour le token valide :
  ```
  thread=http-nio-auto-1-exec-3
  auth=org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
  name=42
  authorities=[SCOPE_tasks:read, FactorGrantedAuthority [authority=FACTOR_BEARER, issuedAt=...]]
  ```
  Trois enseignements :
  - `name=42` : par défaut, le nom de l'utilisateur est le claim `sub` ;
  - le claim `roles` a été **ignoré** : par défaut, le convertisseur ne lit que `scope` (ou `scp`) et préfixe
    `SCOPE_`. D'où le `jwtAuthenticationConverter()` de la section 5, sans lequel `hasRole("ADMIN")` ne trouverait
    pas `ROLE_ADMIN`. Alternative sans code : les propriétés
    `spring.security.oauth2.resourceserver.jwt.authorities-claim-name=roles` et `...authority-prefix=ROLE_`,
    qui réveillent `JwtConverterConfiguration` (tableau ci-dessus) ; lu dans les sources, non testé sur le projet ;
  - `FACTOR_BEARER` est une autorité ajoutée par Spring Security 7, qui note **comment** l'utilisateur s'est
    authentifié (ici par un bearer token). Ne t'étonne pas de la voir dans les logs.
- Le thread `main` du test, lui, lisait `getAuthentication() = null` au même moment : c'est le `ThreadLocal`
  expliqué dans la fiche 10, 4.4.
- Le log `DEBUG` du succès :
  `BearerTokenAuthenticationFilter : Set SecurityContextHolder to JwtAuthenticationToken [Principal=...JwtAuthenticationConverter$JwtAuthenticatedPrincipal@..., Credentials=[PROTECTED], Authenticated=true, ...]`.

**La source.** [OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
(sections "How JWT Authentication Works", "Configuring Authorization", "Extracting Authorities Manually"),
[RFC 6750 (Bearer Token), section 3 "The WWW-Authenticate Response Header Field"](https://www.rfc-editor.org/rfc/rfc6750.html#section-3).

### 11.5 Où l'expiration et l'émetteur sont vérifiés

**Le mécanisme.** `NimbusJwtDecoder.decode(token)` travaille en deux temps, et le type d'exception dit lequel a
échoué :

1. **Forme et signature** (Nimbus) : découpage en trois morceaux, algorithme attendu, recalcul HMAC. Échec :
   `BadJwtException` (tableau de 11.2).
2. **Contenu** (validateurs Spring) : `JwtConfig` (ligne 37) installe
   `JwtValidators.createDefaultWithIssuer(properties.issuer())`. Dans Spring Security 7.1.1, cette méthode combine
   (lu dans les sources) :
   - `JwtTimestampValidator` : `exp` (expiration) et `nbf` (pas avant), avec une **tolérance de 60 secondes** ;
   - `JwtIssuerValidator` : `iss` doit être égal à `app.jwt.issuer` ;
   - `JwtTypeValidator` : le header `typ`, s'il est présent, doit valoir `JWT` (absent accepté, d'où notre header
     sans `typ`) ;
   - `X509CertificateThumbprintValidator`, qui ne concerne pas les tokens HMAC.

   Échec : `JwtValidationException`.

Pourquoi vérifier l'émetteur ? Si un autre service utilisait la même clé (mauvaise idée, mais ça arrive), ses
tokens seraient acceptés par le projet. Le claim `iss` les distingue.

**L'observer.** Tokens fabriqués avec le `JwtEncoder` du projet, puis donnés au `jwtDecoder` (aucune attente,
aucun `sleep` : les dates sont choisies dans le passé, comme le recommande la section 8.3) :

| Token | Résultat réel |
|---|---|
| Expiré depuis 1 h | `JwtValidationException: An error occurred while attempting to decode the Jwt: Jwt expired at 2026-10-29T12:48:45Z` |
| Expiré depuis **30 s** | **Accepté** : la tolérance de 60 s s'applique |
| `iss` = `someone-else` | `JwtValidationException: An error occurred while attempting to decode the Jwt: The iss claim is not valid` |
| Valide | `Jwt` avec `claims={iss=probe, sub=42, exp=2026-10-29T14:48:45Z, iat=2026-10-29T13:48:45Z, roles=[USER]}` |

La ligne "30 s" justifie la consigne de la section 8.3 (prendre une marge large pour un test de token expiré) : un
test qui fabriquerait un token expiré depuis quelques secondes échouerait, le token étant encore accepté.

**Sans le validateur.** Si `JwtConfig` n'appelait pas `setJwtValidator(...)`, le décodeur garderait ses
validateurs par défaut (expiration comprise) mais **n'examinerait plus l'émetteur** (déduit de la documentation
et des sources, non testé).

**La source.** [OAuth 2.0 Resource Server JWT, "Configuring Validation"](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[RFC 7519, section 4.1 "Registered Claim Names"](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1)
(`iss`, `sub`, `exp`, `nbf`, `iat`).

### 11.6 Récapitulatif : retire une pièce, observe la panne

Toutes les lignes ont été observées sur le projet (sauf mention), puis le code a été remis en place.

| On retire ou on casse... | Moment | Symptôme réel | Section |
|---|---|---|---|
| La valeur de `app.jwt.secret` | Démarrage | `Binding to target ...JwtProperties failed`, `Property: app.jwt.secret` | 11.3 |
| `@Validated` (et la valeur du secret) | Démarrage | `NullPointerException` dans `JwtConfig.secretKey()`, cause à chercher dans la pile | 11.3 |
| `@ConfigurationPropertiesScan` | Démarrage | `JwtConfig required a bean of type 'JwtProperties' that could not be found` | 11.3 |
| Le format base64 de la clé | Démarrage | `Illegal base64 character 2d` | 11.2 |
| La longueur de la clé (16 octets) | **Premier login** | `The secret length must be at least 256 bits` | 11.2 |
| Le `JwsHeader` à l'encodage | Premier login | `Failed to select a JWK signing key` | 11.2 |
| `oauth2ResourceServer(...)` dans la chaîne | Jamais : aucune erreur | Les tokens sont ignorés, tout le monde reste anonyme | 11.4 |
| L'entry point du bloc `oauth2ResourceServer` | Requête avec token invalide | 401 au corps vide | 11.4 |
| Le convertisseur `roles` → `ROLE_` | Requête d'un ADMIN | Autorités `SCOPE_...` seulement, pas de `ROLE_ADMIN` (autorités observées, refus de `hasRole` non testé) | 11.4 |

La ligne la plus dangereuse est celle qui ne produit **aucune** erreur : une chaîne sans `oauth2ResourceServer`
démarre, répond, et ne vérifie aucun token. Seuls les tests de la section 8.3 (appel protégé sans token → 401) la
détectent.

---

## Récapitulatif des fichiers

| Fichier | Action |
|---|---|
| `application.properties` | Ajouter `app.jwt.*` |
| `TaskTrackerApplication.java` | `@ConfigurationPropertiesScan` (fait) |
| `auth/config/JwtProperties.java` | Remplir le record (fait) |
| `auth/config/JwtConfig.java` | Créer : `JwtEncoder` + `JwtDecoder` (fait) |
| `auth/service/TokenService.java` | Créer : génération du token |
| `auth/dto/LoginRequestDto.java` | `@NonNull` vers `@NotBlank` |
| `auth/dto/LoginResponseDto.java` | Créer |
| `user/repository/UserRepository.java` | `findByEmail` |
| `user/service/UserService.java` | `authenticate` à la place de `login` vide |
| `auth/service/AuthService.java` | `login` |
| `auth/controller/AuthController.java` | `POST /login` |
| `commons/config/SecurityConfig.java` | Resource server, stateless, règles d'accès |
| `IntegrationTest.java` + tests | Clé générée, helper token, nouveaux tests |
