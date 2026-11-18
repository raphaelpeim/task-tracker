# 10. Sécurité

**Ce que tu vas comprendre**

- Comment Spring Security s'intercale **avant** tes contrôleurs, sous forme de chaîne de filtres
- La différence entre authentification (401) et autorisation (403)
- Pourquoi on ne stocke jamais un mot de passe, mais son hash BCrypt
- Ce que le projet protège déjà aujourd'hui, alors que tous les end-points sont ouverts
- Les attaques classiques que le code évite déjà (attribution de rôle par le client, fuite de mot de passe)
- Ce qui se passe réellement sous le capot : comment Tomcat passe la main à Spring Security, la liste réelle des
  filtres du projet, le trajet d'une requête en log TRACE, ce que Boot fait si tu ne configures rien, et le format
  d'un hash BCrypt, avec de quoi l'observer toi-même

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
   Spring Boot protège **tout** par défaut : sans `SecurityFilterChain` déclarée, il en crée une qui exige d'être
   authentifié sur toutes les routes. Cette configuration remplace ce comportement par défaut. Le mot de passe
   généré au démarrage dont parlent beaucoup de tutoriels n'apparaît **pas** dans ce projet : la section 4.6
   explique pourquoi, et ce que Boot crée exactement à la place.
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

## 4. Sous le capot

Les sections précédentes disent **ce que** fait Spring Security. Celle-ci montre **comment** il s'y prend, et
comment le **voir** dans le projet. Même plan que dans [02. Beans et injection](02-beans-injection.md),
section 4 : le mécanisme réel, comment l'observer, la source. Tous les logs et résultats cités ont été relevés
sur ce projet (Spring Boot 4.1.1, Spring Security 7.1.1), en lançant un test d'intégration temporaire.

Pour activer les logs de sécurité sans toucher aux fichiers de configuration :
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--logging.level.org.springframework.security=TRACE`
(`DEBUG` suffit pour le résumé de démarrage et une ligne par requête ; `TRACE` détaille chaque filtre).

### 4.1 De Tomcat à ta `SecurityFilterChain` : trois étages

**Le mécanisme.** Tomcat ne connaît pas les beans Spring. Il ne sait exécuter qu'une liste de **filtres servlet**
(l'interface `jakarta.servlet.Filter`) enregistrés au démarrage, avant d'appeler le `DispatcherServlet`. Spring
Security se branche sur cette liste en trois étages :

| Étage | Classe réelle dans le projet | Qui le crée | Rôle |
|---|---|---|---|
| 1. Le pont | `DelegatingFilterProxy`, enregistré dans Tomcat sous le nom `springSecurityFilterChain` | Le bean `securityFilterChainRegistration` (classe `DelegatingFilterProxyRegistrationBean`), déclaré par l'auto-configuration `SecurityFilterAutoConfiguration` | Un filtre servlet qui ne contient qu'un **nom de bean**. À la première requête, il va chercher ce bean dans le contexte Spring et lui délègue tout |
| 2. Le dispatcher | Le bean `springSecurityFilterChain`, de classe `WebSecurityConfiguration$CompositeFilterChainProxy`, qui enveloppe un `FilterChainProxy` | `@EnableWebSecurity` | Le point d'entrée unique : choisit **la** chaîne qui correspond à la requête et exécute ses filtres un par un |
| 3. Ta chaîne | `DefaultSecurityFilterChain`, le bean `filterChain` de `SecurityConfig` | Toi, via `http.build()` | Une condition ("quelles requêtes ?", ici `any request`) et une liste ordonnée de filtres |

Pourquoi un pont ? Parce que Tomcat a besoin de ses filtres très tôt, alors que tes beans (et ceux dont ils
dépendent, comme `SecurityProblemHandler`) ne sont pas encore tous créés. Le `DelegatingFilterProxy` permet
d'enregistrer un filtre tout de suite et de ne résoudre le bean qu'au moment où il sert. Pourquoi un dispatcher ?
Parce qu'une application peut avoir **plusieurs** `SecurityFilterChain` (par exemple une pour `/api/**` en
stateless et une pour une console d'administration avec formulaire) : le `FilterChainProxy` prend la première
dont la condition correspond.

Note que le projet ne porte nulle part `@EnableWebSecurity` : c'est Boot qui l'ajoute
(`ServletWebSecurityAutoConfiguration$EnableWebSecurityConfiguration`, condition "aucun bean nommé
`springSecurityFilterChain`").

**L'observer.**

1. *Les filtres connus de Tomcat.* Lu dans un test via `ServletContext.getFilterRegistrations()` :
   ```
   requestContextFilter            -> org.springframework.boot.servlet.filter.OrderedRequestContextFilter
   Tomcat WebSocket (JSR356) Filter -> org.apache.tomcat.websocket.server.WsFilter
   characterEncodingFilter         -> org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter
   springSecurityFilterChain       -> org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean$1
   formContentFilter               -> org.springframework.boot.servlet.filter.OrderedFormContentFilter
   ```
   Tous les filtres de sécurité tiennent dans **une seule** entrée : `springSecurityFilterChain` (la classe
   `...RegistrationBean$1` est la sous-classe anonyme de `DelegatingFilterProxy` créée par Boot).
2. *La pile d'appels d'une requête refusée.* Extrait réel (raccourci) de la pile affichée en TRACE pour un
   `GET /api/tasks` refusé (expérience de 4.3). Lis-la **de bas en haut** : c'est l'ordre réel de traversée.
   ```
   org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
       at ...web.access.intercept.AuthorizationFilter.doFilter(AuthorizationFilter.java:99)
       at ...web.FilterChainProxy$VirtualFilterChain.doFilter(FilterChainProxy.java:385)
       at ...web.access.ExceptionTranslationFilter.doFilter(ExceptionTranslationFilter.java:126)
       [... les autres filtres de la chaîne, un VirtualFilterChain entre chaque ...]
       at ...web.FilterChainProxy.doFilter(FilterChainProxy.java:195)                       <- étage 2
       at ...WebSecurityConfiguration$CompositeFilterChainProxy.doFilter(WebSecurityConfiguration.java:317)
       at org.springframework.web.filter.DelegatingFilterProxy.doFilter(DelegatingFilterProxy.java:272)  <- étage 1
       at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:111)
       at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100)
       at org.springframework.web.filter.FormContentFilter.doFilterInternal(FormContentFilter.java:93)
       at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:199)
       at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:165)
       [... Tomcat : connecteur HTTP, pool de threads ...]
   ```
   Aucune ligne `DispatcherServlet` dans cette pile : la requête n'a jamais atteint Spring MVC (voir 4.5).
3. *Un point d'arrêt* dans `FilterChainProxy.doFilterInternal` (classe de `spring-security-web`, sources
   téléchargeables depuis l'IDE) : à chaque requête tu y passes, et la variable `filters` contient la liste de 4.2.

**Sans cet élément.** Si tu retires `spring-boot-starter-security` du `pom.xml`, `SecurityFilterAutoConfiguration`
n'a plus de raison de s'appliquer : aucun filtre `springSecurityFilterChain` n'est enregistré dans Tomcat, et
toutes les requêtes vont directement au `DispatcherServlet`, sans 401 ni 403 possibles (déduit du code de
l'auto-configuration, non testé en retirant réellement la dépendance).

**La source.** [Spring Security, Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
(sections "DelegatingFilterProxy", "FilterChainProxy", "SecurityFilterChain"),
[Spring Boot, Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html).

### 4.2 La chaîne réelle du projet, filtre par filtre

**Le mécanisme.** Chaque appel sur `http` dans `SecurityConfig` ajoute, configure ou retire des filtres :
`authorizeHttpRequests(...)` crée l'`AuthorizationFilter`, `exceptionHandling(...)` configure
l'`ExceptionTranslationFilter`, `csrf(AbstractHttpConfigurer::disable)` **retire** le `CsrfFilter`. D'autres
filtres sont ajoutés d'office par `HttpSecurity`. `http.build()` les trie dans un ordre fixé par Spring Security
(tu ne choisis pas l'ordre en écrivant les lignes dans un autre sens).

**L'observer.** Au démarrage, en `DEBUG`, une ligne résume la chaîne. Celle du projet :

```
o.s.s.web.DefaultSecurityFilterChain : Will secure any request with filters: DisableEncodeUrlFilter,
WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter, HeaderWriterFilter, LogoutFilter,
RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
ExceptionTranslationFilter, AuthorizationFilter
```

Dix filtres, dans l'ordre d'exécution :

| # | Filtre | Ce qu'il fait | Utile ici ? |
|---|---|---|---|
| 1 | `DisableEncodeUrlFilter` | Empêche Tomcat d'ajouter l'identifiant de session dans les URL (`;jsessionid=...`), qui fuiraient dans les logs | Protection gratuite |
| 2 | `WebAsyncManagerIntegrationFilter` | Transmet l'utilisateur courant aux traitements asynchrones de Spring MVC (voir 4.4) | Pas encore |
| 3 | `SecurityContextHolderFilter` | Prépare le `SecurityContext` de la requête, et le **vide** à la fin (4.4) | Oui, toujours |
| 4 | `HeaderWriterFilter` | Ajoute des en-têtes de sécurité à chaque réponse | Oui |
| 5 | `LogoutFilter` | Intercepte `/logout` | Non (API sans session) |
| 6 | `RequestCacheAwareFilter` | Rejoue la requête initiale après une redirection vers un formulaire de login | Non (pas de formulaire) |
| 7 | `SecurityContextHolderAwareRequestFilter` | Branche `request.isUserInRole(...)`, `request.getRemoteUser()` de l'API servlet sur Spring Security | Rarement |
| 8 | `AnonymousAuthenticationFilter` | Si personne n'est authentifié, range un utilisateur "anonyme" (`ROLE_ANONYMOUS`) au lieu de `null` | Oui |
| 9 | `ExceptionTranslationFilter` | Attrape les refus levés **après** lui et les transforme en 401 ou 403 (4.3, 4.5) | Oui |
| 10 | `AuthorizationFilter` | Applique tes règles `authorizeHttpRequests` | Oui |

Les en-têtes du filtre 4, relevés sur une réponse réelle du projet :

```
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
```

L'en-tête HSTS (`Strict-Transport-Security`) n'est envoyé qu'en HTTPS ; le log TRACE le dit :
`Not injecting HSTS header since it did not match request to [Is Secure]`.

Ce qui **manque** est aussi instructif :

- pas de `CsrfFilter` : c'est l'effet de `csrf(AbstractHttpConfigurer::disable)` (il apparaît dans les chaînes de
  4.6, où la ligne n'existe pas) ;
- **aucun filtre d'authentification** : ni formulaire, ni Basic, ni Bearer. Personne ne peut encore prouver son
  identité, tout le monde est "anonyme". Le filtre `BearerTokenAuthenticationFilter` n'apparaîtra qu'avec
  `oauth2ResourceServer(...)` (guide JWT, section 11) ;
- un détail du `LogoutFilter` : avec CSRF désactivé, il réagit à `GET`, `POST`, `PUT` et `DELETE /logout`
  (log : `Did not match request to Or [PathPattern [GET /logout], PathPattern [POST /logout], ...]`), alors
  qu'avec CSRF actif il n'accepte que `POST /logout`. Sans session, il n'a de toute façon rien à déconnecter.

**La source.** [Architecture, sections "Security Filters" et "Printing the Security Filters"](https://docs.spring.io/spring-security/reference/servlet/architecture.html).

### 4.3 Le trajet d'une requête, en TRACE

**Le mécanisme.** Le `FilterChainProxy` appelle les filtres un par un. Chacun peut laisser passer (appeler le
suivant), écrire lui-même la réponse et s'arrêter, ou lever une exception qui remonte jusqu'à un filtre placé
avant lui. Le dernier, `AuthorizationFilter`, décide ; si la décision est négative il lève une
`AccessDeniedException`, que l'`ExceptionTranslationFilter`, placé juste avant, attrape et traduit :

- l'utilisateur est **anonyme** (aucune preuve d'identité) → `AuthenticationEntryPoint` → 401 ;
- l'utilisateur est **authentifié** mais n'a pas le droit → `AccessDeniedHandler` → 403.

C'est la mécanique exacte derrière le tableau de 2.2.

**L'observer.** Extrait réel en `TRACE` pour `GET /api/tasks` avec la configuration actuelle (`permitAll`),
préfixe de log raccourci :

```
FilterChainProxy : Trying to match request against DefaultSecurityFilterChain defined as 'filterChain'
                   in [class path resource [com/personal/task_tracker/commons/config/SecurityConfig.class]]
                   matching [any request] and having filters [DisableEncodeUrl, ..., Authorization] (1/1)
FilterChainProxy : Securing GET /api/tasks
FilterChainProxy : Invoking DisableEncodeUrlFilter (1/10)
FilterChainProxy : Invoking WebAsyncManagerIntegrationFilter (2/10)
...
FilterChainProxy : Invoking ExceptionTranslationFilter (9/10)
FilterChainProxy : Invoking AuthorizationFilter (10/10)
RequestMatcherDelegatingAuthorizationManager : Authorizing GET /api/tasks
RequestMatcherDelegatingAuthorizationManager : Checking authorization on GET /api/tasks using
                   org.springframework.security.authorization.SingleResultAuthorizationManager@395ae6a2
FilterChainProxy : Secured GET /api/tasks
[... le contrôleur s'exécute ...]
AnonymousAuthenticationFilter : Set SecurityContextHolder to AnonymousAuthenticationToken
                   [Principal=anonymousUser, ..., Granted Authorities=[ROLE_ANONYMOUS]]
```

Deux choses à remarquer :

- `Secured GET /api/tasks` signifie "toute la chaîne a laissé passer" : la suite part vers le `DispatcherServlet`.
  `SingleResultAuthorizationManager` est la traduction de `permitAll()` : une décision fixe, "oui".
- La ligne `AnonymousAuthenticationFilter` arrive **après** le contrôleur. Ce n'est pas un bug d'ordre : depuis
  Spring Security 6, le `SecurityContext` est **différé** (classe `SupplierDeferredSecurityContext` dans les logs).
  Le filtre prépare une fonction qui fabriquera l'utilisateur anonyme, et elle n'est exécutée que lorsque quelqu'un
  lit le contexte. Avec `permitAll`, la règle n'a pas besoin de savoir qui tu es : personne ne le lit pendant la
  chaîne.

Même requête après avoir remplacé temporairement `permitAll()` par `authenticated()` (et remis ensuite) :

```
RequestMatcherDelegatingAuthorizationManager : Checking authorization on GET /api/tasks using
                   org.springframework.security.authorization.AuthenticatedAuthorizationManager@3f50852f
SupplierDeferredSecurityContext : Created SecurityContextImpl [Null authentication]
AnonymousAuthenticationFilter : Set SecurityContextHolder to AnonymousAuthenticationToken [...]
ExceptionTranslationFilter : Sending AnonymousAuthenticationToken [...] to authentication entry point
                   since access is denied
org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
    at ...AuthorizationFilter.doFilter(AuthorizationFilter.java:99)
```

Cette fois la règle a besoin de l'utilisateur : le contexte différé est calculé **pendant** l'autorisation,
l'anonyme est refusé, et l'`ExceptionTranslationFilter` l'envoie vers l'entry point. Le client reçoit le
`ProblemDetail` de `SecurityProblemHandler` :

```json
{"detail":"Authentication is required","instance":"/api/tasks","status":401,"title":"Unauthorized","code":"request.unauthorized"}
```

Un en-tête `Authorization: Bearer abc` ne change rien aujourd'hui (vérifié : même trace, même 401) : aucun filtre
de la chaîne ne lit cet en-tête.

**La source.** [Architecture, "Handling Security Exceptions"](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter),
[Authorize HttpServletRequests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html),
[Anonymous Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/anonymous.html).

### 4.4 `SecurityContextHolder` : une variable par thread

**Le mécanisme.** Comment un contrôleur, un service ou `@PreAuthorize` savent-ils "qui appelle", sans qu'on leur
passe l'utilisateur en paramètre ? `SecurityContextHolder` est une classe à méthodes statiques qui range le
`SecurityContext` dans un `ThreadLocal` : une variable dont chaque thread a **sa propre copie**. Tomcat traite
chaque requête sur un thread de son pool (les `o-auto-1-exec-1`, `-exec-2`... des logs ci-dessus, abréviation de
`http-nio-auto-1-exec-1`). Tout le code exécuté pour cette requête, sur ce thread, voit donc le même utilisateur,
et seulement lui.

Le `SecurityContextHolderFilter` (filtre 3) garantit le nettoyage : il **vide** le `ThreadLocal` dans un `finally`
en fin de requête. Sans ce nettoyage, le thread retourné au pool porterait encore l'utilisateur précédent, et la
requête suivante traitée par ce thread hériterait de son identité. C'est la raison d'être du filtre, même dans
une API sans session.

Conséquence pratique : un traitement lancé sur **un autre thread** (un `new Thread`, un `CompletableFuture`, un
`@Async`) ne voit pas l'utilisateur. Le filtre 2 (`WebAsyncManagerIntegrationFilter`) règle ce cas pour les
traitements asynchrones de Spring MVC ; pour tes propres threads, Spring Security fournit des enveloppes comme
`DelegatingSecurityContextExecutor`.

**L'observer.**

- La stratégie utilisée par le projet, lue dans un test :
  `SecurityContextHolder.getContextHolderStrategy().getClass()` →
  `org.springframework.security.core.context.ThreadLocalSecurityContextHolderStrategy`.
- Vérifié avec la configuration JWT du guide (appliquée temporairement, voir guide JWT 11.4) : pendant le même
  test, le thread `main` du test lit `getAuthentication() = null`, alors que le contrôleur appelé, sur le thread
  `http-nio-auto-1-exec-3`, lit un `JwtAuthenticationToken` au nom de l'utilisateur `42`.
- Point d'arrêt dans `TaskController.getAllTasks`, puis évalue `SecurityContextHolder.getContext().getAuthentication()`
  dans le débogueur : aujourd'hui, un `AnonymousAuthenticationToken` (`anonymousUser`, `ROLE_ANONYMOUS`), celui
  que fabrique le filtre 8.

**La source.** [Servlet Authentication Architecture, "SecurityContextHolder"](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html).

### 4.5 Pourquoi tes erreurs de sécurité ne passent pas par le `@RestControllerAdvice`

**Le mécanisme.** `GlobalExceptionHandler` est un `@RestControllerAdvice` : il est appelé par le
`DispatcherServlet`, qui attrape les exceptions levées **pendant** le traitement d'un contrôleur et cherche un
`@ExceptionHandler` correspondant. Les filtres de sécurité tournent **avant** le `DispatcherServlet` (pile de
4.1) : quand l'`AuthorizationFilter` refuse, le `DispatcherServlet` n'a pas encore été appelé et ne le sera pas.
L'exception est attrapée deux filtres plus haut par l'`ExceptionTranslationFilter`, qui appelle directement
`SecurityProblemHandler.commence(...)`. La réponse est écrite dans le filtre, et la requête s'arrête là.

D'où les deux mécanismes du projet (3.2) :

| Origine du refus | Qui l'attrape | Qui écrit la réponse |
|---|---|---|
| Un filtre (`AuthorizationFilter`, plus tard le filtre Bearer) | `ExceptionTranslationFilter` | `SecurityProblemHandler` |
| Un contrôleur, ou `@PreAuthorize` (qui s'exécute dans le proxy du bean, donc après le `DispatcherServlet`) | `DispatcherServlet` | `GlobalExceptionHandler` |

**Sans `SecurityProblemHandler`.** Expérience faite sur le projet : `authenticated()` à la place de `permitAll()`,
et bloc `exceptionHandling(...)` retiré (puis tout remis en place). `GET /api/tasks` sans identifiant répond :

```
403, corps vide
Set-Cookie: JSESSIONID=291A9377...; Path=/; HttpOnly
```

Trois surprises d'un coup :

1. **403 au lieu de 401.** Sans entry point déclaré et sans mécanisme de login dans la chaîne, Spring Security
   utilise par défaut un entry point qui répond 403 (`Http403ForbiddenEntryPoint`). Le client ne peut plus
   distinguer "connecte-toi" de "tu n'as pas le droit".
2. **Corps vide**, au lieu du `ProblemDetail` habituel : rien ne passe par `GlobalExceptionHandler`.
3. **Une session est créée** (`JSESSIONID`) : le `RequestCacheAwareFilter` sauvegarde la requête refusée en session
   pour pouvoir la rejouer après un login. Le log TRACE montre d'ailleurs deux passages dans
   l'`ExceptionTranslationFilter`, le second avec `SessionId=291A9377...` : Tomcat a redirigé en interne vers
   `/error`, qui repasse par la chaîne de sécurité.

Le `SecurityProblemHandler` n'est donc pas qu'une question de format : il garantit le bon code (401) et une
réponse cohérente avec le reste de l'API.

**La source.** [Architecture, "Handling Security Exceptions"](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter).

### 4.6 Sans `SecurityFilterChain` : ce que Boot fait à ta place

**Le mécanisme.** Trois auto-configurations se partagent le travail, chacune protégée par des conditions (le
principe des conditions est expliqué dans [02. Beans et injection](02-beans-injection.md), section 4.6) :

- `OAuth2ResourceServerWebSecurityAutoConfiguration` (apportée par le starter resource-server) : **s'il n'existe
  aucun bean `SecurityFilterChain`** et qu'un bean `JwtDecoder` existe, elle déclare `jwtSecurityFilterChain` :
  `anyRequest().authenticated()` + validation des JWT.
- `ServletWebSecurityAutoConfiguration$SecurityFilterChainConfiguration` : s'il n'existe **toujours** aucun bean
  `SecurityFilterChain`, elle déclare `defaultSecurityFilterChain` : `anyRequest().authenticated()` + formulaire de
  login + HTTP Basic.
- `UserDetailsServiceAutoConfiguration` : crée un utilisateur en mémoire nommé `user`, avec un mot de passe UUID
  aléatoire affiché dans les logs. Mais seulement si aucun bean `AuthenticationManager`, `AuthenticationProvider`,
  `UserDetailsService` ni `JwtDecoder` n'existe, **et** si aucune classe OAuth2 client / resource server / SAML
  n'est sur le classpath (sauf si `spring.security.user.name` ou `spring.security.user.password` est défini).

Ton bean `filterChain` suffit à faire reculer les deux premières. La troisième recule pour deux raisons, chacune
suffisante : le bean `jwtDecoder` déclaré par `auth/config/JwtConfig.java`, et le **starter resource-server**
présent dans le `pom.xml` (avant même l'existence de `JwtConfig`, il suffisait à la faire reculer).

**L'observer.** Le rapport des conditions (`--debug`, exercice 4 de la fiche 02) explique chaque décision.
Extraits réels du projet :

```
ServletWebSecurityAutoConfiguration$SecurityFilterChainConfiguration : did not match
  @ConditionalOnMissingBean (types: org.springframework.security.web.SecurityFilterChain; SearchStrategy: all)
  found beans of type 'org.springframework.security.web.SecurityFilterChain' filterChain

UserDetailsServiceAutoConfiguration : did not match
  ... @ConditionalOnMissingClass found unwanted class
  'org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector'
```

Et voici ce qui se passe réellement quand on retire temporairement le `@Bean` de `filterChain` (expériences sur
le projet, code remis en place ensuite). Les deux premières lignes comparent le code actuel et celui d'avant le
commit qui a ajouté `JwtConfig` : le simple ajout d'un bean `JwtDecoder` change le comportement par défaut.

| Situation | Chaîne créée par Boot | `GET /api/tasks` sans en-tête | Mot de passe généré ? |
|---|---|---|---|
| Code actuel (un bean `JwtDecoder` existe, via `JwtConfig`) | `jwtSecurityFilterChain`, 13 filtres dont `CsrfFilter`, `BearerTokenAuthenticationFilter` | 401, `WWW-Authenticate: Bearer resource_metadata="http://localhost:<port>/.well-known/oauth-protected-resource"`, corps vide | Non, même avec `-Dspring.security.user.name=user` (le bean `JwtDecoder` suffit à l'empêcher) |
| Code d'avant `JwtConfig` | `defaultSecurityFilterChain`, 16 filtres dont `CsrfFilter`, `UsernamePasswordAuthenticationFilter`, `DefaultLoginPageGeneratingFilter`, `BasicAuthenticationFilter` | 401, `WWW-Authenticate: Basic realm="Realm", charset="UTF-8"`, corps vide | Non |
| Code d'avant `JwtConfig` + `-Dspring.security.user.name=user` | `defaultSecurityFilterChain` | 401 Basic, comme la ligne 2 | **Oui** |

Dans le dernier cas, le log `WARN` attendu apparaît enfin :

```
.s.a.UserDetailsServiceAutoConfiguration :

Using generated security password: <UUID>

This generated password is for development use only. Your security configuration must be updated before running
your application in production.
```

À retenir : le comportement "par défaut" dépend des starters et des beans présents. Ne l'apprends pas par cœur,
lis le rapport des conditions. Et dans tous les cas, sans ta `SecurityFilterChain`, `POST /api/auth/register`
serait refusé : plus personne ne pourrait s'inscrire.

**La source.** [Spring Boot, Spring Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html),
le code des trois auto-configurations, court et lisible :
[ServletWebSecurityAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/web/servlet/ServletWebSecurityAutoConfiguration.java),
[OAuth2ResourceServerWebSecurityAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-security-oauth2-resource-server/src/main/java/org/springframework/boot/security/oauth2/server/resource/autoconfigure/web/OAuth2ResourceServerWebSecurityAutoConfiguration.java),
[UserDetailsServiceAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/UserDetailsServiceAutoConfiguration.java)
et sa condition
[MissingAlternativeUserDetailsManagerOrUserPropertiesConfigured](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/MissingAlternativeUserDetailsManagerOrUserPropertiesConfigured.java).

### 4.7 BCrypt de près

**Le mécanisme.** Deux hash réels du même mot de passe (`motdepasse`), produits par le bean `encoder` du projet :

```
$2a$10$TFqlwzrALOK0ILhVK0nvHuL65DMXOMAzoD2ojsBqDNFtflVT9/Tcm
$2a$10$a8zdB6V5pFIj8ZstLyQT4efQo2kzLYW4Lh/UIyEIOllfwFqJOhwDq
```

Découpage du premier (60 caractères, toujours) :

| Morceau | Valeur | Signification |
|---|---|---|
| `$2a$` | `2a` | Version de l'algorithme BCrypt |
| `10$` | `10` | Coût : 2^10 = 1024 tours de la phase coûteuse de l'algorithme. Valeur par défaut de `new BCryptPasswordEncoder()` |
| 22 caractères suivants | `TFqlwzrALOK0ILhVK0nvHu` | Le **sel** (16 octets aléatoires), encodé dans une variante de base64 propre à BCrypt |
| 31 derniers caractères | `L65DMXOMAzoD2ojsBqDNFtflVT9/Tcm` | Le hash proprement dit |

Tout ce qu'il faut pour vérifier un mot de passe est **dans** la chaîne : `matches(saisi, stocké)` relit la
version, le coût et le sel dans la valeur stockée, recalcule avec ces paramètres, et compare. C'est pourquoi il
n'y a pas de colonne "sel" dans `app_user` (`V1__create_app_user_table.sql` : `password VARCHAR(255)`), et
pourquoi on peut augmenter le coût plus tard sans casser les comptes existants : les anciens hash gardent leur
`$10$`, les nouveaux auront le nouveau coût.

**L'observer.** Mesures faites dans un test du projet, sur la machine de développement :

| Coût | Temps d'un `encode` | Hash obtenu (début) |
|---|---|---|
| 10 (défaut) | 67 à 90 ms | `$2a$10$...` |
| 12 (`new BCryptPasswordEncoder(12)`) | 277 à 291 ms | `$2a$12$...` |

Chaque +1 sur le coût double le temps (ici x4 pour +2). C'est voulu : 80 ms sont invisibles pour un utilisateur
qui se connecte, mais un attaquant qui teste des milliards de mots de passe paie ce prix à chaque essai. `matches`
coûte le même temps, ce qui rend le login volontairement "lent".

La limite des 72 octets (2.4), vérifiée : `encoder.encode("a".repeat(73))` lève
`java.lang.IllegalArgumentException: password cannot be more than 72 bytes`. Sans `@MaxBytes` sur le DTO, cette
exception partirait de `UserService` vers `GlobalExceptionHandler`, qui n'a pas de handler dédié : elle finirait
probablement dans `handleUnexpected`, en 500 au lieu d'un 400 (déduit du code, non testé).

**La source.** [Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
(sections "BCryptPasswordEncoder" et "DelegatingPasswordEncoder").

---

## 5. À toi de jouer

1. **Observer le comportement par défaut de Spring Security.** Sur une branche jetable, commente temporairement le
   bean `filterChain` de `SecurityConfig`, démarre l'application et appelle `GET /api/tasks`.
   *À observer* : un 401 au lieu d'un 200, avec un en-tête `WWW-Authenticate`. Regarde sa valeur (`Bearer` ou
   `Basic` ?) et cherche si un mot de passe généré apparaît dans les logs. Essaie d'expliquer les deux avant de lire
   la réponse en 4.6. Remets le code en place ensuite.
2. **Voir le sel en action.** Dans un test temporaire, encode deux fois la même valeur avec le `PasswordEncoder`
   injecté et affiche les deux hash.
   *À observer* : deux chaînes différentes commençant par `$2a$` (identifiant de l'algorithme), suivies du coût, puis
   du sel et du hash. `matches` renvoie `true` pour les deux.
3. **Comprendre l'ordre des règles.** Sur une branche jetable, écris `.anyRequest().authenticated()` **avant**
   `.requestMatchers("/api/auth/**").permitAll()` et lance les tests.
   *À observer* : l'application ne démarre pas. Les versions récentes de Spring Security refusent qu'une règle soit
   déclarée après `anyRequest()`, car elle ne pourrait jamais s'appliquer. Le message réel, relevé sur le projet :
   `java.lang.IllegalStateException: Can't configure requestMatchers after anyRequest`. Remets le code en place.
4. **Lire le guide JWT** (`14-jwt.md`) et repérer, dans sa section 5, les lignes qui remplaceront le
   `permitAll()` actuel.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Toutes les routes répondent 401 juste après avoir ajouté le starter security | Comportement par défaut : tout est protégé | Déclarer un `SecurityFilterChain` avec ses propres règles |
| 403 alors que l'utilisateur est ADMIN | `hasRole("ADMIN")` cherche `ROLE_ADMIN`, l'autorité n'a pas le préfixe | Préfixer les autorités par `ROLE_`, ou utiliser `hasAuthority` |
| Une route publique répond 401 | Règle `permitAll` placée après une règle plus générale | Mettre les règles précises avant `anyRequest()` |
| Une erreur de sécurité arrive au format Spring par défaut | Levée dans un filtre, que `@RestControllerAdvice` ne voit pas | Déclarer un `AuthenticationEntryPoint` / `AccessDeniedHandler` (ici `SecurityProblemHandler`) |
| La connexion échoue toujours alors que le mot de passe est bon | Comparaison de hash avec `equals` | Utiliser `passwordEncoder.matches` |
| Un `POST` répond 403 sans raison apparente | CSRF actif sans jeton CSRF | Désactiver CSRF pour une API stateless, ou fournir le jeton |
| Un mot de passe apparaît dans les logs | `toString` par défaut d'un record ou d'une classe | Redéfinir `toString`, ne jamais logguer un DTO contenant un secret |
| 403 au corps vide au lieu d'un 401, et un cookie `JSESSIONID` | Aucun `authenticationEntryPoint` déclaré : entry point par défaut qui répond 403 (4.5) | Déclarer l'entry point (ici `SecurityProblemHandler`) |

---

## 7. Pour aller plus loin

- **JWT et API stateless** : [Guide JWT](14-jwt.md)
- **CORS** ⏳ : le front `client/` tournera sur une autre origine (autre port) que l'API. Le navigateur bloquera ses
  appels tant que l'API n'indique pas, via des en-têtes CORS, qu'elle les accepte. Se configure dans la
  `SecurityFilterChain` (`http.cors(...)`).
- **Sécurité par méthode** : `@EnableMethodSecurity`, `@PreAuthorize`, expressions comme
  `#taskId == authentication.name`.
- **Énumération d'utilisateurs** : renvoyer le même message pour "email inconnu" et "mauvais mot de passe".
- **Limitation du nombre de tentatives** de connexion (rate limiting) contre les attaques par force brute.
- **OWASP Top 10** : la liste de référence des failles web les plus courantes.
- **Docs officielles** : les liens de la section 4, en particulier
  [Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html),
  [Authorize HttpServletRequests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
  et [Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
- **`DelegatingPasswordEncoder`** : un format de hash préfixé (`{bcrypt}$2a$10$...`) qui permet de changer
  d'algorithme plus tard sans invalider les mots de passe existants (même page Password Storage).

---

## 8. Quiz

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
