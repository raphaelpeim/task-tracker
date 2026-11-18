# 05. Web et REST

**Ce que tu vas comprendre**
- Ce qu'est une API REST et comment les verbes HTTP s'associent aux actions
- Comment Spring MVC relie une URL à une méthode Java (`@GetMapping`, `@PathVariable`, `@RequestBody`)
- Comment choisir et renvoyer le bon code de statut avec `ResponseEntity`
- Comment Jackson convertit JSON ↔ Java
- Pourquoi `PATCH` a besoin de `JsonNullable` alors que `PUT` non
- Le trajet réel d'une requête dans Spring MVC (Tomcat, `DispatcherServlet`, `HandlerMapping`, `HandlerAdapter`,
  résolveurs d'arguments, `HttpMessageConverter`) et ce que l'auto-configuration web crée, avec de quoi l'observer

**Prérequis** : [04. Architecture en couches](04-architecture-couches.md). Utile ensuite :
[06. Validation](06-validation.md), [07. Exceptions](07-exceptions.md).

---

## 1. Le problème que ça résout

Ton front (`client/`) et ton serveur sont deux programmes séparés. Ils ont besoin d'une langue commune : **HTTP**
pour le transport, **JSON** pour les données. Sans convention, chaque end-point inventerait la sienne :
`/getTask?id=1`, `/deleteTaskNow`, `/tasks/update`... Le front devrait tout apprendre par cœur.

**REST** est cette convention : on manipule des **ressources** (les tâches, les utilisateurs) désignées par une URL,
et c'est le **verbe** HTTP qui dit ce qu'on fait. Une fois la règle connue, l'API se devine.

**Analogie** : une bibliothèque. L'URL est le rayon et la cote du livre (`/api/tasks/1`), le verbe est ce que tu
demandes au guichet : consulter, déposer, remplacer, corriger, retirer.

Spring MVC, de son côté, t'évite d'écrire le code réseau : tu annotes une méthode, Spring s'occupe de lire la
requête, d'appeler la méthode et d'écrire la réponse.

---

## 2. Les concepts

### 2.1 Une requête et une réponse HTTP

```
PATCH /api/tasks/1 HTTP/1.1            ← verbe, chemin
Content-Type: application/json         ← en-têtes : métadonnées
Accept: application/json

{"status":"DONE"}                      ← corps
```

```
HTTP/1.1 200 OK                        ← code de statut
Content-Type: application/json

{"id":1,"title":"...","status":"DONE",...}
```

HTTP est **sans état** : chaque requête doit porter tout ce dont le serveur a besoin. Le serveur ne se souvient pas
de la précédente (c'est pour ça que l'authentification passera par un token envoyé à chaque fois, voir
[10. Sécurité](10-securite.md)).

### 2.2 Ressources et verbes

| Verbe | URL | Action | Code de succès habituel |
|---|---|---|---|
| `GET` | `/api/tasks` | Lister | 200 |
| `GET` | `/api/tasks/1` | Lire une tâche | 200 |
| `POST` | `/api/tasks` | Créer | 201 Created |
| `PUT` | `/api/tasks/1` | Remplacer **entièrement** | 200 |
| `PATCH` | `/api/tasks/1` | Modifier **partiellement** | 200 |
| `DELETE` | `/api/tasks/1` | Supprimer | 204 No Content |

Conventions : nom de ressource au **pluriel**, pas de verbe dans l'URL, l'identifiant dans le chemin.

**Idempotence** : une requête est idempotente si la rejouer laisse le serveur dans le même état. `GET`, `PUT` et
`DELETE` le sont (supprimer deux fois la tâche 1 : elle reste supprimée). `POST` ne l'est pas (deux `POST` créent
deux tâches). C'est important quand un client réessaie après une coupure réseau.

### 2.3 Les codes de statut

| Famille | Sens | Exemples dans le projet |
|---|---|---|
| 2xx | Succès | 200 OK, 201 Created, 204 No Content |
| 4xx | Erreur **du client** | 400 requête invalide, 401 non authentifié, 403 interdit, 404 introuvable, 405 verbe non supporté, 409 conflit |
| 5xx | Erreur **du serveur** | 500 erreur inattendue |

Règle simple : si le client peut corriger sa requête, c'est un 4xx. Si c'est un bug chez toi, c'est un 5xx.

### 2.4 Spring MVC : du HTTP aux méthodes Java

Le **DispatcherServlet** reçoit toutes les requêtes et cherche la méthode correspondante parmi les controllers.

| Annotation | Rôle |
|---|---|
| `@RestController` | La classe est un controller, et ce que renvoient ses méthodes est écrit dans le corps de la réponse (en JSON) |
| `@RequestMapping("/api/tasks")` | Préfixe commun à toutes les méthodes de la classe |
| `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` | Verbe + suite du chemin |
| `@PathVariable` | Récupère un morceau variable du chemin : `/{taskId}` |
| `@RequestBody` | Convertit le corps JSON en objet |
| `@RequestParam` ⏳ | Récupère un paramètre de requête : `?status=DONE&page=0` |

Spring **convertit** les types tout seul : `"1"` dans l'URL devient un `Long`. Si la conversion échoue (`"abc"`), il
lève une exception qui finit en 400.

### 2.5 `ResponseEntity`

`ResponseEntity<T>` représente toute la réponse : corps (`T`), code de statut, en-têtes.

```java
return new ResponseEntity<>(dto, HttpStatus.CREATED);          // corps + 201
return ResponseEntity.status(HttpStatus.NO_CONTENT).build();   // 204 sans corps
```

On pourrait renvoyer directement le DTO (Spring répond alors 200), mais `ResponseEntity` rend le code explicite.

### 2.6 Jackson : JSON ↔ Java

Jackson est la bibliothèque de (dé)sérialisation, configurée automatiquement par Spring Boot. Ce projet utilise
**Jackson 3**, dont les packages commencent par `tools.jackson` (Jackson 2 utilisait `com.fasterxml.jackson`).

- **Désérialisation** (requête) : les propriétés JSON sont associées aux composants du record par leur nom.
  `{"title":"..."}` remplit `title`.
- **Sérialisation** (réponse) : chaque accesseur du record devient une propriété JSON.
- Les enums voyagent par leur nom : `"BUG"` ↔ `TaskType.BUG`. Une valeur inconnue fait échouer la conversion.
- Les `LocalDateTime` sont écrits au format ISO-8601 (`2026-10-28T14:30:00`).

### 2.7 PUT ou PATCH : le problème du « champ absent »

Avec `PUT`, le client envoie **la ressource complète**. Un champ absent veut dire « vide ». Un simple `String` suffit.

Avec `PATCH`, le client n'envoie **que ce qui change**. Il faut alors distinguer trois cas pour chaque champ :

| JSON reçu | Sens | `String` simple | `JsonNullable<String>` |
|---|---|---|---|
| `{}` (champ absent) | Ne pas toucher | `null` | `JsonNullable.undefined()` |
| `{"description": null}` | Effacer la valeur | `null` aussi ! | `JsonNullable.of(null)` |
| `{"description": "x"}` | Mettre « x » | `"x"` | `JsonNullable.of("x")` |

Avec un `String`, les deux premiers cas sont indiscernables. `JsonNullable` (bibliothèque
`jackson-databind-nullable` déclarée dans le `pom.xml`) ajoute l'état « absent ». On l'interroge avec
`isPresent()` et `ifPresent(...)`.

---

## 3. Dans le projet

### Le controller des tâches, verbe par verbe

`server/src/main/java/com/personal/task_tracker/task/controller/TaskController.java`

```java
@RestController
@RequestMapping("/api/tasks")                                  // ligne 14-15 : préfixe
public class TaskController {

	@GetMapping                                                // ligne 23 : GET /api/tasks
	public ResponseEntity<List<TaskResponseDto>> getAllTasks() { ... }

	@GetMapping("/{taskId}")                                   // ligne 29 : GET /api/tasks/1
	public ResponseEntity<TaskResponseDto> getTask(@PathVariable Long taskId) { ... }

	@PostMapping                                               // ligne 37-43
	public ResponseEntity<TaskResponseDto> createTask(@Valid @RequestBody TaskRequestDto taskDto) {
		...
		return new ResponseEntity<>(createdTaskDto, HttpStatus.CREATED);   // 201
	}

	@PutMapping("/{taskId}")                                   // ligne 45 : DTO complet
	... @Valid @RequestBody TaskRequestDto taskDto

	@PatchMapping("/{taskId}")                                 // ligne 54 : DTO partiel
	... @Valid @RequestBody TaskRequestPartialDto taskDto

	@DeleteMapping("/{taskId}")                                // ligne 63-69
	public ResponseEntity<Void> deleteTask(@PathVariable Long taskId) {
		taskService.deleteTask(taskId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();       // 204, pas de corps
	}
}
```

Note que `PUT` et `PATCH` reçoivent deux DTO différents : c'est la traduction directe de la section 2.7.

### Le DTO partiel

`server/src/main/java/com/personal/task_tracker/task/dto/TaskRequestPartialDto.java:11-29`

```java
public record TaskRequestPartialDto(
		@NotBlank(message = "{task.title.required}")
		@Size(max = 50, message = "{task.title.invalidSize}")
		JsonNullable<String> title,
		...
		JsonNullable<Long> assigneeId
) {
}
```

### Le mapper n'applique que ce qui est présent

`server/src/main/java/com/personal/task_tracker/task/mapper/TaskMapper.java:47-53`

```java
public static void applyPartialUpdate(Task task, TaskRequestPartialDto taskDto) {
	taskDto.title().ifPresent(task::setTitle);           // absent : rien ne se passe
	taskDto.description().ifPresent(task::setDescription);
	...
}
```

À comparer avec `applyUpdate` (`TaskMapper.java:39-45`) qui écrase tout, sans condition, pour le `PUT`.

### Les trois états, prouvés par les tests

Dans `server/src/test/java/com/personal/task_tracker/task/TaskControllerTest.java` :

| Test | Ligne | Ce qu'il montre |
|---|---|---|
| `shouldKeepTaskUnchangedWhenPatchBodyIsEmpty` | 674 | `PATCH {}` : 200 et rien ne change |
| `shouldUnassignTaskWhenAssigneeIsExplicitlyNull` | 716 | `assigneeId` à `null` explicite : la tâche est désassignée |
| `shouldRemoveDescriptionWhenPatchDescriptionIsExplicitlyNull` | 868 | `description` à `null` : la description est effacée |
| `shouldUnassignTaskWhenPutHasNoAssignee` | 519 | En `PUT`, un `assigneeId` absent désassigne : le `PUT` remplace tout |

### Les erreurs HTTP produites par Spring lui-même

Toujours dans `TaskControllerTest.java` :

- `shouldReturn400WhenIdIsNotNumeric` (ligne 458) : `GET /api/tasks/abc` → 400, `"abc"` ne se convertit pas en `Long`.
- `shouldReturn405WithCodeWhenMethodIsNotSupported` (ligne 469) : `POST /api/tasks/1` → 405, l'URL existe mais pas
  pour ce verbe.
- `shouldReturn400WithoutFieldErrorWhenJsonIsMalformed` (ligne 255) : JSON coupé → 400.

Tu n'as écrit aucun code pour ces cas : c'est Spring MVC qui les détecte. Le format de la réponse, lui, vient de
`GlobalExceptionHandler` ([07. Exceptions](07-exceptions.md)).

---

## 4. Sous le capot

Tu as écrit `@GetMapping` sur une méthode, et elle répond en JSON à une URL. Entre les deux, Spring MVC fait tourner
une mécanique que tu n'as jamais vue. Cette section la démonte pièce par pièce, comme la section 4 de
[02. Beans et injection](02-beans-injection.md) : le mécanisme réel, comment l'observer, la source, et ce qui se
passe si on retire une pièce.

**Comment observer.** Tout ce qui suit a été relevé sur le projet avec un test temporaire qui étend
`IntegrationTest` (méthode décrite dans [03. Configuration](03-configuration.md), section 4), qui inspecte le contexte
puis envoie de vraies requêtes HTTP avec RestAssured. Le log clé est `logging.level.org.springframework.web=DEBUG` ;
le modèle `application-local.properties.example` prévoit déjà cette clé pour l'application lancée en local.

### 4.1 Tomcat embarqué : le serveur est dans ton jar

**Le mécanisme.** Pas de serveur à installer : `spring-boot-starter-webmvc` apporte Tomcat sous forme de
bibliothèque (`tomcat-embed-core` 11.0.24 dans ce projet). Comme ce jar est sur le classpath, Spring Boot crée un
contexte "web" : un `AnnotationConfigServletWebServerApplicationContext` (classe observée). Pendant son `refresh()`
(02, section 4.1), avant même de fabriquer tes beans, ce contexte demande au bean `tomcatServletWebServerFactory`
(créé par `TomcatServletWebServerAutoConfiguration`) de démarrer un serveur : un `TomcatWebServer` (observé). Puis il
enregistre dans Tomcat les servlets et filtres déclarés comme beans.

**L'observer.**

- Dans le log de démarrage, Tomcat apparaît **avant** la fin de la création des beans. Extrait réel de
  `./mvnw spring-boot:run` lancé sans profil, qui échoue ensuite sur la base de données :
  ```
  o.s.boot.tomcat.TomcatWebServer          : Tomcat initialized with port 8080 (http)
  o.apache.catalina.core.StandardService   : Starting service [Tomcat]
  o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/11.0.24]
  b.w.c.s.WebApplicationContextInitializer : Root WebApplicationContext: initialization completed in 588 ms
  ConfigServletWebServerApplicationContext : Exception encountered during context initialization - cancelling refresh attempt: ...
  o.apache.catalina.core.StandardService   : Stopping service [Tomcat]
  ```
- Ce que le contexte a enregistré dans Tomcat (lu dans le `ServletContext` par le test) :
  ```
  servlet dispatcherServlet -> org.springframework.web.servlet.DispatcherServlet [/]
  filter requestContextFilter -> org.springframework.boot.servlet.filter.OrderedRequestContextFilter [/*]
  filter Tomcat WebSocket (JSR356) Filter -> org.apache.tomcat.websocket.server.WsFilter [/*]
  filter characterEncodingFilter -> org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter [/*]
  filter springSecurityFilterChain -> org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean$1 [/*]
  filter formContentFilter -> org.springframework.boot.servlet.filter.OrderedFormContentFilter [/*]
  ```
  **Une seule** servlet, mappée sur `/` : toutes les requêtes arrivent au `DispatcherServlet`. Avant elle, des
  filtres, dont `springSecurityFilterChain`, l'étape ② du schéma de [04. Architecture](04-architecture-couches.md)
  (détails dans [10. Sécurité](10-securite.md)).
- Chaque requête est traitée par un thread du pool de Tomcat. Dans les logs DEBUG, la colonne du thread affiche
  `[o-auto-1-exec-1]`, `[o-auto-1-exec-2]`... (fin du nom du thread, tronqué par le format de log ; `auto` parce que
  les tests utilisent un port aléatoire). C'est pour ça qu'un bean ne doit pas garder d'état de requête
  (02, section 2.1) : plusieurs threads l'utilisent en même temps.
- Le `DispatcherServlet` lui-même s'initialise **à la première requête**, sur le thread de cette requête (extrait
  réel) :
  ```
  [o-auto-1-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
  [o-auto-1-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
  [o-auto-1-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 2 ms
  ```

**La source.** [Embedded Servlet Container Support](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container),
[Embedded Web Servers (how-to)](https://docs.spring.io/spring-boot/how-to/webserver.html),
[DispatcherServlet](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html).

### 4.2 Au démarrage : la table des routes

**Le mécanisme.** Le bean `requestMappingHandlerMapping` (un `RequestMappingHandlerMapping`) parcourt au démarrage
tous les beans annotés `@Controller` (`@RestController` en fait partie), lit leurs `@RequestMapping`,
`@GetMapping`... et construit une table : *verbe + chemin* → *méthode Java* (`HandlerMethod`). À l'exécution, il n'y
a plus d'annotation à lire : seulement une recherche dans cette table.

**L'observer.** Avec en plus
`-Dlogging.level.org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping=TRACE`, extrait
réel du démarrage :

```
	c.p.t.t.c.TaskController:
	{GET [/api/tasks]}: getAllTasks()
	{POST [/api/tasks]}: createTask(TaskRequestDto)
	{PUT [/api/tasks/{taskId}]}: updateTask(Long,TaskRequestDto)
	{PATCH [/api/tasks/{taskId}]}: updatePartialTask(Long,TaskRequestPartialDto)
	{DELETE [/api/tasks/{taskId}]}: deleteTask(Long)
	{GET [/api/tasks/{taskId}]}: getTask(Long)
...
13 mappings in 'requestMappingHandlerMapping'
```

Les 13 : les 6 de `TaskController`, 2 de `UserController`, 1 d'`AuthController`, 2 de
`SecurityErrorsTestController` (un controller qui n'existe que dans les sources de test) et 2 pour `/error`, apportés
par `BasicErrorController` de Spring Boot.

**Ce qui casse.** C'est ici, au démarrage, que les conflits sont détectés. Vérifié en ajoutant temporairement un
second controller avec `@GetMapping("/api/tasks")` : le bean `requestMappingHandlerMapping` ne peut pas être créé,
l'application ne démarre pas :

```
Ambiguous mapping. Cannot map 'taskController' method
com.personal.task_tracker.task.controller.TaskController#getAllTasks()
to {GET [/api/tasks]}: There is already 'tmpDuplicateController' bean method
com.personal.task_tracker.TmpDuplicateController#duplicate() mapped.
```

**La source.** [Request Mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html).

### 4.3 Le parcours d'une requête dans le `DispatcherServlet`

**Le mécanisme.** Pour chaque requête, le `DispatcherServlet` enchaîne quatre types de composants, qui sont tous des
beans du contexte :

| Étape | Composant | Rôle | Dans le projet (observé) |
|---|---|---|---|
| 1 | `HandlerMapping` | Trouver qui traite la requête | 6 beans, interrogés dans cet ordre : `RouterFunctionMapping`, `RequestMappingHandlerMapping` (tes controllers), `WelcomePageHandlerMapping`, `BeanNameUrlHandlerMapping`, `WelcomePageNotAcceptableHandlerMapping`, et en dernier `SimpleUrlHandlerMapping` (fichiers statiques) |
| 2 | `HandlerAdapter` | Savoir **appeler** ce handler | 4 beans ; pour une méthode `@GetMapping`, c'est `RequestMappingHandlerAdapter` |
| 3 | (dans l'adaptateur) résolveurs d'arguments, puis gestionnaires de valeur de retour | Fabriquer chaque paramètre, puis écrire la réponse | 36 résolveurs, 15 gestionnaires (4.4 à 4.6) |
| 4 | `HandlerExceptionResolver` | Transformer une exception en réponse | `ExceptionHandlerExceptionResolver`, qui trouve ton `GlobalExceptionHandler` |

Pourquoi cette indirection ? Parce que le `DispatcherServlet` ne connaît rien aux annotations : il sait seulement
déléguer. C'est ce qui permet d'avoir dans la même application des controllers annotés, des fichiers statiques et des
"router functions", chacun avec son couple mapping/adaptateur.

**L'observer.** Le log DEBUG de `POST /api/tasks` (corps JSON valide), extrait réel, colonne thread et logger
conservées :

```
[o-auto-1-exec-1] o.s.web.servlet.DispatcherServlet        : POST "/api/tasks", parameters={}
[o-auto-1-exec-1] s.w.s.m.m.a.RequestMappingHandlerMapping : Mapped to com.personal.task_tracker.task.controller.TaskController#createTask(TaskRequestDto)
[o-auto-1-exec-1] m.m.a.RequestResponseBodyMethodProcessor : Read "application/json;charset=UTF-8" to [TaskRequestDto[title=Probe, description=null, type=BUG, status=BACKLOG, priority=LOW, assigneeId=nul (truncated)...]
[o-auto-1-exec-1] o.s.w.s.m.m.a.HttpEntityMethodProcessor  : Using 'application/json', given [*/*] and supported [application/json, application/*+json]
[o-auto-1-exec-1] o.s.w.s.m.m.a.HttpEntityMethodProcessor  : Writing [TaskResponseDto[id=1, title=Probe, description=null, type=BUG, status=BACKLOG, priority=LOW, assigne (truncated)...]
[o-auto-1-exec-1] o.s.web.servlet.DispatcherServlet        : Completed 201 CREATED
```

Ligne par ligne : la requête entre (1), la table des routes trouve la méthode (2), le résolveur de `@RequestBody`
lit le JSON (4.4, 4.5), ton code s'exécute (pas de log, c'est le trou entre les lignes 3 et 4), le gestionnaire de
`ResponseEntity` choisit le format puis écrit (4.5, 4.6), la réponse sort (6).

Et une URL qui n'existe pas, `GET /api/inexistant` ? Extrait réel :

```
o.s.web.servlet.DispatcherServlet        : GET "/api/inexistant", parameters={}
o.s.w.s.handler.SimpleUrlHandlerMapping  : Mapped to ResourceHttpRequestHandler [classpath [META-INF/resources/], classpath [resources/], classpath [static/], classpath [public/], ServletContext [/]]
o.s.w.s.r.ResourceHttpRequestHandler     : Resource not found for path [api/inexistant]
.m.m.a.ExceptionHandlerExceptionResolver : Using @ExceptionHandler com.personal.task_tracker.commons.exception.GlobalExceptionHandler#handleException(Exception, WebRequest)
o.s.web.servlet.DispatcherServlet        : Completed 404 NOT_FOUND
```

Surprise : aucune table "ne trouve rien". Faute de controller, c'est le **dernier** `HandlerMapping`, celui des
**fichiers statiques**, qui prend la requête (il accepte tous les chemins), ne trouve pas de fichier, et lève
`NoResourceFoundException`. C'est cette exception que `GlobalExceptionHandler` transforme en 404 au format
`application/problem+json`.

**La source.** [Special Bean Types](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/special-bean-types.html),
[Processing](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/sequence.html).

### 4.4 La résolution des arguments : d'où viennent `taskId` et `taskDto`

**Le mécanisme.** Avant d'appeler `getTask(Long taskId)`, l'adaptateur doit fabriquer `taskId`. Pour chaque
paramètre, il parcourt sa liste de **résolveurs d'arguments** (36 dans le projet) et prend le premier qui répond
"je sais faire" :

| Paramètre | Résolveur qui le prend | Ce qu'il fait |
|---|---|---|
| `@PathVariable Long taskId` | `PathVariableMethodArgumentResolver` | Extrait `"1"` du chemin, puis le convertit en `Long` |
| `@Valid @RequestBody TaskRequestDto taskDto` | `RequestResponseBodyMethodProcessor` | Choisit un `HttpMessageConverter` selon le `Content-Type` (4.5), lit le corps, puis valide si `@Valid` |
| Un objet sans annotation | `ServletModelAttributeMethodProcessor` | Dernier de la liste : remplit l'objet depuis les **paramètres de requête** (`?title=...`) |

La liste contient aussi les résolveurs de `@RequestParam`, `@RequestHeader`, `HttpServletRequest`, `Pageable` (ajouté
par Spring Data), `@AuthenticationPrincipal` (ajouté par Spring Security)... Chaque starter enrichit la liste.

`@PathVariable Long taskId` n'a pas besoin d'écrire `@PathVariable("taskId")` grâce à l'option `-parameters` du
compilateur (02, section 4.2) : le nom `taskId` est lu dans le bytecode.

**L'observer.** `GET /api/tasks/abc`, extrait réel :

```
o.s.web.method.HandlerMethod : Could not resolve parameter [0] in public org.springframework.http.ResponseEntity<...> com.personal.task_tracker.task.controller.TaskController.getTask(java.lang.Long): Method parameter 'taskId': Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input string: "abc"
.m.m.a.ExceptionHandlerExceptionResolver : Resolved [org.springframework.web.method.annotation.MethodArgumentTypeMismatchException: Method parameter 'taskId': Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input string: "abc"]
o.s.web.servlet.DispatcherServlet : Completed 400 BAD_REQUEST
```

Ta méthode n'a jamais été appelée : l'échec a lieu pendant la fabrication de ses arguments. C'est la réponse à la
question 2 du quiz.

**Ce qui se passe si on retire `@RequestBody`.** Expérience faite sur le projet (puis annulée) : sur
`createTask`, remplace `@Valid @RequestBody TaskRequestDto taskDto` par `@Valid TaskRequestDto taskDto`, et envoie un
JSON **complet et valide**. Résultat réel : 400 avec quatre erreurs (`task.title.required`, `task.status.required`,
`task.type.required`, `task.priority.required`). Sans l'annotation, le paramètre tombe dans le dernier résolveur de la
liste, qui ignore le corps et cherche `title`, `type`... dans les paramètres d'URL. Le JSON est perdu en silence ;
seule la validation te prévient.

**La source.** [Method Arguments](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/arguments.html),
[@RequestBody](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html).

### 4.5 `HttpMessageConverter` et Jackson : la négociation de contenu

**Le mécanisme.** Spring MVC ne parle pas JSON lui-même. Il délègue à une liste de `HttpMessageConverter`, chacun
sachant lire et/ou écrire certains types Java dans certains formats (types de média). Pour **lire** un corps, il
prend le premier convertisseur qui accepte le couple (type du paramètre, `Content-Type` reçu). Pour **écrire**, il
croise l'en-tête `Accept` du client avec ce que les convertisseurs savent produire : c'est la **négociation de
contenu**.

**L'observer.** La liste réelle du projet, dans l'ordre, avec pour chacun la réponse à "sais-tu lire
`TaskRequestDto` / écrire `TaskResponseDto` en `application/json` ?" :

| Convertisseur | Types de média | Lit / écrit nos DTO ? |
|---|---|---|
| `ProjectingJacksonHttpMessageConverter` (ajouté par Spring Data) | `application/json`, `application/*+json` | non / non (réservé aux projections Spring Data) |
| `ByteArrayHttpMessageConverter` | `application/octet-stream`, `*/*` | non / non |
| `StringHttpMessageConverter` | `text/plain`, `*/*` | non / non |
| `ResourceHttpMessageConverter`, `ResourceRegionHttpMessageConverter` | `*/*` | non / non |
| `AllEncompassingFormHttpMessageConverter` | formulaires, multipart | non / non |
| **`JacksonJsonHttpMessageConverter`** | `application/json`, `application/*+json` | **oui / oui** |
| `Jaxb2RootElementHttpMessageConverter` | `application/xml`, `text/xml` | non / non |

Seul `JacksonJsonHttpMessageConverter` fait le travail. Il utilise le bean `jacksonJsonMapper` (un `JsonMapper` de
Jackson 3), créé par `JacksonAutoConfiguration` avec `@ConditionalOnMissingBean` : si tu déclares ton propre
`JsonMapper`, Boot s'efface et c'est le tien qui sert.

Trois conséquences vérifiées par de vraies requêtes :

| Requête | Résultat réel | Pourquoi |
|---|---|---|
| `POST /api/tasks` avec un corps JSON mais `Content-Type: text/plain` | 415, `Content-Type 'text/plain;charset=ISO-8859-1' is not supported` | aucun convertisseur ne lit `TaskRequestDto` depuis `text/plain` |
| `GET /api/tasks` avec `Accept: application/xml` | 406, `"Acceptable representations: [application/json, application/*+json]."` | le convertisseur XML existe, mais ne sait pas écrire nos records |
| `POST /api/tasks` avec une propriété inconnue `"Description"` en plus | 201, la propriété est ignorée | dans ce projet, `FAIL_ON_UNKNOWN_PROPERTIES` vaut `false` sur le `JsonMapper` (valeur lue dans le contexte) |

La troisième ligne explique le premier piège de la section 6 : une faute de casse dans un nom de champ ne provoque
aucune erreur.

**La source.** [HTTP Message Conversion](https://docs.spring.io/spring-framework/reference/web/webmvc/message-converters.html),
[Spring Boot, HttpMessageConverters](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.message-converters),
[Spring Boot, JSON](https://docs.spring.io/spring-boot/reference/features/json.html).

### 4.6 Le choix du code HTTP

**Le mécanisme.** Après l'appel de ta méthode, l'adaptateur passe la valeur renvoyée à ses **gestionnaires de
valeur de retour** (15 dans le projet). Pour un `ResponseEntity`, c'est `HttpEntityMethodProcessor` (le logger
`o.s.w.s.m.m.a.HttpEntityMethodProcessor` des extraits précédents) : il prend le code et les en-têtes que tu as mis
dans l'objet, puis confie le corps au convertisseur choisi en 4.5. Si une exception sort de ta méthode (ou de la
résolution des arguments), c'est un `HandlerExceptionResolver` qui fixe le code : `ExceptionHandlerExceptionResolver`
cherche une méthode `@ExceptionHandler` dans `GlobalExceptionHandler`. Pour les exceptions propres à Spring MVC
(415, 405, 404, 400 de conversion), la méthode trouvée est `handleException`, héritée de
`ResponseEntityExceptionHandler`, qui connaît le code de chacune (voir les lignes `Using @ExceptionHandler ...` des
extraits).

**Ce qui se passe si on remplace `@RestController` par `@Controller`.** Expérience faite sur `TaskController`
(puis annulée) : `GET /api/tasks` répond toujours `200` en JSON. Surprenant ? Non : le gestionnaire de
`ResponseEntity` écrit le corps **quelle que soit** l'annotation de la classe. `@RestController` ajoute
`@ResponseBody` à toutes les méthodes, ce qui compte pour une méthode qui renverrait directement un DTO ; d'après la
documentation, sans lui, le retour serait interprété comme un nom de vue (une page HTML), ce que ce projet n'a pas.
Garde `@RestController` : il dit clairement que la classe ne renvoie que des données.

**La source.** [Return Values](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/return-types.html),
[ResponseEntity](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html),
[@ResponseBody](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responsebody.html),
[Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

### 4.7 Ce que crée l'auto-configuration web

**Le mécanisme.** Rien de ce qui précède n'est déclaré dans ton code : ce sont des beans créés par des classes
d'auto-configuration, listées dans les fichiers `AutoConfiguration.imports` des modules web de Boot 4 (le principe
est décrit en [02. Beans et injection](02-beans-injection.md), section 4.6). Pour le web, quatre modules :

| Module (jar) | Auto-configurations principales |
|---|---|
| `spring-boot-tomcat` | `TomcatServletWebServerAutoConfiguration` |
| `spring-boot-webmvc` | `DispatcherServletAutoConfiguration`, `WebMvcAutoConfiguration`, `ErrorMvcAutoConfiguration` |
| `spring-boot-http-converter` | `HttpMessageConvertersAutoConfiguration` |
| `spring-boot-jackson` | `JacksonAutoConfiguration` |

**L'observer.** Le test a demandé, pour chaque bean, quelle configuration l'a défini. Extrait réel :

```
dispatcherServlet              <- ...webmvc.autoconfigure.DispatcherServletAutoConfiguration$DispatcherServletConfiguration
dispatcherServletRegistration  <- ...webmvc.autoconfigure.DispatcherServletAutoConfiguration$DispatcherServletRegistrationConfiguration
requestMappingHandlerMapping   <- ...webmvc.autoconfigure.WebMvcAutoConfiguration$EnableWebMvcConfiguration
requestMappingHandlerAdapter   <- ...webmvc.autoconfigure.WebMvcAutoConfiguration$EnableWebMvcConfiguration
handlerExceptionResolver       <- ...webmvc.autoconfigure.WebMvcAutoConfiguration$EnableWebMvcConfiguration
basicErrorController           <- ...webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
jacksonJsonMapper              <- ...jackson.autoconfigure.JacksonAutoConfiguration
characterEncodingFilter        <- ...servlet.autoconfigure.HttpEncodingAutoConfiguration
tomcatServletWebServerFactory  <- ...tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
```

Et pourquoi elles ont été retenues (rapport des conditions, extrait réel, noms de package retirés) :

```
TomcatServletWebServerAutoConfiguration -> MATCH
    @ConditionalOnClass found required classes 'jakarta.servlet.ServletRequest', 'org.apache.catalina.startup.Tomcat', ...
WebMvcAutoConfiguration -> MATCH
    @ConditionalOnClass found required classes 'jakarta.servlet.Servlet', 'org.springframework.web.servlet.DispatcherServlet', ...
    @ConditionalOnMissingBean (types: org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport; SearchStrategy: all) did not find any beans
JacksonAutoConfiguration#jacksonJsonMapper -> MATCH
    @ConditionalOnMissingBean (types: tools.jackson.databind.json.JsonMapper; SearchStrategy: all) did not find any beans
```

La condition sur `WebMvcConfigurationSupport` est un piège classique : `@EnableWebMvc`, souvent copié depuis de vieux
tutoriels, déclare justement un bean de ce type. `WebMvcAutoConfiguration` s'efface alors, et avec elle les
réglages de Boot pour Spring MVC. Dans une application Spring Boot, on ne met pas `@EnableWebMvc`.

Pour voir ce rapport complet sur l'application, lance-la avec `--debug` (exercice 4 de la fiche 02) et cherche
`WebMvcAutoConfiguration`.

**La source.** [Spring MVC Auto-configuration](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.auto-configuration),
[Enable MVC Configuration](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/enable.html).

### 4.8 Le refaire toi-même : un mini Spring MVC en 70 lignes environ

Le JDK contient un petit serveur HTTP (`com.sun.net.httpserver`). Avec lui et un peu de réflexion, tu peux refaire
le squelette de 4.1 à 4.6. Crée ce fichier **hors du projet** et lance-le avec `java MiniMvc.java` :

```java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MiniMvc {

	@Retention(RetentionPolicy.RUNTIME)
	@interface Get {                                     // notre @GetMapping
		String value();
	}

	record TaskDto(long id, String title) {
	}

	static class TaskController {                        // un "controller" ordinaire
		@Get("/api/tasks/{id}")
		TaskDto getTask(Long id) {
			return new TaskDto(id, "Tâche " + id);
		}
	}

	// La table des routes, construite au démarrage (~ RequestMappingHandlerMapping)
	static final Map<String, Method> routes = new HashMap<>();
	static final Object controller = new TaskController();

	public static void main(String[] args) throws Exception {
		for (Method m : TaskController.class.getDeclaredMethods()) {
			if (m.isAnnotationPresent(Get.class)) {
				routes.put(m.getAnnotation(Get.class).value(), m);
				System.out.println("Mapped GET " + m.getAnnotation(Get.class).value() + " -> " + m.getName());
			}
		}
		HttpServer server = HttpServer.create(new InetSocketAddress(8099), 0);   // ~ Tomcat
		server.createContext("/", MiniMvc::dispatch);                            // ~ DispatcherServlet sur "/"
		server.start();
	}

	static void dispatch(HttpExchange exchange) throws java.io.IOException {
		String path = exchange.getRequestURI().getPath();                        // ex. /api/tasks/42
		String template = path.replaceAll("/\\d+$", "/{id}");                   // ~ correspondance de chemin
		Method handler = routes.get(template);
		int status;
		String body;
		if (handler == null || !exchange.getRequestMethod().equals("GET")) {
			status = 404;
			body = "{\"title\":\"Not Found\"}";
		} else {
			try {
				Long id = Long.valueOf(path.substring(path.lastIndexOf('/') + 1));   // ~ résolution d'argument + conversion
				TaskDto dto = (TaskDto) handler.invoke(controller, id);              // ~ HandlerAdapter
				status = 200;
				body = "{\"id\":" + dto.id() + ",\"title\":\"" + dto.title() + "\"}";   // ~ HttpMessageConverter
			} catch (Exception e) {
				status = 500;
				body = "{\"title\":\"Internal Server Error\"}";                       // ~ HandlerExceptionResolver
			}
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
```

Puis, dans un autre terminal : `curl -i localhost:8099/api/tasks/42` répond `200` avec
`{"id":42,"title":"Tâche 42"}`, et la console a affiché `Mapped GET /api/tasks/{id} -> getTask` au démarrage, comme
le log de 4.2.

Ce qui manque par rapport à Spring MVC, et que tu as vu dans cette section : plusieurs `HandlerMapping` et
adaptateurs (4.3), une liste extensible de résolveurs d'arguments (4.4), Jackson et la négociation de contenu (4.5),
les codes d'erreur par type d'exception (4.6).

**Défis :**

1. `curl -i localhost:8099/api/tasks/abc` répond 404. Spring, lui, répond 400 (4.4). Pourquoi cette différence, et
   comment obtenir 400 ?
2. Ajoute une seconde méthode annotée `@Get("/api/tasks/{id}")`. Que fait le mini-framework ? Que fait Spring (4.2) ?

<details><summary>Réponses</summary>

1. Le mini-framework ne reconnaît la route que si le dernier segment est fait de chiffres (`\\d+`) : `abc` ne
   correspond à aucune route, d'où 404. Spring associe d'abord la route avec un segment quelconque, puis échoue à la
   **conversion** de l'argument. Pour l'imiter : remplace `\\d+` par `[^/]+`, et attrape la
   `NumberFormatException` de `Long.valueOf` pour répondre 400.
2. `routes.put` écrase silencieusement la première méthode, et l'ordre de `getDeclaredMethods()` n'est pas garanti :
   tu ne sais même pas laquelle gagne. Spring refuse de démarrer avec "Ambiguous mapping" : mieux vaut une erreur
   au démarrage qu'un comportement au hasard.
</details>

---

## 5. À toi de jouer

Lance l'application depuis `server/` (`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`) et remplace
`<port>` par le port de ta configuration locale.

1. **Parcourir le CRUD à la main.**
   ```bash
   curl -i -X POST localhost:<port>/api/tasks -H 'Content-Type: application/json' \
     -d '{"title":"Ma tâche","type":"BUG","status":"BACKLOG","priority":"LOW"}'
   curl -i localhost:<port>/api/tasks
   curl -i -X PATCH localhost:<port>/api/tasks/<id> -H 'Content-Type: application/json' -d '{"status":"DONE"}'
   curl -i -X DELETE localhost:<port>/api/tasks/<id>
   ```
   **À observer** : 201 puis 200, 200, 204 ; l'en-tête `Content-Type: application/json` ; aucun corps après le
   `DELETE`.
2. **PUT vs PATCH.** Sur une tâche qui a une description, envoie `PATCH` avec `{"status":"READY"}`, puis `PUT` avec
   seulement `title`, `type`, `status`, `priority`. **À observer** : après le `PATCH`, la description est intacte ;
   après le `PUT`, elle a disparu.
3. **Provoquer les erreurs de Spring.** `GET /api/tasks/abc`, `POST /api/tasks/1`, `GET /api/inexistant`.
   **À observer** : 400, 405, 404, tous avec `Content-Type: application/problem+json` et une propriété `code`.
4. **Lire un test.** Lance seulement le test des 405 :
   `./mvnw test -Dtest=TaskControllerTest#shouldReturn405WithCodeWhenMethodIsNotSupported` (Docker doit tourner).
   **À observer** : la structure `given()` / `when()` / `then()` reprend exactement une requête HTTP.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Un champ envoyé arrive à `null` | Nom de propriété JSON différent du nom du composant du record (`Title`, `assignee_id`...) : la propriété inconnue est ignorée | Vérifier l'orthographe exacte des champs |
| 415 Unsupported Media Type | En-tête `Content-Type: application/json` absent, ou corps envoyé dans un autre format | `Content-Type: application/json` |
| 405 Method Not Allowed | Bon chemin, mauvais verbe | Vérifier l'annotation du controller |
| Un `PATCH` efface des champs non envoyés | DTO partiel avec des types simples au lieu de `JsonNullable` | Utiliser `JsonNullable` et `ifPresent` |
| 200 renvoyé après une création | `ResponseEntity.ok(...)` au lieu de `HttpStatus.CREATED` | Renvoyer 201 pour un `POST` qui crée |
| Un verbe dans l'URL (`/api/tasks/delete/1`) | Conception non REST | `DELETE /api/tasks/1` |
| Erreur au démarrage « Ambiguous mapping » | Deux méthodes avec le même verbe et le même chemin | Changer le chemin ou fusionner |
| 400 de validation sur tous les champs alors que le JSON envoyé est complet | `@RequestBody` oublié : le DTO est rempli depuis les paramètres d'URL (4.4) | Ajouter `@RequestBody` |
| Réglages Spring MVC de Boot ignorés | `@EnableWebMvc` ajouté : `WebMvcAutoConfiguration` s'efface (4.7) | Le retirer |

---

## 7. Pour aller plus loin

- **Pagination et filtres** ⏳ : `@RequestParam`, `Pageable` (Spring Data). `GET /api/tasks` renverra un jour trop
  de lignes pour une seule réponse.
- **CORS** ⏳ : nécessaire pour que le front `client/`, servi depuis une autre origine, puisse appeler l'API.
- **Documentation OpenAPI** ⏳ (springdoc) : génère le contrat de l'API et une page pour la tester.
- **En-tête `Location`** : après un 201, la convention REST propose d'indiquer l'URL de la ressource créée.
- **Versionnement d'API** (`/api/v1/...`) quand des clients existants ne peuvent pas suivre un changement.
- Documentation : en plus des liens de la section 4, *Spring Framework Reference*, « Web on Servlet Stack > Spring
  Web MVC > Annotated Controllers » ; RFC 9110 (sémantique HTTP) ; RFC 5789 (méthode PATCH) ; MDN, « HTTP response
  status codes ».

---

## 8. Quiz

**1. Quel code renvoyer après une suppression réussie, et pourquoi n'y a-t-il pas de corps ?**
<details><summary>Réponse</summary>

204 No Content : l'action a réussi et il n'y a rien à renvoyer, la ressource n'existe plus.
</details>

**2. `GET /api/tasks/abc` renvoie 400 alors que tu n'as écrit aucune vérification. Qui l'a détecté ?**
<details><summary>Réponse</summary>

Spring MVC, en essayant de convertir `"abc"` en `Long` pour le paramètre `@PathVariable Long taskId`.
</details>

**3. Pourquoi `TaskRequestPartialDto` utilise-t-il `JsonNullable<String>` au lieu de `String` ?**
<details><summary>Réponse</summary>

Pour distinguer un champ absent du JSON (ne pas modifier) d'un champ présent à `null` (effacer). Avec un `String`,
les deux arrivent à `null`.
</details>

**4. `POST` est-il idempotent ? Quelle conséquence si le client réessaie après une coupure réseau ?**
<details><summary>Réponse</summary>

Non. Si la première requête était arrivée, le réessai crée une deuxième tâche identique.
</details>

**5. Un client envoie un `PUT` sans `assigneeId`. Que devient l'assigné de la tâche ?**
<details><summary>Réponse</summary>

Il est retiré : `PUT` remplace la ressource entière, un champ absent vaut vide
(`TaskService.updateTask` met l'assigné à `null`, test `shouldUnassignTaskWhenPutHasNoAssignee`).
</details>
