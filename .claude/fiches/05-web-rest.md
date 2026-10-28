# 05. Web et REST

**Ce que tu vas comprendre**
- Ce qu'est une API REST et comment les verbes HTTP s'associent aux actions
- Comment Spring MVC relie une URL à une méthode Java (`@GetMapping`, `@PathVariable`, `@RequestBody`)
- Comment choisir et renvoyer le bon code de statut avec `ResponseEntity`
- Comment Jackson convertit JSON ↔ Java
- Pourquoi `PATCH` a besoin de `JsonNullable` alors que `PUT` non

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

## 4. À toi de jouer

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

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Un champ envoyé arrive à `null` | Nom de propriété JSON différent du nom du composant du record (`Title`, `assignee_id`...) : la propriété inconnue est ignorée | Vérifier l'orthographe exacte des champs |
| 415 Unsupported Media Type | En-tête `Content-Type: application/json` absent, ou corps envoyé dans un autre format | `Content-Type: application/json` |
| 405 Method Not Allowed | Bon chemin, mauvais verbe | Vérifier l'annotation du controller |
| Un `PATCH` efface des champs non envoyés | DTO partiel avec des types simples au lieu de `JsonNullable` | Utiliser `JsonNullable` et `ifPresent` |
| 200 renvoyé après une création | `ResponseEntity.ok(...)` au lieu de `HttpStatus.CREATED` | Renvoyer 201 pour un `POST` qui crée |
| Un verbe dans l'URL (`/api/tasks/delete/1`) | Conception non REST | `DELETE /api/tasks/1` |
| Erreur au démarrage « Ambiguous mapping » | Deux méthodes avec le même verbe et le même chemin | Changer le chemin ou fusionner |

---

## 6. Pour aller plus loin

- **Pagination et filtres** ⏳ : `@RequestParam`, `Pageable` (Spring Data). `GET /api/tasks` renverra un jour trop
  de lignes pour une seule réponse.
- **CORS** ⏳ : nécessaire pour que le front `client/`, servi depuis une autre origine, puisse appeler l'API.
- **Documentation OpenAPI** ⏳ (springdoc) : génère le contrat de l'API et une page pour la tester.
- **En-tête `Location`** : après un 201, la convention REST propose d'indiquer l'URL de la ressource créée.
- **Versionnement d'API** (`/api/v1/...`) quand des clients existants ne peuvent pas suivre un changement.
- Documentation : *Spring Framework Reference*, « Web on Servlet Stack > Spring Web MVC > Annotated Controllers » ;
  RFC 9110 (sémantique HTTP) ; RFC 5789 (méthode PATCH) ; MDN, « HTTP response status codes ».

---

## 7. Quiz

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
