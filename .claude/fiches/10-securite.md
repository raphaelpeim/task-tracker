# 10. Sécurité

**Ce que tu vas comprendre**

- Comment Spring Security s'intercale **avant** tes contrôleurs, sous forme de chaîne de filtres
- La différence entre authentification (401) et autorisation (403)
- Pourquoi on ne stocke jamais un mot de passe, mais son hash BCrypt
- Ce que le projet protège déjà aujourd'hui, alors que tous les end-points sont ouverts
- Les attaques classiques que le code évite déjà (attribution de rôle par le client, fuite de mot de passe)

**Prérequis** : [02. Beans et injection](02-beans-injection.md), [05. Web et REST](05-web-rest.md),
[07. Exceptions](07-exceptions.md)

---

## 1. Le problème que ça résout

Imagine un immeuble de bureaux. À l'entrée, un **vigile** vérifie ton badge (qui es-tu ?). Puis, à chaque étage,
un **lecteur de badge** décide si tu as le droit d'entrer (as-tu le droit ?). Les bureaux eux-mêmes (tes
contrôleurs) n'ont pas à se poser la question : si tu es arrivé jusqu'à eux, c'est que tu étais autorisé.

Sans framework de sécurité, il faudrait écrire au début de **chaque** méthode de contrôleur :

```java
// Ce qu'on éviterait d'écrire partout
String header = request.getHeader("Authorization");
if (header == null || !tokenValide(header)) {
	return ResponseEntity.status(401).build();
}
if (!utilisateur.estAdmin()) {
	return ResponseEntity.status(403).build();
}
```

Un oubli sur une seule méthode, et la route est ouverte à tous. Spring Security centralise ces vérifications en
un seul endroit, appliqué à toutes les requêtes.

---

## 2. Les concepts

### 2.1 La chaîne de filtres

Une requête HTTP traverse une série de **filtres** avant d'atteindre le `DispatcherServlet` (le routeur de Spring
MVC, voir fiche 05). Spring Security ajoute ses propres filtres dans cette chaîne :

```
Requête ──► [filtre CORS] ──► [filtre CSRF] ──► [filtre d'authentification] ──► [filtre d'autorisation] ──► Contrôleur
                                    │                       │                            │
                                    ▼                       ▼                            ▼
                                  403                     401                          403
```

Chaque filtre peut **arrêter** la requête et écrire lui-même la réponse. Conséquence importante : une erreur levée
dans un filtre n'arrive jamais au `@RestControllerAdvice` (fiche 07), qui ne connaît que les contrôleurs.

La chaîne se configure avec un bean `SecurityFilterChain`, construit à partir d'un objet `HttpSecurity` :

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) {
	http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/api/public/**").permitAll()   // ouvert à tous
			.anyRequest().authenticated());                  // le reste exige d'être connecté
	return http.build();
}
```

Les règles sont lues **dans l'ordre** : la première qui correspond à l'URL l'emporte. On met donc les règles
précises en premier et `anyRequest()` à la fin.

### 2.2 Authentification et autorisation

| | Authentification | Autorisation |
|---|---|---|
| Question | Qui es-tu ? | As-tu le droit ? |
| Échec | **401 Unauthorized** (le nom est trompeur : il signifie "non authentifié") | **403 Forbidden** |
| Exemple | Pas de token, token expiré | Un USER appelle une route réservée aux ADMIN |
| Côté Spring | `AuthenticationException`, gérée par un `AuthenticationEntryPoint` | `AccessDeniedException`, gérée par un `AccessDeniedHandler` |

Une fois authentifié, l'utilisateur est représenté par un objet `Authentication` rangé dans le
`SecurityContext`. Il porte des **autorités** (`GrantedAuthority`), par exemple `ROLE_ADMIN`.

### 2.3 Rôles et autorités

Un rôle est une autorité dont le nom commence par `ROLE_`. `hasRole("ADMIN")` vérifie donc l'autorité
`ROLE_ADMIN`, alors que `hasAuthority("ADMIN")` vérifierait l'autorité `ADMIN` exacte. Ce préfixe est une source
de confusion fréquente (voir les pièges).

Deux façons de poser une règle :

```java
// a) Centralisée, par URL, dans SecurityConfig
.requestMatchers("/api/users/**").hasRole("ADMIN")

// b) Sur la méthode (nécessite @EnableMethodSecurity sur une classe de configuration)
@PreAuthorize("hasRole('ADMIN')")
public List<UserResponseDto> getAllUsers() { ... }
```

### 2.4 Stocker un mot de passe : le hash

On ne stocke **jamais** un mot de passe tel quel : si la base fuite, tous les comptes sont compromis (et souvent
d'autres sites, car les gens réutilisent leurs mots de passe).

On stocke un **hash** : le résultat d'une fonction à sens unique. On peut calculer le hash d'un mot de passe, mais
pas retrouver le mot de passe à partir du hash. Pour vérifier une connexion, on recalcule et on compare.

BCrypt ajoute deux protections :

- un **sel** aléatoire, différent pour chaque utilisateur et intégré au hash : deux utilisateurs avec le même mot
  de passe ont des hash différents ;
- une fonction **volontairement lente** (paramètre de coût) : tester des milliards de mots de passe devient
  très long pour un attaquant.

```java
String hash = passwordEncoder.encode(motDePasseSaisi);       // à l'inscription
boolean ok = passwordEncoder.matches(motDePasseSaisi, hash);  // à la connexion
```

À cause du sel, encoder deux fois la même valeur donne deux hash différents : il faut **toujours** vérifier avec
`matches`, jamais en comparant deux hash avec `equals`.

Limite à connaître : l'algorithme BCrypt ne prend en compte que les **72 premiers octets** du mot de passe. Deux
mots de passe qui ne diffèrent qu'au-delà seraient donc confondus. Pour éviter ce piège, les versions récentes de
Spring Security refusent d'encoder un mot de passe plus long (exception) au lieu de le tronquer en silence. Dans
les deux cas, il faut valider la longueur **avant**, en entrée de l'API.

### 2.5 CSRF

Le CSRF (Cross-Site Request Forgery) est une attaque où un site malveillant fait envoyer par ton navigateur une
requête vers un site où tu es connecté, en profitant du **cookie de session** que le navigateur joint tout seul.
La protection CSRF de Spring exige un jeton supplémentaire dans les requêtes qui modifient des données.

Elle n'a de sens que si l'authentification repose sur un cookie envoyé automatiquement. Avec un token placé
explicitement dans l'en-tête `Authorization` (cas d'une API REST avec JWT), le navigateur ne l'ajoute pas tout
seul : l'attaque ne fonctionne pas, et on désactive souvent la protection.

### 2.6 Stateless et JWT

Une API **stateless** ne garde aucune session côté serveur : chaque requête apporte la preuve de l'identité, en
général un **JWT** signé. Tout le détail (principe, configuration, end-point de login, tests) est dans le guide
dédié : [Guide JWT](14-jwt.md). Cette fiche ne le duplique pas.

---

## 3. Dans le projet

### 3.1 La configuration actuelle : tout est ouvert

`server/src/main/java/com/personal/task_tracker/commons/config/SecurityConfig.java`, lignes 15 à 28 :

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandler problemHandler) {
	http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())   // (1)
			.csrf(AbstractHttpConfigurer::disable)                       // (2)
			.exceptionHandling(exceptions -> exceptions
					.authenticationEntryPoint(problemHandler)            // (3)
					.accessDeniedHandler(problemHandler));
	return http.build();
}

@Bean
public PasswordEncoder encoder() {
	return new BCryptPasswordEncoder();                                  // (4)
}
```

1. `anyRequest().permitAll()` : **toutes** les routes sont ouvertes. C'est un état temporaire assumé : le README
   l'indique (ligne 76 : "L'authentification n'est pas encore en place : tous les endpoints sont ouverts").
   Pourquoi déclarer une configuration alors ? Parce que dès que `spring-boot-starter-security` est présent,
   Spring Boot protège **tout** par défaut (avec un formulaire de login et un mot de passe généré au démarrage).
   Cette configuration remplace ce comportement par défaut.
2. CSRF désactivé, en prévision d'une API stateless.
3. Les 401 et 403 levés dans les filtres seront écrits par `SecurityProblemHandler`, au même format que les autres
   erreurs.
4. Le `PasswordEncoder` est déclaré comme un bean : n'importe quel service peut se le faire injecter.

### 3.2 Les erreurs de sécurité, des deux côtés

`commons/exception/SecurityProblemHandler.java`, ligne 24 :

```java
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
```

Une seule classe joue les deux rôles du tableau 2.2. Ses méthodes `commence` (ligne 34, le 401) et `handle`
(ligne 40, le 403) réutilisent `GlobalExceptionHandler.unauthorized()` et `forbidden()` (lignes 189 et 193 de
`GlobalExceptionHandler.java`) : le client reçoit le même `ProblemDetail`, que l'erreur vienne d'un filtre ou
d'un contrôleur.

Le commentaire de `GlobalExceptionHandler.java`, lignes 172 à 175, résume pourquoi il faut les deux :

```java
/**
 * Raised in a controller or by @PreAuthorize: without this handler, it would end in handleUnexpected as a 500.
 * The 401 raised by the security filters never reach this class, see SecurityProblemHandler.
 */
```

### 3.3 Le hash du mot de passe à l'inscription

`user/service/UserService.java`, ligne 78 :

```java
String passwordHash = passwordEncoder.encode(userDto.rawPassword());
```

Le nom `rawPassword` signale clairement qu'il s'agit de la valeur saisie, et `passwordHash` de ce qui est stocké.

Le test `shouldStoreHashedPassword` (`server/src/test/java/com/personal/task_tracker/auth/AuthControllerTest.java`,
lignes 85 à 99) le vérifie : la valeur stockée est différente du mot de passe envoyé, et
`passwordEncoder.matches(...)` la reconnaît (ligne 98).

### 3.4 La limite de 72 octets

`auth/dto/RegisterRequestDto.java`, lignes 27 à 30 :

```java
@NotBlank(message = "{user.password.required}")
@Size(min = 8, message = "{user.password.invalidSize}")
@MaxBytes(value = 72, message = "{user.password.invalidSize}")
String password
```

`@Size` compte des **caractères**, BCrypt des **octets**. Un caractère accentué ou un emoji occupe plusieurs octets
en UTF-8 : un mot de passe de 40 emojis passe `@Size` mais dépasse 72 octets. D'où la contrainte maison
`@MaxBytes` (fiche 06).

### 3.5 Ne jamais exposer le mot de passe

Trois protections dans le code :

- `RegisterRequestDto.toString()` (lignes 32 à 39) est redéfini pour remplacer le mot de passe par des étoiles :
  si l'objet est un jour écrit dans un log, le mot de passe n'y apparaît pas. Par défaut, le `toString` d'un record
  affiche **tous** ses champs.
- `user/dto/UserResponseDto.java` ne contient pas de champ `password` : impossible de le renvoyer par erreur.
- Le test `shouldRegisterUserWithoutExposingPassword` (`AuthControllerTest.java`, ligne 38) vérifie que la réponse
  ne contient ni `password` ni `rawPassword`.

### 3.6 Empêcher le client de choisir son rôle

`AuthControllerTest.java`, lignes 62 à 83 : le test `shouldIgnoreRoleSentInBody` envoie un JSON avec
`"role": "ADMIN"` et vérifie que l'utilisateur créé est `USER`. Le rôle est fixé côté serveur dans
`user/mapper/UserMapper.java`, ligne 20 (`UserRole.USER`), et `RegisterRequestDto` n'a pas de champ `role`.

Ce type d'attaque s'appelle **mass assignment** : le client ajoute des champs que le serveur recopie sans les
filtrer. Le DTO de requête est la défense : il ne contient que ce que le client a le droit de fournir.

### 3.7 Les rôles prévus

`user/enums/UserRole.java`, lignes 3 à 7 : `ADMIN`, `USER`, `GUEST`. Ils sont stockés mais pas encore utilisés dans
des règles d'accès : ce sera le cas avec le JWT (voir le guide, section 5).

---

## 4. À toi de jouer

1. **Observer le comportement par défaut de Spring Security.** Sur une branche jetable, commente temporairement le
   bean `filterChain` de `SecurityConfig`, démarre l'application et appelle `GET /api/tasks`.
   *À observer* : un 401 (ou une redirection vers `/login` dans un navigateur) au lieu d'un 200. Selon les beans
   déjà déclarés et les starters présents, Spring Boot affiche aussi dans les logs un mot de passe généré pour un
   utilisateur `user` ; regarde s'il apparaît, et cherche dans la documentation pourquoi. Remets le code en place
   ensuite.
2. **Voir le sel en action.** Dans un test temporaire, encode deux fois la même valeur avec le `PasswordEncoder`
   injecté et affiche les deux hash.
   *À observer* : deux chaînes différentes commençant par `$2a$` (identifiant de l'algorithme), suivies du coût, puis
   du sel et du hash. `matches` renvoie `true` pour les deux.
3. **Comprendre l'ordre des règles.** Sur une branche jetable, écris `.anyRequest().authenticated()` **avant**
   `.requestMatchers("/api/auth/**").permitAll()` et lance les tests.
   *À observer* : l'application ne démarre pas. Les versions récentes de Spring Security refusent qu'une règle soit
   déclarée après `anyRequest()`, car elle ne pourrait jamais s'appliquer. Lis le message d'erreur, puis remets
   le code en place.
4. **Lire le guide JWT** (`14-jwt.md`) et repérer, dans sa section 5, les lignes qui remplaceront le
   `permitAll()` actuel.

---

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Toutes les routes répondent 401 juste après avoir ajouté le starter security | Comportement par défaut : tout est protégé | Déclarer un `SecurityFilterChain` avec ses propres règles |
| 403 alors que l'utilisateur est ADMIN | `hasRole("ADMIN")` cherche `ROLE_ADMIN`, l'autorité n'a pas le préfixe | Préfixer les autorités par `ROLE_`, ou utiliser `hasAuthority` |
| Une route publique répond 401 | Règle `permitAll` placée après une règle plus générale | Mettre les règles précises avant `anyRequest()` |
| Une erreur de sécurité arrive au format Spring par défaut | Levée dans un filtre, que `@RestControllerAdvice` ne voit pas | Déclarer un `AuthenticationEntryPoint` / `AccessDeniedHandler` (ici `SecurityProblemHandler`) |
| La connexion échoue toujours alors que le mot de passe est bon | Comparaison de hash avec `equals` | Utiliser `passwordEncoder.matches` |
| Un `POST` répond 403 sans raison apparente | CSRF actif sans jeton CSRF | Désactiver CSRF pour une API stateless, ou fournir le jeton |
| Un mot de passe apparaît dans les logs | `toString` par défaut d'un record ou d'une classe | Redéfinir `toString`, ne jamais logguer un DTO contenant un secret |

---

## 6. Pour aller plus loin

- **JWT et API stateless** : [Guide JWT](14-jwt.md)
- **CORS** ⏳ : le front `client/` tournera sur une autre origine (autre port) que l'API. Le navigateur bloquera ses
  appels tant que l'API n'indique pas, via des en-têtes CORS, qu'elle les accepte. Se configure dans la
  `SecurityFilterChain` (`http.cors(...)`).
- **Sécurité par méthode** : `@EnableMethodSecurity`, `@PreAuthorize`, expressions comme
  `#taskId == authentication.name`.
- **Énumération d'utilisateurs** : renvoyer le même message pour "email inconnu" et "mauvais mot de passe".
- **Limitation du nombre de tentatives** de connexion (rate limiting) contre les attaques par force brute.
- **OWASP Top 10** : la liste de référence des failles web les plus courantes.
- Documentation officielle : *Spring Security Reference*, chapitres "Servlet Applications > Architecture",
  "Authorization > Authorize HTTP Requests", "Password Storage".

---

## 7. Quiz

**1. Un client appelle une route protégée sans token. Quel code reçoit-il, et quel composant l'écrit dans ce projet ?**

<details><summary>Réponse</summary>

Un 401. Il est levé par un filtre de sécurité, donc écrit par l'`AuthenticationEntryPoint` configuré, ici
`SecurityProblemHandler.commence`. `GlobalExceptionHandler` ne le voit pas.
</details>

**2. Pourquoi le projet déclare-t-il une `SecurityFilterChain` alors que tout est en `permitAll` ?**

<details><summary>Réponse</summary>

Parce que la seule présence de `spring-boot-starter-security` active une sécurité par défaut qui protège toutes les
routes. La configuration déclarée remplace ce comportement, désactive CSRF et branche le format d'erreur du projet.
</details>

**3. Pourquoi deux appels à `passwordEncoder.encode` avec la même valeur donnent-ils deux résultats différents ?**

<details><summary>Réponse</summary>

BCrypt génère un sel aléatoire à chaque appel et l'intègre au hash. C'est pour ça qu'on vérifie avec `matches`, qui
relit le sel contenu dans le hash stocké.
</details>

**4. Pourquoi `@Size(max = 72)` ne suffit-il pas pour le mot de passe ?**

<details><summary>Réponse</summary>

`@Size` compte des caractères, alors que la limite de BCrypt porte sur les octets. Un caractère non ASCII occupe
plusieurs octets en UTF-8. D'où `@MaxBytes(72)`.
</details>

**5. Qu'est-ce qui empêche un client de s'inscrire directement en ADMIN ?**

<details><summary>Réponse</summary>

`RegisterRequestDto` n'a pas de champ `role`, donc un `role` envoyé dans le JSON est ignoré, et `UserMapper.toEntity`
fixe toujours `UserRole.USER`. Le test `shouldIgnoreRoleSentInBody` le garantit.
</details>

**6. `hasRole("ADMIN")` ou `hasAuthority("ADMIN")` : lequel vérifie l'autorité `ROLE_ADMIN` ?**

<details><summary>Réponse</summary>

`hasRole("ADMIN")`, qui ajoute le préfixe `ROLE_`. `hasAuthority` compare le nom exact.
</details>
