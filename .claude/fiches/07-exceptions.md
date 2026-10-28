# 07. Exceptions et format d'erreur

**Ce que tu vas comprendre**
- Comment une exception levée dans un service devient une réponse HTTP avec le bon code de statut
- Le rôle de `@RestControllerAdvice` et de `@ExceptionHandler`
- Le format standard `ProblemDetail` (RFC 9457) et la propriété `code` ajoutée par le projet
- Pourquoi les erreurs de sécurité ont leur propre gestionnaire
- Comment traduire une erreur technique (contrainte en base) en erreur métier claire

**Prérequis** : [04. Architecture en couches](04-architecture-couches.md), [05. Web et REST](05-web-rest.md).
Utile avant : [06. Validation](06-validation.md), qui détaille les erreurs 400 de validation.

---

## 1. Le problème que ça résout

Imagine `GET /api/tasks/999` alors que la tâche 999 n'existe pas. Sans gestion d'erreur, deux mauvaises options :

**Option A : gérer l'erreur dans chaque contrôleur**

```java
@GetMapping("/{taskId}")
public ResponseEntity<?> getTask(@PathVariable Long taskId) {
	try {
		return new ResponseEntity<>(taskService.getTaskById(taskId), HttpStatus.OK);
	} catch (TaskNotFoundException e) {
		return new ResponseEntity<>(Map.of("error", e.getMessage()), HttpStatus.NOT_FOUND);
	}
}
```

Ce `try/catch` serait à recopier dans chaque méthode, chacun écrirait son propre format de réponse, et un oubli
suffirait à faire fuiter une erreur brute.

**Option B : ne rien faire**

L'exception remonte jusqu'à Spring, qui répond un 500 générique. Le client croit à une panne du serveur alors qu'il
a simplement demandé une tâche inexistante.

**Analogie** : un standard téléphonique. Chaque service de l'entreprise (les contrôleurs) ne gère pas lui-même les
appels qui tournent mal : tout problème est renvoyé au standard (le gestionnaire global), qui répond au client
toujours de la même manière, avec la bonne information.

La solution Spring : le code métier **lève** simplement une exception, et une classe unique la **traduit** en
réponse HTTP.

---

## 2. Les concepts

### 2.1 Exceptions vérifiées et non vérifiées

En Java, il existe deux familles d'exceptions :

| Famille | Hérite de | Obligation | Exemple |
|---|---|---|---|
| Vérifiée (*checked*) | `Exception` | Déclarer `throws` ou attraper | `IOException` |
| Non vérifiée (*unchecked*) | `RuntimeException` | Aucune | `IllegalArgumentException` |

Les exceptions métier d'une application Spring sont presque toujours **non vérifiées** : elles traversent les
couches (repository, service, contrôleur) sans polluer chaque signature avec des `throws`. Autre raison : par
défaut, `@Transactional` n'annule la transaction que pour une `RuntimeException` (voir
[09. Transactions](09-transactions.md)).

```java
public class TaskNotFoundException extends RuntimeException {
	public TaskNotFoundException(String message) {
		super(message);
	}
}
```

### 2.2 Lever au plus près, traduire au plus loin

Le principe : **celui qui détecte le problème lève l'exception**, sans se soucier de HTTP. Le service ne sait pas
qu'il existe un code 404, il dit seulement « cette tâche n'existe pas ».

```java
repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + taskId));
```

C'est la couche web qui décide que « tâche introuvable » se dit `404` en HTTP. Ce découpage garde le service
réutilisable : le même service pourrait servir un traitement en ligne de commande, sans HTTP du tout.

### 2.3 `@RestControllerAdvice` et `@ExceptionHandler`

Un **advice** est une classe qui s'applique à **tous** les contrôleurs. `@RestControllerAdvice` est la version qui
écrit le retour en JSON.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(TaskNotFoundException.class)
	public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}
}
```

Quand une exception sort d'un contrôleur, Spring cherche la méthode `@ExceptionHandler` dont le type correspond le
mieux. **Le plus spécifique gagne** : si tu as un handler pour `TaskNotFoundException` et un autre pour
`Exception`, une `TaskNotFoundException` ira dans le premier. Le handler sur `Exception` sert de filet de sécurité
pour tout le reste.

### 2.4 `ProblemDetail` : un format d'erreur standard

Plutôt que d'inventer un format (`{"error": "..."}`, `{"message": "..."}`...), Spring propose `ProblemDetail`, qui
suit la RFC 9457 « Problem Details for HTTP APIs ». La réponse a le type de contenu `application/problem+json` et
ces champs :

| Champ | Sens | Exemple |
|---|---|---|
| `type` | URI qui identifie le type d'erreur (`about:blank` par défaut) | `about:blank` |
| `title` | Résumé court, par défaut le libellé du statut | `Not Found` |
| `status` | Code HTTP | `404` |
| `detail` | Explication lisible de **ce** cas | `There is no task with id: 999` |
| `instance` | L'URI de la requête concernée | `/api/tasks/999` |

On peut ajouter ses propres propriétés avec `setProperty`. Le projet en ajoute une : `code`.

### 2.5 Pourquoi un `code` en plus du `detail` ?

`detail` est une phrase en anglais, faite pour un humain. Le front, lui, a besoin d'une **clé stable** pour :
- afficher un message traduit (« Tâche introuvable ») ;
- réagir différemment selon l'erreur (proposer « mot de passe oublié » sur `user.email.alreadyExists`).

Comparer des phrases serait fragile : la moindre reformulation casserait le front. La clé `task.notFound` ne change
pas.

### 2.6 `ResponseEntityExceptionHandler` : les erreurs de Spring MVC

Spring MVC lève lui-même des exceptions avant ton code : JSON illisible, méthode HTTP non supportée, paramètre du
mauvais type, route inconnue... La classe `ResponseEntityExceptionHandler` les gère déjà toutes et répond avec un
`ProblemDetail`. En **héritant** d'elle, tu récupères ce comportement et tu peux **surcharger** une méthode précise
(`handleMethodArgumentNotValid`, `handleHttpMessageNotReadable`...) pour la personnaliser.

### 2.7 Filtres et contrôleurs : deux mondes

Rappel du trajet d'une requête ([04. Architecture en couches](04-architecture-couches.md)) :

```
Requête ──► Filtres (Spring Security) ──► DispatcherServlet ──► Contrôleur ──► Service
                    │                                                │
         erreur ici : l'advice                          erreur ici : GlobalExceptionHandler
         ne la voit PAS                                 la traduit
```

`@RestControllerAdvice` ne fonctionne que pour les exceptions levées **à partir du DispatcherServlet**. Une erreur
dans un filtre de sécurité (token absent, accès refusé) se produit **avant** : il faut un autre mécanisme
(`AuthenticationEntryPoint` et `AccessDeniedHandler`) pour écrire la réponse.

---

## 3. Dans le projet

### Les exceptions métier

`server/src/main/java/com/personal/task_tracker/task/exception/TaskNotFoundException.java:3` : la forme la plus
simple, une `RuntimeException` avec un message.

`server/src/main/java/com/personal/task_tracker/user/exception/UserAlreadyExistsException.java` va plus loin :

```java
public class UserAlreadyExistsException extends RuntimeException {

	private final String code;

	private UserAlreadyExistsException(String code, String message, @Nullable Throwable cause) {   // (1)
		super(message, cause);
		this.code = code;
	}

	public static UserAlreadyExistsException username() {                                          // (2)
		return username(null);
	}

	public static UserAlreadyExistsException username(@Nullable Throwable cause) {                 // (3)
		return new UserAlreadyExistsException("user.username.alreadyExists", "Username already exists", cause);
	}
	// ... même chose pour email()
}
```

1. **Constructeur privé** : impossible de créer l'exception avec un code inventé.
2. **Méthodes de fabrique** nommées : `UserAlreadyExistsException.username()` se lit comme une phrase, et le code
   ne peut pas être mal orthographié.
3. **Cause chaînée** : quand l'erreur vient d'une autre exception (la contrainte en base), on la garde. Elle
   apparaîtra dans les logs pour le débogage, sans être envoyée au client.

Une seule classe porte deux cas (username ou email) : le `code` les distingue, et le handler n'a qu'à le recopier.

### Où elles sont levées

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java:147` :

```java
return repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + taskId));
```

`findById` renvoie un `Optional`. `orElseThrow` lève l'exception si la tâche n'existe pas. Pas de `if`, pas de
`null`.

### Traduire une erreur technique en erreur métier

`server/src/main/java/com/personal/task_tracker/user/service/UserService.java:71` à `:92` :

```java
if (repository.existsByUsernameIgnoreCase(username)) {
	throw UserAlreadyExistsException.username();                                       // (1)
}
if (repository.existsByEmail(email)) {
	throw UserAlreadyExistsException.email();
}
// ...
try {
	return repository.saveAndFlush(user);
} catch (DataIntegrityViolationException exception) {                                   // (2)
	String constraintName = exception.getCause() instanceof ConstraintViolationException cve ? cve.getConstraintName() : null;
	if (USERNAME_UNIQUE_INDEX.equals(constraintName)) {
		throw UserAlreadyExistsException.username(exception);                            // (3)
	}
	if (EMAIL_UNIQUE_CONSTRAINT.equals(constraintName)) {
		throw UserAlreadyExistsException.email(exception);
	}
	throw exception;                                                                     // (4)
}
```

1. **Vérification préalable** : le cas courant, avec un message clair.
2. **Le cas rare** : deux inscriptions simultanées avec le même username passent toutes les deux la vérification,
   puis la seconde se heurte à l'index unique en base. Spring traduit l'erreur SQL en
   `DataIntegrityViolationException`.
3. On reconnaît la contrainte à son **nom** (défini dans la migration Flyway `V1`) et on lève la même exception
   métier qu'au point 1, en gardant la cause.
4. Une **autre** contrainte violée n'est pas un doublon : on relance l'exception telle quelle. Elle finira en 500,
   car c'est un vrai bug.

Le `saveAndFlush` (et pas `save`) force l'envoi du SQL **à l'intérieur** du `try`. Sans flush, l'INSERT pourrait
partir plus tard, en fin de transaction, hors du `try/catch` (voir [08. Persistance](08-persistance.md)).

### Le gestionnaire global

`server/src/main/java/com/personal/task_tracker/commons/exception/GlobalExceptionHandler.java:34` :

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
```

Il hérite de `ResponseEntityExceptionHandler` pour récupérer la gestion des erreurs de Spring MVC. Ses méthodes sont
rangées par thème : requêtes, sécurité, utilisateurs, tâches, inattendu.

**Les erreurs métier** (`:199` à `:214`) :

```java
@ExceptionHandler(UserAlreadyExistsException.class)
public ProblemDetail handleUserAlreadyExists(UserAlreadyExistsException ex) {
	return problem(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());          // 409, code porté par l'exception
}

@ExceptionHandler(TaskNotFoundException.class)
public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {
	return problem(HttpStatus.NOT_FOUND, "task.notFound", ex.getMessage());      // 404
}
```

Toutes passent par la même méthode utilitaire (`:224`), qui garantit la présence du `code` :

```java
private static ProblemDetail problem(HttpStatus status, String code, String detail) {
	ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
	problem.setProperty(CODE, code);
	return problem;
}
```

Réponse obtenue pour `GET /api/tasks/999` :

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "There is no task with id: 999",
  "instance": "/api/tasks/999",
  "code": "task.notFound"
}
```

**Le filet de sécurité** (`:218`) :

```java
@ExceptionHandler(Exception.class)
public ProblemDetail handleUnexpected(Exception ex) {
	logger.error("Unexpected error", ex);                                          // (1)
	return problem(HttpStatus.INTERNAL_SERVER_ERROR, "server.internalError", "An unexpected error occurred");  // (2)
}
```

1. L'erreur complète, avec sa stack trace, va dans les **logs** du serveur. `logger` est hérité de
   `ResponseEntityExceptionHandler`.
2. Le client reçoit un message **générique**. Jamais le message interne : il pourrait révéler un nom de table, une
   requête SQL ou un chemin de fichier.

**Les erreurs de Spring MVC** : `handleExceptionInternal` (`:143`) est appelée pour toutes les erreurs que gère la
classe mère. Elle ajoute un `code` construit à partir du statut quand il n'y en a pas déjà un : un 405 donne
`request.methodNotAllowed`, un 404 sur une route inconnue `request.notFound`. Ainsi **toutes** les réponses d'erreur
ont un `code`, même celles que le projet n'a pas prévues.

**Les erreurs de lecture du JSON** : `handleHttpMessageNotReadable` (`:89`) distingue deux cas :
- une valeur que Jackson ne sait pas convertir (du texte pour un nombre, une valeur d'enum inconnue) devient une
  erreur de champ `task.type.invalidFormat`, au même format qu'une erreur de validation ;
- un JSON mal formé (accolade manquante) n'a pas de champ : la réponse est un 400 `request.invalidBody`.

Les erreurs de validation (`handleMethodArgumentNotValid`, `:50`) sont détaillées dans
[06. Validation](06-validation.md).

### Les erreurs de sécurité, des deux côtés

Dans un **contrôleur**, une `AuthenticationException` ou une `AccessDeniedException` passe par
`GlobalExceptionHandler` (`:176` et `:184`). Sans ces handlers, elle finirait dans `handleUnexpected`, en 500.

Dans un **filtre**, l'advice ne voit rien. C'est
`server/src/main/java/com/personal/task_tracker/commons/exception/SecurityProblemHandler.java` qui prend le relais :
il implémente `AuthenticationEntryPoint` (le 401) et `AccessDeniedHandler` (le 403), et il est branché dans
`SecurityConfig`. Pour produire **exactement** le même corps, il réutilise les méthodes
`GlobalExceptionHandler.unauthorized()` et `forbidden()` (`:189` et `:193`), puis écrit le JSON lui-même :

```java
private static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
		throws IOException {
	problem.setInstance(URI.create(request.getRequestURI()));                    // Spring MVC ne le fera pas ici
	response.setStatus(problem.getStatus());
	response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
	MAPPER.writeValue(response.getOutputStream(), problem);
}
```

Dans un filtre, on est avant Spring MVC : il faut faire à la main ce que Spring fait d'habitude (statut, type de
contenu, sérialisation, `instance`).

### Les tests qui verrouillent le comportement

`server/src/test/java/com/personal/task_tracker/auth/AuthControllerTest.java` :

- `:218` `shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex` simule l'inscription simultanée. Le spy du
  repository fait croire que le username est libre (`doReturn(false)`), l'INSERT se heurte à l'index, et le test
  vérifie qu'on obtient bien un 409 et non un 500.
- `:257` `shouldReturn500WhenAnotherConstraintIsViolated` vérifie le point (4) : une autre contrainte donne un 500.
- `:274` `shouldReturn500WithoutInternalMessageWhenUnexpectedErrorOccurs` vérifie que le message interne ne fuit
  pas.

`server/src/test/java/com/personal/task_tracker/commons/exception/SecurityErrorsTest.java` teste les deux côtés de
la sécurité : `:27` et `:39` via un contrôleur de test qui lève les exceptions
(`SecurityErrorsTestController`), `:53` et `:63` en appelant directement `SecurityProblemHandler`.

---

## 4. À toi de jouer

**Exercice 1 : observer les formats d'erreur.** Lance l'application et appelle :

```bash
curl -i localhost:<port>/api/tasks/999
curl -i -X DELETE localhost:<port>/api/users/1
curl -i localhost:<port>/api/route-inconnue
curl -i -X POST localhost:<port>/api/tasks -H 'Content-Type: application/json' -d '{"title":'
```

À observer : le statut de chaque réponse, le `Content-Type: application/problem+json`, et le `code`. Le deuxième
appel donne un 405 `request.methodNotAllowed` (aucune méthode `DELETE` dans `UserController`), le troisième un 404
`request.notFound`, le quatrième un 400 `request.invalidBody`. Aucun de ces trois codes n'est écrit en toutes
lettres dans le projet : ils sont fabriqués par `handleExceptionInternal`.

**Exercice 2 : retirer un handler.** Commente le handler `handleTaskNotFound` (`GlobalExceptionHandler.java:211`),
relance et rappelle `GET /api/tasks/999`. À observer : un 500 `server.internalError`, et la stack trace de
`TaskNotFoundException` dans les logs. Le filet de sécurité a attrapé l'exception. Lance ensuite les tests :
`TaskControllerTest.shouldReturn404WhenTaskNotFound` (`:445`) échoue, comme les autres tests qui attendent
`task.notFound`. Remets le handler.

**Exercice 3 : créer une exception métier.** Sur papier ou sur une branche d'essai : imagine la règle « on ne peut
pas supprimer une tâche au statut `DONE` ». Quelle exception créer, dans quel package ? Qui la lève ? Quel statut
HTTP et quel `code` dans le handler ? (Une piste en réponse : `TaskNotDeletableException` dans `task/exception/`,
levée par `TaskService.deleteTask`, 409 ou 422 avec un code `task.notDeletable`.)

**Exercice 4 : suivre une cause chaînée.** Dans `AuthControllerTest`, lance seul le test
`shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex` en mode debug, avec un point d'arrêt dans
`handleUserAlreadyExists`. À observer : `ex.getCause()` contient la `DataIntegrityViolationException` d'origine,
mais la réponse au client n'en dit rien.

---

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Une erreur métier renvoie 500 | Aucun `@ExceptionHandler` pour ce type : le filet `Exception` l'attrape | Ajouter un handler avec le bon statut |
| Un 401/403 au corps vide ou au mauvais format | Erreur levée dans un filtre : l'advice ne la voit pas | Brancher un `AuthenticationEntryPoint` / `AccessDeniedHandler` (ici `SecurityProblemHandler`) |
| Le message SQL ou une stack trace apparaît chez le client | `ex.getMessage()` d'une exception technique renvoyé tel quel | Message générique côté client, détail dans les logs |
| Une exception vérifiée ne déclenche pas le rollback | `@Transactional` n'annule par défaut que sur `RuntimeException` | Hériter de `RuntimeException`, ou `rollbackFor` |
| Un `try/catch` autour de `save` n'attrape jamais l'erreur de contrainte | Le SQL part au flush, en fin de transaction, hors du `try` | `saveAndFlush`, comme dans `UserService.createUser` |
| L'erreur est avalée, le client reçoit 200 | `catch (Exception e) { }` vide quelque part | Ne jamais attraper sans relancer ou traiter |
| Deux handlers pour des types parents et enfants, le mauvais est appelé | Confusion sur la règle « le plus spécifique gagne » | Vérifier la hiérarchie des exceptions |

---

## 6. Pour aller plus loin

- **Logging structuré** ⏳ : aujourd'hui seul `handleUnexpected` logue. En production on logue aussi les erreurs
  métier fréquentes au niveau `WARN`, avec un identifiant de requête pour retrouver le contexte.
- **Champ `type` personnalisé** : la RFC prévoit une URI par type d'erreur, qui peut pointer vers une page de
  documentation. Le projet garde `about:blank` et met la clé dans `code`.
- **Internationalisation** ⏳ : les `code` sont prêts pour une traduction côté front, ou côté serveur avec
  `MessageSource` et l'en-tête `Accept-Language`.
- **422 Unprocessable Content** : statut parfois préféré à 400 ou 409 pour une règle métier violée sur une requête
  bien formée.
- **Documentation officielle** : Spring Framework Reference, sections « Exceptions » et « Error Responses » du
  chapitre Web MVC ; Spring Security Reference, « Handling Security Exceptions » ; RFC 9457 « Problem Details for
  HTTP APIs ».

---

## 7. Quiz

**1. Pourquoi les exceptions métier héritent-elles de `RuntimeException` ?**

<details><summary>Réponse</summary>

Pour traverser les couches sans `throws` dans chaque signature, et parce que `@Transactional` n'annule la
transaction par défaut que pour les exceptions non vérifiées.

</details>

**2. Le service lève `TaskNotFoundException`. Qui décide que la réponse est un 404 ?**

<details><summary>Réponse</summary>

`GlobalExceptionHandler.handleTaskNotFound`. Le service ne connaît pas HTTP : il signale un fait métier, la couche
web le traduit.

</details>

**3. Pourquoi ajouter `code` alors que `ProblemDetail` a déjà `detail` ?**

<details><summary>Réponse</summary>

`detail` est une phrase pour un humain, susceptible de changer. `code` est une clé stable que le front utilise pour
traduire le message et réagir selon l'erreur.

</details>

**4. Un token invalide est rejeté par un filtre de sécurité. Pourquoi `GlobalExceptionHandler` ne voit-il rien ?**

<details><summary>Réponse</summary>

Les filtres s'exécutent avant le DispatcherServlet, alors que `@RestControllerAdvice` ne s'applique qu'aux
exceptions levées à partir de là. C'est `SecurityProblemHandler`, branché dans `SecurityConfig`, qui écrit la
réponse.

</details>

**5. Dans `UserService.createUser`, pourquoi relancer l'exception quand la contrainte n'est ni l'index du username
ni la contrainte de l'email ?**

<details><summary>Réponse</summary>

Parce que ce n'est pas un doublon : c'est une erreur imprévue, donc un bug. La déguiser en 409 cacherait le
problème. Relancée, elle finit en 500 et apparaît dans les logs.

</details>

**6. Que reçoit le client si une `NullPointerException` survient dans un service ?**

<details><summary>Réponse</summary>

Un 500 avec `code` = `server.internalError` et `detail` = « An unexpected error occurred ». La stack trace part
uniquement dans les logs du serveur.

</details>
