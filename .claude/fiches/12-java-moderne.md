# 12. Java moderne et qualité de code

**Ce que tu vas comprendre**
- Les records, et pourquoi ils sont parfaits pour les DTO mais pas pour les entités
- Les lambdas, références de méthode et streams, qui remplacent la plupart des boucles du projet
- `Optional` et les annotations de nullabilité, pour traiter l'absence de valeur
- Le *pattern matching* (`instanceof` avec variable), les collections immuables, `getFirst()`
- Quelques réflexes de qualité : immutabilité, `Locale.ROOT`, Javadoc, classes utilitaires
- Ce que le compilateur génère vraiment pour les records, les enums, les lambdas et le *pattern matching*, et
  comment le lire toi-même dans les `.class` du projet avec `javap`

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
champ d'instance en dehors des composants (seulement des champs `static`), et les composants sont `final`.

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

## 4. Sous le capot

Les sections précédentes disent **ce que** permet le langage. Celle-ci montre **ce que le compilateur en fait** :
la JVM ne connaît ni les records, ni les lambdas, ni le *pattern matching* sous la forme où tu les écris. `javac`
les traduit en bytecode ordinaire, parfois avec un peu d'aide de la bibliothèque standard au démarrage. Rien n'est
caché : tout se lit dans les `.class` du projet. Même plan que la fiche 02 : le mécanisme réel, comment
l'observer, la source.

L'outil d'observation est `javap`, le désassembleur fourni avec le JDK. Compile d'abord, depuis `server/` :

```bash
./mvnw compile
javap -p -cp target/classes <nom.complet.de.la.Classe>      # la liste des membres, privés compris
javap -c -p -cp target/classes <...>                         # + le bytecode de chaque méthode
javap -v -p -cp target/classes <...>                         # + tous les attributs (annotations, noms de paramètres...)
```

### 4.1 Un record est une classe finale dont `javac` écrit le corps

**Le mécanisme.** Pour `record TaskResponseDto(...)`, `javac` produit une classe tout à fait ordinaire. Sortie
réelle de `javap -p -cp target/classes com.personal.task_tracker.task.dto.TaskResponseDto`, abrégée :

```
public final class com.personal.task_tracker.task.dto.TaskResponseDto extends java.lang.Record {
  private final java.lang.Long id;
  private final java.lang.String title;
  ...
  public com.personal.task_tracker.task.dto.TaskResponseDto(java.lang.Long, java.lang.String, ...);
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public java.lang.Long id();
  public java.lang.String title();
  ...
}
```

Tout ce que dit 2.1 est là : champs `private final`, constructeur canonique, accesseurs sans `get`. Et un détail qui
explique la remarque sur les entités : la classe est **`final`**. Hibernate ne pourrait pas en générer de
sous-classe pour le chargement LAZY.

Ce qui est moins attendu, c'est le corps de `toString`, `equals` et `hashCode`. `javap -c` montre que `javac` ne les
écrit pas champ par champ :

```
  public final java.lang.String toString();
    Code:
         0: aload_0
         1: invokedynamic #43,  0             // InvokeDynamic #0:toString:(Lcom/personal/task_tracker/task/dto/TaskResponseDto;)Ljava/lang/String;
         6: areturn
```

`invokedynamic` signifie "au premier appel, demande à une méthode de la bibliothèque standard de fabriquer
l'implémentation". Ici, `javap -v` indique laquelle, `java.lang.runtime.ObjectMethods.bootstrap`, et ce qu'on lui
passe : la liste des composants (`id;title;description;...`) et un accès direct à chaque champ. L'implémentation est
donc construite une fois, au démarrage, à partir de cette description. Avantage : le bytecode reste minuscule, et une
JVM plus récente peut fournir une implémentation plus rapide sans recompiler ton code.

Le compilateur ajoute enfin un attribut `Record` (visible avec `javap -v`) qui liste les composants. C'est lui que
lit `Class.getRecordComponents()`, et c'est ainsi qu'une bibliothèque comme Jackson sait qu'un record a les
composants `id`, `title`... sans chercher de getters.

Pour `RegisterRequestDto`, qui redéfinit `toString`, la méthode n'est plus `final` (c'est la tienne) et son
bytecode n'utilise plus `ObjectMethods` : il lit les quatre champs puis appelle
`invokedynamic ... makeConcatWithConstants`. C'est le même principe appliqué à ta concaténation
`"RegisterRequestDto[firstname=" + firstname + ...` : depuis Java 9, `javac` ne génère plus de `StringBuilder`, il
délègue l'assemblage de la chaîne à `StringConcatFactory`. Le `password` n'est jamais lu par cette méthode :
`javap -c` le prouve, il n'y a que quatre `getfield`.

**L'observer.**

```bash
javap -c -p -cp target/classes com.personal.task_tracker.task.dto.TaskResponseDto | sed -n '/toString()/,/areturn/p'
javap -v -p -cp target/classes com.personal.task_tracker.task.dto.TaskResponseDto | grep -A12 'BootstrapMethods:'
javap -c -p -cp target/classes com.personal.task_tracker.auth.dto.RegisterRequestDto | sed -n '/String toString()/,/areturn/p'
```

**La source.** [JEP 395 : Records](https://openjdk.org/jeps/395),
[java.lang.Record](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Record.html),
[ObjectMethods](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/runtime/ObjectMethods.html),
[JEP 280 : Indify String Concatenation](https://openjdk.org/jeps/280),
[javap](https://docs.oracle.com/en/java/javase/25/docs/specs/man/javap.html).

### 4.2 Où atterrissent les annotations d'un composant de record

**Le mécanisme.** Dans `RegisterRequestDto`, tu poses `@NotBlank` sur le composant `password`. Mais un composant
n'existe pas dans le bytecode : il devient un champ, un accesseur et un paramètre du constructeur. `javac` recopie
l'annotation **sur chacun de ces éléments où son `@Target` l'autorise**. Constat avec `javap -v -p` sur le projet :

| Annotation | Champ `password` | Accesseur `password()` | Paramètre du constructeur |
|---|---|---|---|
| `@NotBlank`, `@Size` (cibles larges, dont `METHOD`) | oui | oui | oui |
| `@MaxBytes` (`@Target({FIELD, PARAMETER, RECORD_COMPONENT})`) | oui | **non** | oui |

C'est donc le `@Target` que tu écris dans `MaxBytes.java` qui décide où l'annotation existera. Hibernate Validator
trouve ensuite les contraintes en lisant ces éléments par réflexion.

L'autre méta-annotation, `@Retention(RUNTIME)`, n'est pas décorative non plus : elle demande au compilateur de
garder l'annotation dans le `.class` **et** de la rendre lisible par réflexion.

**Si on la retire.** Vérifié sur le projet : en passant temporairement `MaxBytes` en `@Retention(RetentionPolicy.CLASS)`
(l'annotation reste dans le fichier `.class`, mais la réflexion ne la voit plus), tout compile, et le test
`AuthControllerTest.shouldReturn400WhenPasswordExceeds72BytesWithFewerCharacters` échoue :
`Expected status code <400> but was <500>`. Le log montre ce qui s'est passé :

```
java.lang.IllegalArgumentException: password cannot be more than 72 bytes
```

Hibernate Validator n'a pas vu la contrainte, le mot de passe de 80 octets est arrivé jusqu'à BCrypt, qui l'a
refusé. Aucune erreur, aucun avertissement au démarrage : une contrainte invisible est simplement ignorée.

**L'observer.**

```bash
javap -v -p -cp target/classes com.personal.task_tracker.auth.dto.RegisterRequestDto | sed -n '/public java.lang.String password();/,/public java.lang.String/p'
```

Tu y vois `RuntimeVisibleAnnotations` avec `@NotBlank` et `@Size`, mais pas `@MaxBytes`.

**La source.** [JLS 8.10.3 : Record Members](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10.3)
(propagation des annotations des composants),
[RetentionPolicy](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/annotation/RetentionPolicy.html).

### 4.3 Un enum est une classe avec des constantes numérotées

**Le mécanisme.** `enum TaskStatus { BACKLOG, READY, ... }` devient, d'après `javap -p` :

```
public final class com.personal.task_tracker.task.enums.TaskStatus extends java.lang.Enum<com.personal.task_tracker.task.enums.TaskStatus> {
  public static final com.personal.task_tracker.task.enums.TaskStatus BACKLOG;
  public static final com.personal.task_tracker.task.enums.TaskStatus READY;
  ...
  private static final com.personal.task_tracker.task.enums.TaskStatus[] $VALUES;
  public static com.personal.task_tracker.task.enums.TaskStatus[] values();
  public static com.personal.task_tracker.task.enums.TaskStatus valueOf(java.lang.String);
  private com.personal.task_tracker.task.enums.TaskStatus();
  ...
  static {};
}
```

Chaque constante est un objet unique, créé une seule fois dans le bloc `static {}`. `javap -c` montre comment :

```
         0: new           #1                  // class com/personal/task_tracker/task/enums/TaskStatus
         3: dup
         4: ldc           #47                 // String BACKLOG
         6: iconst_0
         7: invokespecial #48                 // Method "<init>":(Ljava/lang/String;I)V
```

Le constructeur reçoit en réalité deux arguments cachés : le **nom** (`"BACKLOG"`) et un **numéro d'ordre**
(`0`, puis `1` pour `READY`...), l'`ordinal`. `valueOf("READY")` retrouve la constante par son nom ; Jackson
fait de même, par défaut, pour lire `"status": "READY"` dans un JSON, et écrit le nom (pas l'ordinal) en sortie.

**Pourquoi `@Enumerated(EnumType.STRING)` dans `Task`.** JPA peut stocker un enum par son ordinal (le défaut) ou par
son nom. L'ordinal dépend uniquement de **l'ordre de déclaration** : insérer un statut entre `BACKLOG` et `READY`
décalerait tous les numéros, et chaque ligne déjà en base désignerait alors un autre statut, sans aucune erreur.
Stocker le nom évite ce piège (et la colonne reste lisible en SQL). Le risque se déplace : **renommer** une
constante casse la lecture des lignes existantes, d'où la contrainte `CHECK` côté base (voir
[08. Persistance](08-persistance.md)).

**L'observer.** `TaskStatus.READY.ordinal()` renvoie `1`, `TaskStatus.values().length` renvoie `8` (voir 4.6 pour
un programme qui l'affiche).

**La source.** [JLS 8.9 : Enum Classes](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.9),
[java.lang.Enum](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Enum.html).

### 4.4 Une lambda n'est pas une classe anonyme

**Le mécanisme.** Avant Java 8, l'équivalent d'une lambda était une classe anonyme, compilée dans un fichier à part
(`TaskService$1.class`). Regarde `target/classes/com/personal/task_tracker/task/service/` : il n'y a que
`TaskService.class`. Les lambdas y sont traduites autrement.

1. **Le corps de la lambda devient une méthode privée** de la classe. `javap -p` sur `TaskService` en montre une :
   ```
   private static com.personal.task_tracker.task.exception.TaskNotFoundException lambda$findTaskById$0(java.lang.Long);
   ```
   C'est `() -> new TaskNotFoundException("There is no task with id: " + taskId)`. La lambda n'a aucun paramètre,
   mais la méthode en reçoit un : `taskId`, la variable **capturée**, est passée par valeur au moment où la lambda
   est créée. Voilà pourquoi Java exige qu'une variable capturée soit *effectivement finale* : la lambda en reçoit
   une copie, une modification ultérieure ne pourrait pas lui parvenir.
2. **À l'endroit où la lambda est créée**, `javac` place un `invokedynamic` qui confie la fabrication de l'objet à
   `LambdaMetafactory` :
   ```
   26: invokedynamic #39,  0             // InvokeDynamic #0:apply:()Ljava/util/function/Function;
   11: invokedynamic #125,  0            // InvokeDynamic #1:get:(Ljava/lang/Long;)Ljava/util/function/Supplier;
   ```
   La première est `.map(TaskMapper::toTaskResponseDto)` dans `getAllTasks`, la seconde la lambda de
   `findTaskById`, qui reçoit le `Long` capturé. Pour la **référence de méthode**, aucune méthode privée n'est
   générée : `javap -v` montre que `LambdaMetafactory` reçoit directement
   `REF_invokeStatic com/personal/task_tracker/task/mapper/TaskMapper.toTaskResponseDto`.
3. **Au premier passage**, `LambdaMetafactory` génère en mémoire une petite classe qui implémente `Function` (ou
   `Supplier`) et appelle la méthode cible. C'est une classe *cachée* (*hidden class*), qui n'existe dans aucun
   fichier.

**L'observer.** Les points 1 et 2 :

```bash
javap -p -cp target/classes com.personal.task_tracker.task.service.TaskService | grep lambda
javap -c -p -cp target/classes com.personal.task_tracker.task.service.TaskService | grep invokedynamic
```

Pour le point 3, affiche la classe d'une lambda (programme de 4.6 ou n'importe quel test) :
`mapper.getClass().getName()` donne un nom de la forme `LambdaProbe$$Lambda/0x000007c001158210` (le nom de la
classe qui crée la lambda, puis une adresse qui change à chaque exécution), et `getClass().isHidden()` renvoie
`true`. Même famille de noms que les `$$SpringCGLIB$$0` de la fiche 02 : une classe écrite en mémoire au démarrage.

**La source.** [JLS 15.27 : Lambda Expressions](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.27),
[LambdaMetafactory](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/LambdaMetafactory.html),
[JEP 371 : Hidden Classes](https://openjdk.org/jeps/371).

### 4.5 Le *pattern matching* n'existe pas dans le bytecode

**Le mécanisme.** `instanceof ConstraintViolationException cve` est un pur confort d'écriture. Le bytecode de
`UserService.java:84` est exactement celui qu'aurait produit la version "test puis cast" de 2.5 :

```
107: instanceof    #127                // class org/hibernate/exception/ConstraintViolationException
110: ifeq          128
113: aload         9
115: checkcast     #127                // class org/hibernate/exception/ConstraintViolationException
118: astore        8
```

`instanceof` teste, `checkcast` convertit, `astore` range le résultat dans une variable locale (`cve`). Aucun coût
supplémentaire à l'exécution, et aucune nouveauté pour la JVM : le compilateur a écrit le cast pour toi, et
vérifie à ta place que la variable n'est utilisée que là où le test a réussi.

Même constat pour `@Nullable` et `@NonNull` (2.7) : `javap -v` sur `GlobalExceptionHandler` montre que jspecify
les place bien dans le bytecode (`RuntimeVisibleTypeAnnotations`), mais **aucun** code du projet ou de Spring ne les
lit pour bloquer une valeur à l'exécution. Contrairement à `@MaxBytes` (4.2), les retirer ne changerait rien au
comportement de l'application, seulement aux avertissements de l'IDE.

**L'observer.**

```bash
javap -c -p -cp target/classes com.personal.task_tracker.user.service.UserService | grep -B2 -A6 instanceof
```

**La source.** [JEP 394 : Pattern Matching for instanceof](https://openjdk.org/jeps/394),
[Pattern Matching for instanceof (guide Oracle)](https://docs.oracle.com/en/java/javase/25/language/pattern-matching-instanceof.html).

### 4.6 Le refaire toi-même : lire un record comme Jackson

Jackson ne connaît pas `TaskResponseDto` à l'avance. Il découvre ses composants par réflexion, grâce à l'attribut
`Record` vu en 4.1. Ce petit programme fait la même chose. Crée-le **hors du projet** (par exemple
`~/probe/RecordProbe.java`) et lance-le contre les classes compilées du projet, sans Maven :

```java
import com.personal.task_tracker.task.dto.TaskResponseDto;
import com.personal.task_tracker.task.enums.TaskStatus;
import java.lang.reflect.RecordComponent;

public class RecordProbe {
	public static void main(String[] args) throws Exception {
		TaskResponseDto dto = new TaskResponseDto(1L, "Titre", null, null, TaskStatus.READY, null, null, null, null);
		System.out.println(TaskResponseDto.class.isRecord());
		for (RecordComponent component : TaskResponseDto.class.getRecordComponents()) {
			System.out.println(component.getName() + " = " + component.getAccessor().invoke(dto));   // "clé" = valeur
		}
		System.out.println(dto);
		System.out.println(TaskStatus.READY.ordinal() + " " + TaskStatus.valueOf("READY") + " " + TaskStatus.values().length);
	}
}
```

```bash
java -cp server/target/classes ~/probe/RecordProbe.java
```

Sortie réelle :

```
true
id = 1
title = Titre
description = null
type = null
status = READY
priority = null
assignee = null
createdDate = null
updatedDate = null
TaskResponseDto[id=1, title=Titre, description=null, type=null, status=READY, priority=null, assignee=null, createdDate=null, updatedDate=null]
1 READY 8
```

Remplace l'affichage par l'écriture de `"nom": valeur` et tu as l'essentiel d'un sérialiseur JSON pour records.
Ce qui manque par rapport à Jackson : les types imbriqués (`assignee` est lui-même un record), les dates, les
`JsonNullable`, la configuration. Le principe, lui, est le même.

**Défi :** ajoute une ligne qui affiche `getRecordComponents()` pour une **classe** classique, l'entité
`com.personal.task_tracker.task.entity.Task`. Que renvoie la méthode, et qu'en déduis-tu sur la façon dont Jackson
doit traiter une classe qui n'est pas un record ? (Des avertissements `unknown enum constant` peuvent s'afficher à
la compilation du programme : les annotations JPA de `Task` ne sont pas sur ce classpath. Ils sont sans
conséquence ici.)

<details><summary>Réponse</summary>

`getRecordComponents()` renvoie `null` (vérifié) pour une classe qui n'est pas un record. Jackson doit alors
chercher autre chose : les getters (`getTitle()`), les champs publics, ou des annotations comme `@JsonProperty`.
Une classe classique aux champs privés doit donc exposer des getters (ou être annotée) pour être sérialisée, alors
qu'un record n'en a pas besoin : ses composants sont déclarés dans le bytecode.
</details>

---

## 5. À toi de jouer

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
méthodes générés, absents du fichier source. Pour le bytecode lui-même, voir `javap` en section 4.1.

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

## 6. Pièges fréquents

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

## 7. Pour aller plus loin

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
- **Documentation officielle** : en plus des liens de la section 4 (JEP 395 pour les records, JEP 394 pour le
  *pattern matching*, `javap`, `LambdaMetafactory`, la spécification du langage), « Java Language Updates »
  (Oracle) qui liste les nouveautés par version, et le site jspecify.dev.

---

## 8. Quiz

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
