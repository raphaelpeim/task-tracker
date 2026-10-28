# 12. Java moderne et qualité de code

**Ce que tu vas comprendre**
- Les records, et pourquoi ils sont parfaits pour les DTO mais pas pour les entités
- Les lambdas, références de méthode et streams, qui remplacent la plupart des boucles du projet
- `Optional` et les annotations de nullabilité, pour traiter l'absence de valeur
- Le *pattern matching* (`instanceof` avec variable), les collections immuables, `getFirst()`
- Quelques réflexes de qualité : immutabilité, `Locale.ROOT`, Javadoc, classes utilitaires

**Prérequis** : aucun, cette fiche peut se lire à tout moment. Elle éclaire le code cité dans toutes les autres,
en particulier [04. Architecture en couches](04-architecture-couches.md).

---

## 1. Le problème que ça résout

Le projet tourne sous **Java 25** (`<java.version>25</java.version>` dans `server/pom.xml`). Si tu as appris Java
avec des versions plus anciennes (Java 8 ou avant), une partie du code peut te sembler étrange : pas de getters dans
les DTO, des `::` partout, des `instanceof` qui déclarent une variable...

Ces ajouts successifs du langage ont un même but : **dire plus avec moins de code**, et rendre certaines erreurs
impossibles. Comparaison avec un DTO de réponse écrit « à l'ancienne » :

```java
public final class TaskResponseDto {
	private final Long id;
	private final String title;
	// ... 7 autres champs

	public TaskResponseDto(Long id, String title /* , ... */) {
		this.id = id;
		this.title = title;
	}

	public Long getId() { return id; }
	public String getTitle() { return title; }
	// ... 7 autres getters

	@Override public boolean equals(Object o) { /* 15 lignes */ }
	@Override public int hashCode() { /* ... */ }
	@Override public String toString() { /* ... */ }
}
```

Environ 80 lignes, où une erreur peut se glisser partout (un champ oublié dans `equals`). La version du projet en
fait 12 (`server/src/main/java/com/personal/task_tracker/task/dto/TaskResponseDto.java`).

**Analogie** : passer d'un formulaire papier à remplir en entier à chaque fois à un formulaire pré-rempli. Tu ne
saisis plus que ce qui est propre à ton cas.

---

## 2. Les concepts

### 2.1 Les records (Java 16)

Un `record` déclare une classe **immuable** porteuse de données. À partir de la seule liste des composants, Java
génère :
- les champs `private final` ;
- le constructeur avec tous les composants ;
- un accesseur par composant, **sans** préfixe `get` : `dto.title()`, pas `dto.getTitle()` ;
- `equals`, `hashCode` et `toString`.

```java
public record Point(int x, int y) { }

Point p = new Point(1, 2);
p.x();                            // 1
p.equals(new Point(1, 2));        // true : comparaison par valeur
```

On peut ajouter des méthodes, ou **redéfinir** une méthode générée (comme `toString`). On ne peut pas ajouter de
champ modifiable.

**Pourquoi pas pour une entité JPA ?** Hibernate a besoin d'un constructeur sans argument, de champs modifiables
(il remplit l'objet après l'avoir créé, puis suit ses modifications) et d'une classe qu'il peut étendre pour le
chargement LAZY. Un record est immuable et `final` : il ne remplit aucune de ces conditions.

### 2.2 Lambdas et références de méthode (Java 8)

Une **lambda** est une fonction anonyme qu'on passe en paramètre :

```java
() -> new TaskNotFoundException("There is no task with id: " + taskId)   // aucun paramètre
(current, other) -> priority(other) < priority(current) ? other : current // deux paramètres
```

Une **référence de méthode** (`::`) est un raccourci quand la lambda ne fait qu'appeler une méthode existante :

| Lambda | Référence de méthode | Sorte |
|---|---|---|
| `task -> TaskMapper.toTaskResponseDto(task)` | `TaskMapper::toTaskResponseDto` | méthode statique |
| `value -> task.setTitle(value)` | `task::setTitle` | méthode d'un objet précis |
| `configurer -> configurer.disable()` | `AbstractHttpConfigurer::disable` | méthode de l'objet reçu |

### 2.3 Les streams (Java 8, `toList()` en Java 16)

Un stream décrit une **chaîne de transformations** sur une collection, au lieu d'une boucle :

```java
// Boucle classique
List<TaskResponseDto> result = new ArrayList<>();
for (Task task : tasks) {
	result.add(TaskMapper.toTaskResponseDto(task));
}

// Stream
List<TaskResponseDto> result = tasks.stream().map(TaskMapper::toTaskResponseDto).toList();
```

Les opérations courantes : `map` (transformer), `filter` (garder), `sorted`, puis une opération finale : `toList()`,
`count()`, `findFirst()`, `anyMatch(...)`. `toList()` renvoie une liste **non modifiable**.

### 2.4 `Optional` (Java 8)

`Optional<T>` est une boîte qui contient une valeur ou rien. Elle oblige l'appelant à **traiter l'absence**, là où
un `null` passerait inaperçu jusqu'à la `NullPointerException`.

```java
Optional<Task> found = repository.findById(taskId);

found.orElseThrow(() -> new TaskNotFoundException("..."));  // la valeur, ou une exception
found.orElse(defaultTask);                                    // la valeur, ou une valeur par défaut
found.map(TaskMapper::toTaskResponseDto);                     // transformer si présent
found.ifPresent(task -> log(task));                           // agir si présent
```

À éviter : `found.get()` sans vérifier, qui revient à ignorer l'absence. Et `Optional` sert de **type de retour**,
pas de type de champ ni de paramètre.

### 2.5 Le pattern matching pour `instanceof` (Java 16)

Avant, tester un type puis l'utiliser demandait un cast :

```java
if (cause instanceof ConstraintViolationException) {
	ConstraintViolationException cve = (ConstraintViolationException) cause;
	name = cve.getConstraintName();
}
```

Maintenant, `instanceof` déclare directement la variable typée, utilisable si le test réussit :

```java
if (cause instanceof ConstraintViolationException cve) {
	name = cve.getConstraintName();
}
```

### 2.6 Collections immuables et collections ordonnées

- `List.of(...)`, `Map.of(...)` (Java 9) créent des collections **non modifiables** : `add` lève une exception, et
  elles refusent les `null`. Idéal pour des constantes.
- `getFirst()` / `getLast()` (Java 21) sur une `List` remplacent `list.get(0)` et `list.get(list.size() - 1)`.

### 2.7 Nullabilité : jspecify

Java ne dit pas dans les types si une valeur peut être `null`. La bibliothèque **jspecify** ajoute des annotations
pour le documenter :
- `@Nullable` : la valeur peut être `null`, l'appelant doit le vérifier ;
- `@NonNull` : la valeur n'est jamais `null`.

Ces annotations n'ont **aucun effet à l'exécution** : elles servent à l'IDE et aux outils d'analyse, qui signalent un
déréférencement risqué. Ne pas les confondre avec `@NotNull` de Bean Validation, qui, lui, est vérifié à l'exécution
par `@Valid` (voir [06. Validation](06-validation.md)).

### 2.8 Réflexes de qualité

- **Immutabilité** : un champ `final` ne peut être réaffecté. Les beans Spring sont partagés entre toutes les
  requêtes simultanées : sans état modifiable, pas de mélange entre deux utilisateurs.
- **`Locale.ROOT`** : `toLowerCase()` sans argument dépend de la langue de la machine. En turc, `"I".toLowerCase()`
  donne `"ı"` (i sans point). Pour du texte technique (email, clé), toujours `toLowerCase(Locale.ROOT)`.
- **Classe utilitaire** : uniquement des méthodes statiques, avec un constructeur **privé** pour empêcher toute
  instanciation.
- **Javadoc** : décrire le contrat d'une méthode (ce qu'elle reçoit, renvoie, lève), pas répéter son code.

---

## 3. Dans le projet

### Des records partout pour les DTO

`server/src/main/java/com/personal/task_tracker/task/dto/TaskResponseDto.java:10` :

```java
public record TaskResponseDto(
		Long id,
		String title,
		String description,
		TaskType type,
		TaskStatus status,
		TaskPriority priority,
		UserResponseDto assignee,       // un record peut contenir un autre record
		LocalDateTime createdDate,
		LocalDateTime updatedDate
) {
}
```

Jackson sait sérialiser un record directement : chaque composant devient une clé JSON.

### Redéfinir le `toString` généré pour cacher un secret

`server/src/main/java/com/personal/task_tracker/auth/dto/RegisterRequestDto.java:33` :

```java
@Override
public @NonNull String toString() {
	return "RegisterRequestDto[firstname=" + firstname
			+ ", lastname=" + lastname
			+ ", username=" + username
			+ ", email=" + email
			+ ", password=***]";
}
```

Le `toString` généré afficherait le mot de passe en clair. Or un objet finit souvent dans un log (message d'erreur,
débogage). Redéfinir `toString` garantit qu'il ne fuit jamais par ce chemin.

### Les entités, elles, sont des classes classiques

`server/src/main/java/com/personal/task_tracker/task/entity/Task.java` : constructeur `protected Task()` vide pour
Hibernate, champs modifiables, getters et setters. C'est la contrepartie du point 2.1.

### Streams et références de méthode

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java:36` :

```java
return repository.findAll(Sort.by("id")).stream().map(TaskMapper::toTaskResponseDto).toList();
```

Lecture : « toutes les tâches triées par id, chacune convertie en DTO, dans une liste ». Même schéma dans
`UserService.java:42`.

`server/src/main/java/com/personal/task_tracker/commons/config/SecurityConfig.java:18` :

```java
.csrf(AbstractHttpConfigurer::disable)
```

`csrf(...)` attend une fonction qui reçoit le configurateur CSRF. `AbstractHttpConfigurer::disable` veut dire
« appelle `disable()` sur l'objet reçu ».

### Une lambda à deux paramètres

`server/src/main/java/com/personal/task_tracker/commons/exception/GlobalExceptionHandler.java:57` à `:61` :

```java
Map<String, FieldError> fieldErrors = new LinkedHashMap<>();
for (FieldError error : ex.getBindingResult().getFieldErrors()) {
	fieldErrors.merge(error.getField(), error, (current, other) -> priority(other) < priority(current) ? other : current);
}
List<Map<String, String>> errors = fieldErrors.values().stream().map(GlobalExceptionHandler::toError).toList();
```

`Map.merge(clé, valeur, fonction)` : si la clé est absente, elle stocke la valeur ; sinon, elle appelle la fonction
avec l'ancienne et la nouvelle valeur, et garde le résultat. Ici : une seule erreur par champ, la plus prioritaire.
`LinkedHashMap` (et non `HashMap`) conserve l'ordre d'insertion, donc l'ordre des champs dans la réponse est stable.

Remarque : une boucle `for` classique côtoie le stream. Les streams ne remplacent pas toutes les boucles ; on choisit
ce qui se lit le mieux.

### `Optional` et `JsonNullable`

`TaskService.java:147` :

```java
return repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException("There is no task with id: " + taskId));
```

`server/src/main/java/com/personal/task_tracker/task/mapper/TaskMapper.java:48` :

```java
taskDto.title().ifPresent(task::setTitle);
```

`JsonNullable` (bibliothèque `jackson-databind-nullable`) a une API calquée sur `Optional`, avec une différence
clé : il distingue « champ absent du JSON » (rien à faire) de « champ présent avec la valeur `null` ». Avec
`{"description": null}`, `ifPresent` **appelle** `setDescription(null)` et efface la description. Un `Optional`
ne saurait pas faire cette distinction (voir [05. Web et REST](05-web-rest.md) pour PUT et PATCH).

### Pattern matching

`server/src/main/java/com/personal/task_tracker/user/service/UserService.java:84` :

```java
String constraintName = exception.getCause() instanceof ConstraintViolationException cve ? cve.getConstraintName() : null;
```

Test de type, variable `cve` et opérateur ternaire sur une seule ligne. Autres exemples :
`GlobalExceptionHandler.java:113` (avec une négation : `!(... instanceof MismatchedInputException cause)`, la
variable `cause` reste utilisable **après** le `if`, puisque le `if` sort de la méthode), `:118` et `:151`.

### Collections immuables et `getFirst()`

`GlobalExceptionHandler.java:38` à `:45` :

```java
private static final List<String> CONSTRAINT_PRIORITY = List.of("NotNull", "NotBlank", "Size", "MaxBytes", "Email");
static final Map<Class<?>, String> DOMAINS = Map.of(
		TaskRequestDto.class, "task",
		TaskRequestPartialDto.class, "task",
		RegisterRequestDto.class, "user"
);
```

Des constantes : `static final` + collection non modifiable. `Class<?>` se lit « une classe de type quelconque ».

`GlobalExceptionHandler.java:116` : `cause.getPath().getFirst()`, le premier élément du chemin JSON en erreur.

### Une annotation écrite à la main

`server/src/main/java/com/personal/task_tracker/auth/validator/MaxBytes.java` déclare une annotation avec
`@interface`. Les méta-annotations disent où on peut la poser (`@Target`) et qu'elle doit rester visible à
l'exécution (`@Retention(RUNTIME)`), pour que Bean Validation puisse la lire. Son validateur,
`MaxBytesValidator.java:19`, montre l'encodage explicite :

```java
return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
```

`StandardCharsets.UTF_8` plutôt que `getBytes()` sans argument, qui dépendrait de l'encodage de la machine. Même
logique que `Locale.ROOT`. Et `value == null` renvoie `true` : par convention, une contrainte ne juge pas l'absence,
c'est le rôle de `@NotBlank`.

### Immutabilité, `Locale.ROOT`, classes utilitaires, Javadoc

- `TaskService.java:22` et `:23` : dépendances en `private final`, injectées par le constructeur.
- `UserService.java:69` : `userDto.email().trim().toLowerCase(Locale.ROOT)`.
- `server/src/main/java/com/personal/task_tracker/user/mapper/UserMapper.java:10` : `private UserMapper() { }`.
- Les services documentent chaque méthode publique, y compris les exceptions levées (`@throws`).

### Nullabilité : un usage ciblé

Le projet utilise `@Nullable` et `@NonNull` à des endroits précis : les paramètres de cause dans
`UserAlreadyExistsException`, les méthodes surchargées de `ResponseEntityExceptionHandler`, le retour `@Nullable`
de `GlobalExceptionHandler.toError(HttpMessageNotReadableException)` (`:112`), qui renvoie `null` quand le JSON
est mal formé. Aucun package n'est annoté `@NullMarked` : la convention « non null par défaut » n'est pas déclarée
globalement.

---

## 4. À toi de jouer

**Exercice 1 : observer le code généré d'un record.** Dans un test, ou dans une petite classe `main` jetable :

```java
TaskResponseDto a = new TaskResponseDto(1L, "Titre", null, null, null, null, null, null, null);
TaskResponseDto b = new TaskResponseDto(1L, "Titre", null, null, null, null, null, null, null);
System.out.println(a);
System.out.println(a.equals(b));
System.out.println(a == b);
```

À observer : un `toString` lisible généré, `equals` vrai (comparaison des valeurs) et `==` faux (deux objets
distincts). Ensuite, dans l'IDE, ouvre la vue « Structure » de `TaskResponseDto` : tu y verras les accesseurs et
méthodes générés, absents du fichier source.

**Exercice 2 : réécrire un stream en boucle, et inversement.** Réécris `UserService.getAllUsers` avec une boucle
`for`, puis compare les deux versions. Ensuite, essaie d'ajouter un élément à la liste renvoyée par le stream
(`.add(...)`) dans un test : à observer, une `UnsupportedOperationException`, car `toList()` renvoie une liste non
modifiable.

**Exercice 3 : le piège du turc.** Dans une classe jetable :

```java
System.out.println("TITLE".toLowerCase(Locale.forLanguageTag("tr")));
System.out.println("TITLE".toLowerCase(Locale.ROOT));
```

À observer : `tıtle` (sans point sur le i) puis `title`. Imagine deux emails comparés ainsi sur un serveur configuré
en turc.

**Exercice 4 : repérer les nullabilités.** Dans l'IDE, appelle `toError(...)` (la version
`HttpMessageNotReadableException`) et utilise le résultat sans vérifier `null`. Selon ta configuration, l'IDE
signale un avertissement grâce au `@Nullable`. Sinon, cherche dans les réglages de l'inspection des nullabilités.
Annule ensuite ta modification.

---

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `dto.getTitle()` ne compile pas | Les accesseurs d'un record n'ont pas de préfixe `get` | `dto.title()` |
| Hibernate refuse une entité écrite en record | Un record est immuable et sans constructeur vide | Classe classique pour les entités, records pour les DTO |
| `UnsupportedOperationException` sur `add` | Liste issue de `List.of` ou `Stream.toList()`, non modifiable | `new ArrayList<>(liste)` si une copie modifiable est nécessaire |
| `NullPointerException` dans `Map.of` ou `List.of` | Ces collections refusent `null` | Vérifier les valeurs, ou une collection classique |
| `NoSuchElementException` sur `optional.get()` | Optional vide, absence non traitée | `orElseThrow` avec une exception métier |
| Un mot de passe apparaît dans les logs | `toString` généré d'un record avec un champ sensible | Redéfinir `toString` comme `RegisterRequestDto` |
| Comparaisons de texte fausses sur certains serveurs | `toLowerCase()` / `getBytes()` sans locale ni encodage | `Locale.ROOT`, `StandardCharsets.UTF_8` |
| `@NonNull` jspecify ne rejette pas une requête sans champ | Annotation d'analyse statique, sans effet à l'exécution | `@NotNull` / `@NotBlank` de Bean Validation |

---

## 6. Pour aller plus loin

- **`switch` expressions et pattern matching dans `switch`** (Java 21) : `switch` qui renvoie une valeur et
  distingue les types, utile par exemple pour traduire un statut en libellé.
- **Classes et interfaces scellées** (`sealed`, Java 17) : limiter les sous-types possibles, pratique pour une
  famille fermée d'erreurs métier.
- **Records déconstruits** (*record patterns*, Java 21) : `if (obj instanceof Point(int x, int y))`.
- **`var`** (Java 10) : inférence du type des variables locales. Le projet ne l'utilise pas, c'est un choix de
  lisibilité.
- **Text blocks** (`"""`, Java 15) : chaînes sur plusieurs lignes, pratiques pour du JSON dans les tests.
- **MapStruct** : générer les mappers au lieu de les écrire à la main.
- **NullAway ou l'inspection de l'IDE** : faire respecter les annotations jspecify à la compilation.
- **Documentation officielle** : « Java Language Updates » (Oracle) qui liste les nouveautés par version ; le site
  jspecify.dev ; les JEP 395 (records) et 394 (pattern matching pour `instanceof`).

---

## 7. Quiz

**1. Quelles méthodes Java génère-t-il pour `record Point(int x, int y)` ?**

<details><summary>Réponse</summary>

Le constructeur `Point(int x, int y)`, les accesseurs `x()` et `y()`, ainsi que `equals`, `hashCode` et
`toString`. Les champs sont `private final`.

</details>

**2. Pourquoi `Task` n'est-elle pas un record ?**

<details><summary>Réponse</summary>

Hibernate a besoin d'un constructeur sans argument, de champs modifiables et d'une classe extensible (pour les
proxies du chargement LAZY). Un record est immuable et `final`.

</details>

**3. Que signifie `TaskMapper::toTaskResponseDto` dans `.map(TaskMapper::toTaskResponseDto)` ?**

<details><summary>Réponse</summary>

Une référence à la méthode statique `toTaskResponseDto`, équivalente à la lambda
`task -> TaskMapper.toTaskResponseDto(task)`. `map` l'applique à chaque élément du stream.

</details>

**4. Avec `JsonNullable`, que se passe-t-il en PATCH pour `{"description": null}` et pour `{}` ?**

<details><summary>Réponse</summary>

Avec `{"description": null}`, la valeur est présente (et vaut `null`) : `ifPresent` appelle `setDescription(null)`,
la description est effacée. Avec `{}`, la valeur est absente : rien n'est modifié.

</details>

**5. Pourquoi `RegisterRequestDto` redéfinit-il `toString` ?**

<details><summary>Réponse</summary>

Le `toString` généré par le record afficherait le mot de passe en clair. Un objet finit souvent dans un log : on
masque le champ sensible.

</details>

**6. `@NonNull` (jspecify) sur un champ de DTO suffit-il à renvoyer un 400 si le champ manque ?**

<details><summary>Réponse</summary>

Non. C'est une annotation d'analyse statique, sans effet à l'exécution. Il faut une contrainte Bean Validation
(`@NotNull`, `@NotBlank`) et `@Valid` sur le paramètre du contrôleur.

</details>
