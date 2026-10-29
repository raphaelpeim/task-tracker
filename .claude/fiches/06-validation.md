# 06. Validation

**Ce que tu vas comprendre**
- Comment Bean Validation vérifie les données reçues avec de simples annotations
- Le rôle indispensable de `@Valid`
- Comment les messages d'erreur sont externalisés et transformés en codes pour le front
- Comment écrire ta propre contrainte (l'exemple de `@MaxBytes`)
- Pourquoi on valide à la fois dans l'API et dans la base
- Ce qui se passe vraiment derrière `@Valid` : qui déclenche la validation, quel moteur, comment `@MaxBytes` et les
  messages sont trouvés, et ce qui casse si on retire une pièce

**Prérequis** : [05. Web et REST](05-web-rest.md). Utile ensuite : [07. Exceptions](07-exceptions.md).

---

## 1. Le problème que ça résout

Le client peut envoyer n'importe quoi : un titre vide, une description de 10 000 caractères, un email sans `@`.
Sans validation, tu écrirais au début de chaque méthode :

```java
if (dto.title() == null || dto.title().isBlank()) {
	throw new IllegalArgumentException("Title is required");
}
if (dto.title().length() > 50) {
	throw new IllegalArgumentException("Title too long");
}
// ... et ainsi de suite pour chaque champ, dans chaque end-point
```

C'est long, répétitif, facile à oublier, et le client ne reçoit que la **première** erreur : il corrige, renvoie,
découvre la suivante...

**Analogie** : le contrôle à l'entrée d'un concert. Le vigile vérifie le billet avant que tu entres, pas une fois
que tu es dans la fosse. Et un bon vigile te dit tout ce qui ne va pas d'un coup (« pas de billet, et le sac est trop
grand »), pas une chose à la fois.

**Bean Validation** (spécification Jakarta, implémentée par Hibernate Validator) déplace ces règles dans des
annotations sur le DTO, les vérifie toutes, et renvoie la liste complète des erreurs.

---

## 2. Les concepts

### 2.1 Les contraintes de base

| Annotation | Vérifie | Accepte `null` ? |
|---|---|---|
| `@NotNull` | La valeur n'est pas `null` | Non |
| `@NotEmpty` | Pas `null`, et pas vide (`""`, liste vide) | Non |
| `@NotBlank` | Pas `null`, et au moins un caractère non blanc (`"   "` refusé) | Non |
| `@Size(min, max)` | Longueur d'une chaîne ou taille d'une collection | **Oui** |
| `@Email` | Format d'email | **Oui** |
| `@Min`, `@Max`, `@Positive` | Bornes numériques | **Oui** |
| `@Pattern(regexp)` | Expression régulière | **Oui** |

Règle à retenir : **toutes les contraintes acceptent `null`, sauf celles qui sont faites pour le refuser**
(`@NotNull`, `@NotEmpty`, `@NotBlank`). Un champ optionnel limité à 255 caractères porte donc seulement
`@Size(max = 255)` ; un champ obligatoire combine `@NotBlank` et `@Size`.

### 2.2 `@Valid` déclenche la validation

Les annotations sur le DTO ne font **rien** toutes seules. C'est `@Valid` sur le paramètre du controller qui
demande à Spring de valider l'objet après la conversion JSON :

```java
public ResponseEntity<...> createTask(@Valid @RequestBody TaskRequestDto taskDto)
```

Si au moins une contrainte échoue, la méthode du controller **n'est pas appelée**. Spring lève
`MethodArgumentNotValidException`, qui contient toutes les erreurs, champ par champ.

### 2.3 Messages externalisés

Chaque contrainte a un `message`. Il peut être une phrase en dur, ou une **clé entre accolades** qui renvoie au
fichier `ValidationMessages.properties` :

```java
@Size(max = 50, message = "{task.title.invalidSize}")
```

```properties
task.title.invalidSize=Title must be at most {max} characters
```

Dans la phrase, `{max}` est remplacé par l'attribut de l'annotation : le client lit
« Title must be at most 50 characters ». Si tu passes la limite à 60, la phrase suit toute seule.

### 2.4 Validation au niveau de la base

Le DTO protège l'API. Mais la base a aussi ses contraintes (`NOT NULL`, `VARCHAR(50)`, `CHECK`, `UNIQUE`). Ce n'est
pas redondant :

- la base protège contre **tout** écrivain : un bug, un script, une autre application ;
- certaines règles ne peuvent être garanties **que** par la base. Exemple : « email unique ». Vérifier avant
  d'insérer ne suffit pas si deux inscriptions arrivent en même temps ; seule la contrainte `UNIQUE` tranche
  (voir [07. Exceptions](07-exceptions.md), traduction de `DataIntegrityViolationException`).

### 2.5 Écrire sa propre contrainte

Quand aucune annotation standard ne convient, on en crée une. Il faut deux pièces :

1. **L'annotation**, marquée `@Constraint(validatedBy = ...)`, avec trois attributs obligatoires : `message`,
   `groups`, `payload`, plus ses propres paramètres.
2. **Le validateur**, qui implémente `ConstraintValidator<Annotation, TypeValidé>` et sa méthode `isValid`.

Le validateur doit suivre la convention de la section 2.1 : **renvoyer `true` pour `null`**, et laisser `@NotBlank`
s'occuper de l'absence.

---

## 3. Dans le projet

### Un DTO validé

`server/src/main/java/com/personal/task_tracker/auth/dto/RegisterRequestDto.java:9-30`

```java
public record RegisterRequestDto(
		@NotBlank(message = "{user.firstname.required}")       // obligatoire
		@Size(max = 50, message = "{user.firstname.invalidSize}")  // et limité
		String firstname,
		...
		@NotBlank(message = "{user.email.required}")
		@Email(message = "{user.email.invalidFormat}")
		@Size(max = 255, message = "{user.email.invalidSize}")
		String email,

		@NotBlank(message = "{user.password.required}")
		@Size(min = 8, message = "{user.password.invalidSize}")       // au moins 8 caractères
		@MaxBytes(value = 72, message = "{user.password.invalidSize}") // au plus 72 octets
		String password
) { ... }
```

Les limites (`max = 50`, `max = 255`) correspondent aux colonnes SQL : un titre de 51 caractères serait de toute
façon refusé par la base, mais avec une erreur bien moins claire.

### Le fichier de messages

`server/src/main/resources/ValidationMessages.properties:1-16`

```properties
# English sentences returned in "detail" for each validation error.
# The key (without braces) is returned in "code" so that the front can translate it.
...
task.title.required=Title is required
task.title.invalidSize=Title must be at most {max} characters
task.title.invalidFormat=Title must be a text
```

Le projet utilise la clé **deux fois** : la phrase anglaise va dans `detail` (lisible par un développeur), la clé
elle-même va dans `code` (le front s'en sert pour afficher un message traduit). Le fichier n'existe qu'en une
langue, donc la phrase reste en anglais quelle que soit la langue du client (test
`shouldReturnEnglishDetailWhateverTheRequestLanguage`, `TaskControllerTest.java:270`).

Les clés `invalidFormat` ne viennent pas de Bean Validation : elles servent quand Jackson ne peut même pas convertir
la valeur (du texte à la place d'un nombre). Voir [07. Exceptions](07-exceptions.md).

### Ce que reçoit le client

Pour `POST /api/tasks` avec `{}`, la réponse contient une erreur par champ obligatoire
(`shouldReturn400ForEachMissingRequiredField`, `TaskControllerTest.java:283`) :

```json
{
  "status": 400,
  "detail": "The request has validation errors",
  "code": "validation.failed",
  "errors": [
    { "pointer": "#/title",    "code": "task.title.required",    "detail": "Title is required" },
    { "pointer": "#/type",     "code": "task.type.required",     "detail": "Type is required" },
    { "pointer": "#/status",   "code": "task.status.required",   "detail": "Status is required" },
    { "pointer": "#/priority", "code": "task.priority.required", "detail": "Priority is required" }
  ]
}
```

(les propriétés standard `title` et `instance` sont aussi présentes ; `type` est omis, ce qui équivaut à
`about:blank`, voir [07. Exceptions](07-exceptions.md) section 4.5 ; l'ordre des erreurs peut varier).

`description` et `assigneeId` n'apparaissent pas : ils sont optionnels.

### Une seule erreur par champ

`server/src/main/java/com/personal/task_tracker/commons/exception/GlobalExceptionHandler.java:38` et `56-60`

```java
private static final List<String> CONSTRAINT_PRIORITY = List.of("NotNull", "NotBlank", "Size", "MaxBytes", "Email");
...
// One error per field: when several constraints fail, keep the most basic one first
for (FieldError error : ex.getBindingResult().getFieldErrors()) {
	fieldErrors.merge(error.getField(), error, (current, other) -> priority(other) < priority(current) ? other : current);
}
```

Un titre de 60 espaces viole `@NotBlank` **et** `@Size`. Le handler ne garde que `@NotBlank`, plus fondamentale :
inutile de dire « trop long » à quelqu'un qui n'a rien écrit.

### La contrainte personnalisée `@MaxBytes`

`server/src/main/java/com/personal/task_tracker/auth/validator/MaxBytes.java:11-27`

```java
/**
 * The annotated string must not exceed the given number of bytes once encoded in UTF-8.
 * BCrypt only accepts passwords up to 72 bytes, whereas {@code @Size} counts characters.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})  // où on peut la poser
@Retention(RetentionPolicy.RUNTIME)                                                  // lisible à l'exécution
@Constraint(validatedBy = MaxBytesValidator.class)                                   // qui la vérifie
public @interface MaxBytes {
	int value();                                                       // le paramètre propre
	String message() default "size must not exceed {value} bytes";   // les trois attributs
	Class<?>[] groups() default {};                                    // imposés par
	Class<? extends Payload>[] payload() default {};                   // la spécification
}
```

`server/src/main/java/com/personal/task_tracker/auth/validator/MaxBytesValidator.java:8-21`

```java
public class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {
	private int max;

	@Override
	public void initialize(MaxBytes constraint) {
		this.max = constraint.value();               // lit le paramètre de l'annotation
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;   // null accepté
	}
}
```

Pourquoi ? `é` est **un** caractère mais **deux** octets en UTF-8. `@Size(max = 72)` laisserait passer 40 `é`
(80 octets), que BCrypt ne sait pas hacher correctement. Les tests de `AuthControllerTest.java` le vérifient :
`shouldReturn400WhenPasswordExceeds72BytesWithFewerCharacters` (ligne 391) et
`shouldRegisterWhenPasswordIsExactly72Bytes` (ligne 408).

### Validation en `PATCH`

`TaskRequestPartialDto` pose `@NotBlank` sur un `JsonNullable<String>`. La contrainte s'applique à la **valeur
contenue** : un titre absent du JSON n'est pas vérifié (`PATCH {}` passe, test ligne 674), un titre envoyé à `null`
est refusé (`shouldReturn400WhenTitleIsExplicitlyNullInPatch`, ligne 760). C'est la bibliothèque
`jackson-databind-nullable` qui fournit à Bean Validation de quoi « ouvrir » le `JsonNullable`.

### Un test qui garde tout cohérent

`server/src/test/java/com/personal/task_tracker/commons/exception/ValidationMessagesTest.java` vérifie par
réflexion que :

- chaque `@RequestBody` d'un controller est déclaré dans `GlobalExceptionHandler.DOMAINS` (ligne 30) ;
- chaque message de contrainte est une clé entre accolades présente dans le fichier (ligne 51) ;
- chaque champ a sa clé `invalidFormat` (ligne 77).

Oublier une clé fait donc échouer la suite de tests au lieu d'afficher une erreur incompréhensible en production.

---

## 4. Sous le capot

Les sections précédentes disent **ce que** fait la validation. Celle-ci montre **qui** la déclenche, **avec quoi**,
et ce qui casse si on retire une pièce. Même plan que dans [02. Beans et injection](02-beans-injection.md) : le
mécanisme réel, comment l'observer, la source. Les sorties citées ont été relevées sur le projet (Spring Boot
4.1.1, Hibernate Validator 9.1.3), avec un test temporaire qui étend `IntegrationTest`.

### 4.1 Trois étages : la spécification, le moteur, la colle Spring

**Le mécanisme.** `spring-boot-starter-validation` n'apporte pas une bibliothèque mais trois étages, qu'on lit dans
la liste des `.jar` du projet :

| Jar | Rôle | Ce qu'il contient |
|---|---|---|
| `jakarta.validation-api-3.1.1` | La **spécification** : des interfaces et des annotations | `@NotBlank`, `@Size`, `@Valid`, `ConstraintValidator`, `Validator`... Aucun code qui vérifie quoi que ce soit |
| `hibernate-validator-9.1.3.Final` | Le **moteur** : l'implémentation de la spécification | Le code de chaque contrainte, l'interpolation des messages, les messages par défaut traduits |
| `spring-boot-validation-4.1.1` | La **colle** avec Spring | `ValidationAutoConfiguration`, qui crée les beans de validation |

Comment l'API trouve-t-elle le moteur sans le connaître ? Par le mécanisme standard de Java, `ServiceLoader` : le jar
d'Hibernate Validator contient un fichier texte `META-INF/services/jakarta.validation.spi.ValidationProvider` dont
l'unique ligne est `org.hibernate.validator.HibernateValidator`. C'est le même principe que le fichier
`AutoConfiguration.imports` de Spring Boot (voir [02, section 4.6](02-beans-injection.md)), mais au niveau du JDK.

`ValidationAutoConfiguration` s'appuie justement sur ce fichier : elle est protégée par
`@ConditionalOnClass(ExecutableValidator.class)` **et**
`@ConditionalOnResource("classpath:META-INF/services/jakarta.validation.spi.ValidationProvider")`. Pas de moteur sur
le classpath, pas d'auto-configuration. Quand elle passe, elle déclare deux beans :

- `defaultValidator`, un `LocalValidatorFactoryBean` : l'adaptateur Spring autour du moteur ;
- `methodValidationPostProcessor`, un `FilteredMethodValidationPostProcessor` (voir 4.3).

**L'observer.**

- Le fichier de service, depuis `server/` :
  ```bash
  unzip -p ~/.m2/repository/org/hibernate/validator/hibernate-validator/9.1.3.Final/hibernate-validator-9.1.3.Final.jar \
    META-INF/services/jakarta.validation.spi.ValidationProvider
  # org.hibernate.validator.HibernateValidator
  ```
- Les annotations de l'auto-configuration, sans les sources :
  `javap -v -cp ~/.m2/repository/org/springframework/boot/spring-boot-validation/4.1.1/spring-boot-validation-4.1.1.jar org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration | grep -E 'Conditional|ValidationProvider'`.
- Dans un test qui étend `IntegrationTest`, en demandant les beans au contexte puis en "déballant" le validateur
  (`unwrap`). Sortie réelle :
  ```
  jakarta Validator bean 'defaultValidator' -> org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
  spring Validator bean 'mvcValidator' -> org.springframework.boot.validation.autoconfigure.ValidatorAdapter
  unwrap ValidatorFactory -> org.hibernate.validator.internal.engine.ValidatorFactoryImpl
  unwrap Validator -> org.hibernate.validator.internal.engine.ValidatorImpl
  ```
  Sous l'emballage Spring, c'est bien Hibernate Validator qui travaille.

**Pourquoi c'est indispensable.** Avec seulement l'API, `@NotBlank` compile mais reste une étiquette : il n'existe
aucun code pour la lire. C'est le piège "`@NonNull` ne bloque rien" de la section 6, en plus sournois.

**La source.** [Java Bean Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
(Spring Framework), [Validation](https://docs.spring.io/spring-boot/reference/io/validation.html) (Spring Boot),
[Jakarta Validation 3.1](https://jakarta.ee/specifications/bean-validation/3.1/jakarta-validation-spec-3.1.html)
(la spécification).

### 4.2 Qui exécute `@Valid` sur un `@RequestBody` : un résolveur d'argument, pas un proxy

**Le mécanisme.** Contrairement à `@Transactional` ([02, section 4.4](02-beans-injection.md)), `@Valid` sur un
paramètre de contrôleur ne passe par **aucun proxy**. C'est Spring MVC lui-même qui s'en charge, au moment où il
prépare les arguments de ta méthode :

1. le `DispatcherServlet` confie la requête au `RequestMappingHandlerAdapter` ;
2. pour chaque paramètre, un **résolveur d'argument** fabrique la valeur. Pour `@RequestBody`, c'est
   `RequestResponseBodyMethodProcessor` : il fait convertir le JSON par Jackson, puis appelle
   `validateIfApplicable`, qui regarde si le paramètre porte `@Valid` (ou `@Validated`) ;
3. si oui, il crée un `DataBinder` et appelle le validateur MVC (`mvcValidator`), qui délègue à Hibernate Validator ;
4. chaque violation devient une `FieldError` dans un `BindingResult` ;
5. s'il y a au moins une erreur, le résolveur lève `MethodArgumentNotValidException`, avec ce `BindingResult`. Ta
   méthode n'est jamais appelée.

**L'observer.** Pose un point d'arrêt dans `MaxBytesValidator.isValid`, puis envoie une inscription avec un mot de
passe de 40 `é`. Pile d'appels réelle, lue de bas en haut et filtrée sur les classes utiles :

```
MaxBytesValidator.isValid(MaxBytesValidator.java)
ValidatorImpl.validate(ValidatorImpl.java:170)                                  <- Hibernate Validator
SpringValidatorAdapter.validate(SpringValidatorAdapter.java:103)                <- l'adaptateur Spring
ValidatorAdapter.validate(ValidatorAdapter.java:69)                             <- le mvcValidator de Spring Boot
DataBinder.validate(DataBinder.java:1402)
AbstractMessageConverterMethodArgumentResolver.validateIfApplicable(...:290)    <- "y a-t-il @Valid ?"
RequestResponseBodyMethodProcessor.resolveArgument(...:163)                     <- le résolveur de @RequestBody
InvocableHandlerMethod.getMethodArgumentValues(...:224)                         <- préparation des arguments
RequestMappingHandlerAdapter.invokeHandlerMethod(...:934)
DispatcherServlet.doDispatch(DispatcherServlet.java:963)
```

Aucune ligne `$$SpringCGLIB$$` : tout se passe **avant** l'appel de `AuthController.register`.

**Ce qui se passe sans `@Valid`.** Vérifié avec un contrôleur de test dont le `@RequestBody` a un champ `@NotBlank`
mais pas de `@Valid` : l'envoi de `{"name": ""}` donne un **200** et la méthode reçoit `name=''`. Le résolveur a
converti le JSON, n'a pas trouvé `@Valid`, et n'a rien vérifié. Avec `@Valid`, la même requête donne le 400
`validation.failed` habituel.

**La source.** [Validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
(Spring MVC), [Validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/validation.html)
(configuration MVC).

### 4.3 La validation de méthode : l'autre chemin, avec un proxy cette fois

**Le mécanisme.** Hors des contrôleurs, aucun résolveur d'argument n'est là pour lire `@Valid`. Pour valider les
paramètres d'un service, Spring utilise donc la technique de `@Transactional` : un `BeanPostProcessor` (voir
[02, section 4.4](02-beans-injection.md)), ici le `methodValidationPostProcessor` déclaré par Spring Boot. Il
n'enveloppe **que** les beans dont la classe porte `@Validated`. Le proxy appelle Hibernate Validator avant chaque
méthode et lève `jakarta.validation.ConstraintViolationException` si un paramètre est invalide.

Dans le projet, **aucun bean ne porte `@Validated`** : ce post-processeur existe, mais ne proxifie rien. La
validation passe uniquement par le chemin de 4.2.

**L'observer.** Dans un test, en donnant à la main au post-processeur deux petites classes, l'une avec
`@Validated`, l'autre sans, chacune avec une méthode `greet(@NotBlank String name)`. Sortie réelle :

```
MVPP bean 'methodValidationPostProcessor' -> org.springframework.boot.validation.beanvalidation.FilteredMethodValidationPostProcessor
@Validated after MVPP -> ...TmpProbeValidationTest$ProbeValidatedService$$SpringCGLIB$$0
exception -> jakarta.validation.ConstraintViolationException: greet.name: ne doit pas être vide
not @Validated after MVPP -> ...TmpProbeValidationTest$ProbeNotValidatedService
plain call -> hello
direct new @Validated call -> hello
```

Trois enseignements :

- avec `@Validated`, l'objet rendu est une sous-classe CGLIB, et l'appel invalide est refusé ;
- sans `@Validated`, le post-processeur rend l'objet tel quel, et `@NotBlank` est ignoré ;
- un `new ProbeValidatedService()` hors de Spring n'a pas de proxy, donc aucune validation. Mêmes limites que
  `@Transactional` : pas de `this.autreMethode()`, pas de méthode `private`.

Le message "ne doit pas être vide" est en français : c'est le message par défaut d'Hibernate Validator, choisi selon
la langue de la JVM (voir 4.5).

**Un troisième chemin, propre aux contrôleurs.** Depuis Spring Framework 6.1, une contrainte posée **directement**
sur un paramètre de contrôleur (`@PathVariable @Positive Long id`) est validée par Spring MVC sans proxy ni
`@Validated`. Vérifié avec un contrôleur de test : `GET /probe/validation/-1` donne un 400, mais l'exception est
`HandlerMethodValidationException`, pas `MethodArgumentNotValidException`. Le projet ne la personnalise pas : la
réponse est générique (`"detail": "Validation failure"`, `"code": "request.badRequest"`), sans la liste `errors`.
À garder en tête le jour où un contrôleur validera un paramètre de chemin.

**La source.** [Spring-driven Method Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html#validation-beanvalidation-spring-method),
[Validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)
(Spring MVC, les deux exceptions).

### 4.4 Comment `@MaxBytes` est trouvé et instancié

**Le mécanisme.** `MaxBytesValidator` ne porte ni `@Component` ni aucune annotation Spring, et rien ne le scanne.
Le lien est **dans l'annotation elle-même** : `@Constraint(validatedBy = MaxBytesValidator.class)`. Voici
l'enchaînement :

1. Au premier `validate` d'un `RegisterRequestDto`, Hibernate Validator lit par réflexion les annotations de ses
   composants. Il voit `@MaxBytes`, remarque qu'elle est elle-même annotée `@Constraint`, et lit `validatedBy`.
   C'est pour cela que `@Retention(RUNTIME)` est obligatoire : sans elle, l'annotation disparaît à la compilation et
   le moteur ne la voit jamais.
2. Pour créer l'instance, il demande à une `ConstraintValidatorFactory`. Spring a remplacé celle par défaut par
   `SpringConstraintValidatorFactory`, qui délègue à la fabrique de beans de Spring (`createBean`). Conséquence
   pratique : un validateur maison peut recevoir des dépendances par son constructeur, comme un bean (un
   repository, par exemple, pour vérifier une unicité).
3. Hibernate Validator appelle `initialize(annotation)` une fois, puis garde l'instance en cache. Chaque validation
   suivante appelle seulement `isValid`.

**L'observer.** Avec un constructeur temporaire qui affiche son identité et sa pile d'appels (code restauré
ensuite), deux inscriptions invalides successives donnent :

```
--- POST /api/auth/register (1)
MaxBytesValidator created 433582883
  ctor at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(...:325)
  ctor at org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory.getInstance(...:83)
  ctor at org.hibernate.validator.internal.engine.constraintvalidation.ClassBasedValidatorDescriptor.newInstance(...:82)
  ...
MaxBytesValidator.isValid on 433582883
--- POST /api/auth/register (2)
MaxBytesValidator.isValid on 433582883
```

L'instance est créée **pendant la première requête**, pas au démarrage, puis réutilisée (même numéro). C'est pour
cela qu'un `ConstraintValidator` ne doit garder aucun état propre à une requête dans ses champs : `max` ne change
jamais après `initialize`, c'est sans risque. Sans modifier le code, un point d'arrêt dans `initialize` montre la
même chose : il n'est atteint qu'à la première inscription.

**La source.** [Configuring Custom Constraints](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html#validation-beanvalidation-spring-constraints)
(Spring), [Creating custom constraints](https://docs.hibernate.org/validator/9.1/reference/en-US/html_single/#validator-customconstraints)
(Hibernate Validator).

### 4.5 L'interpolation : de `{task.title.required}` à "Title is required"

**Le mécanisme.** Transformer un gabarit de message en phrase s'appelle l'**interpolation**. Dans le projet, ce
n'est pas un seul objet mais une chaîne de trois, relevée dans le contexte :

```
messageInterpolator -> org.springframework.validation.beanvalidation.LocaleContextMessageInterpolator
   LocaleContextMessageInterpolator.targetInterpolator -> org.springframework.boot.validation.MessageSourceMessageInterpolator
   MessageSourceMessageInterpolator.messageSource -> ...AnnotationConfigServletWebServerApplicationContext
   MessageSourceMessageInterpolator.messageInterpolator -> org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator
context messageSource -> org.springframework.context.support.DelegatingMessageSource
```

1. `LocaleContextMessageInterpolator` (Spring) ajoute la **langue** de la requête en cours.
2. `MessageSourceMessageInterpolator` (Spring Boot) essaie d'abord de résoudre chaque `{clé}` dans le `MessageSource`
   de Spring, c'est-à-dire un éventuel `messages.properties`. Le projet n'en a pas : le `MessageSource` est un
   `DelegatingMessageSource` vide, la clé reste telle quelle.
3. `ResourceBundleMessageInterpolator` (Hibernate Validator) cherche alors la clé dans le bundle
   **`ValidationMessages`** à la racine du classpath (ton fichier), puis dans ses propres messages par défaut
   (`org/hibernate/validator/ValidationMessages_xx.properties`, dans son jar). Enfin il remplace les paramètres
   comme `{max}` par les attributs de l'annotation.

Le nom `ValidationMessages.properties` n'est donc pas une convention du projet : c'est le nom imposé par la
spécification. Renomme-le et toutes les réponses afficheraient `{task.title.required}`, accolades comprises.
`GlobalExceptionHandler` relit d'ailleurs le même fichier avec `ResourceBundle.getBundle("ValidationMessages")`
pour les clés `invalidFormat`, qui ne passent pas par Bean Validation.

**L'observer.**

- Les messages par défaut et leurs traductions :
  `unzip -l ~/.m2/repository/org/hibernate/validator/hibernate-validator/9.1.3.Final/hibernate-validator-9.1.3.Final.jar | grep ValidationMessages`
  liste une trentaine de langues, dont `ValidationMessages_fr.properties`.
- D'où le français de 4.3 : appelé hors requête HTTP, le validateur prend la langue de la JVM (ici `fr`), et
  `@NotBlank` sans `message` donne "ne doit pas être vide". Les messages du projet, eux, n'existent qu'en anglais :
  quelle que soit la langue, c'est la phrase anglaise qui sort.

**La source.** [Interpolating constraint error messages](https://docs.hibernate.org/validator/9.1/reference/en-US/html_single/#section-message-interpolation)
(Hibernate Validator).

### 4.6 L'exception levée et son trajet

**Le mécanisme.** Selon le chemin, l'erreur n'a pas la même forme :

| Chemin | Exception | Qui la traduit dans le projet | Réponse |
|---|---|---|---|
| `@Valid @RequestBody` (4.2) | `MethodArgumentNotValidException` | `GlobalExceptionHandler.handleMethodArgumentNotValid` | 400 `validation.failed`, avec `errors` |
| Contrainte sur un paramètre de contrôleur (4.3) | `HandlerMethodValidationException` | La classe mère `ResponseEntityExceptionHandler` | 400 `request.badRequest`, sans `errors` |
| Bean `@Validated` (4.3) | `ConstraintViolationException` | Personne de dédié : `handleUnexpected` | 500 `server.internalError` |

La dernière ligne se déduit de la liste des exceptions gérées par `ResponseEntityExceptionHandler`
(`ConstraintViolationException` n'y figure pas) : si un jour un service porte `@Validated`, il faudra ajouter un
handler, sinon une donnée invalide finira en 500.

Le trajet de `MethodArgumentNotValidException` est celui de toute exception de contrôleur : elle remonte au
`DispatcherServlet`, qui la confie à `ExceptionHandlerExceptionResolver`, qui trouve la méthode
`handleException` héritée de `ResponseEntityExceptionHandler`, laquelle aiguille vers ta surcharge
`handleMethodArgumentNotValid`. Détails dans [07. Exceptions](07-exceptions.md), section 4.

**L'observer.** Avec `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=DEBUG`,
pour `POST /api/tasks` avec `{}` :

```
Using @ExceptionHandler com.personal.task_tracker.commons.exception.GlobalExceptionHandler#handleException(Exception, WebRequest)
Resolved [org.springframework.web.bind.MethodArgumentNotValidException: Validation failed for argument [0] in public ... TaskController.createTask(...)
Completed 400 BAD_REQUEST
```

**La source.** [Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
(Spring MVC).

### 4.7 Le refaire toi-même : valider sans Spring

Pour voir qu'il n'y a pas de magie Spring dans la vérification elle-même, valide un DTO du projet avec la seule API
Jakarta. Crée ce fichier **hors du projet** (par exemple `~/mini-validation/MiniValidation.java`) :

```java
import com.personal.task_tracker.auth.dto.RegisterRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

public class MiniValidation {

	public static void main(String[] args) {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {   // ServiceLoader trouve Hibernate Validator
			Validator validator = factory.getValidator();
			System.out.println(factory.getClass().getName());

			RegisterRequestDto dto = new RegisterRequestDto("", "Doe", "jdoe", "pas-un-email", "é".repeat(40));
			for (ConstraintViolation<RegisterRequestDto> v : validator.validate(dto)) {
				System.out.println(v.getPropertyPath() + " | " + v.getMessageTemplate() + " | " + v.getMessage());
			}
		}
	}
}
```

Lance-le depuis `server/`, avec les classes compilées du projet et ses dépendances :

```bash
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "target/classes:$(cat /tmp/cp.txt)" ~/mini-validation/MiniValidation.java
```

Sortie réelle (l'ordre des lignes peut varier) :

```
org.hibernate.validator.internal.engine.ValidatorFactoryImpl
firstname | {user.firstname.required} | Firstname is required
password | {user.password.invalidSize} | Password must contain at least 8 characters and at most 72 bytes
email | {user.email.invalidFormat} | Email is not a valid email address
```

Tout y est : le moteur trouvé par `ServiceLoader` (4.1), `@MaxBytes` découvert seul (4.4), les messages lus dans
`ValidationMessages.properties` (4.5). Ce que Spring ajoute par-dessus : le déclenchement automatique par `@Valid`
(4.2), la transformation en `MethodArgumentNotValidException` puis en `ProblemDetail` (4.6), et l'injection dans
les validateurs maison.

**Expérience de suppression.** Relance l'application (ou un test) avec
`-Dspring.autoconfigure.exclude=org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration`.
Résultat vérifié : `POST /api/tasks` avec `{}` renvoie **toujours** le 400 complet, mais les beans
`defaultValidator` et `methodValidationPostProcessor` ont disparu. Explication : quand aucun validateur n'existe
dans le contexte, le `mvcValidator` (un `ValidatorAdapter`, déclaré par l'auto-configuration Web MVC) s'en
fabrique un. L'auto-configuration n'est donc pas ce qui fait
marcher `@Valid` dans les contrôleurs ; c'est la présence d'Hibernate Validator sur le classpath. Ce qu'elle
apporte, c'est un validateur partagé dans le contexte et la validation de méthode de 4.3.

---

## 5. À toi de jouer

1. **Observer la règle « une erreur par champ ».** Envoie `POST /api/tasks` avec un titre composé de 60 espaces et
   des valeurs valides pour le reste. **À observer** : une seule erreur sur `#/title`, de code `task.title.required`,
   pas `invalidSize`.
2. **Changer une limite.** Dans `TaskRequestDto`, passe `@Size(max = 50)` du titre à `max = 30` (sur une branche
   d'essai), relance l'application et envoie un titre de 40 caractères. **À observer** : le message dit « at most 30
   characters » sans que tu aies touché au fichier de messages. Remets la valeur ensuite : la colonne SQL fait 50.
3. **Casser le test de cohérence.** Dans `TaskRequestDto`, remplace temporairement le message du `@Size` de
   `description` par `message = "Too long"`, puis lance `./mvnw test -Dtest=ValidationMessagesTest`. **À observer** :
   l'échec liste précisément `TaskRequestDto.description @Size: message is not a key between braces: Too long`.
   Annule la modification. (N'ajoute pas un second `@Size` : deux contraintes identiques sont regroupées dans une
   annotation conteneur `@Size.List`, que le test ne parcourt pas, et rien n'échouerait.)
4. **Anticiper le login.** Le guide JWT ajoute un `@RequestBody @Valid LoginRequestDto`. D'après
   `ValidationMessagesTest`, que faudra-t-il ajouter en même temps ? **À observer** : l'entrée dans `DOMAINS`, les
   clés `user.*` ou `auth.*` de ses contraintes, et ses clés `invalidFormat`.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Aucune contrainte n'est vérifiée | `@Valid` oublié sur le paramètre | Ajouter `@Valid` devant `@RequestBody` |
| Un champ obligatoire vide passe | Seulement `@Size` ou `@Email`, qui acceptent `null` | Ajouter `@NotBlank` / `@NotNull` |
| `"   "` accepté comme titre | `@NotNull` ou `@NotEmpty` au lieu de `@NotBlank` | `@NotBlank` pour du texte |
| Le message affiché est `{task.title.required}` avec les accolades | Clé absente de `ValidationMessages.properties` | Ajouter la clé (le test de cohérence le détecte) |
| `@NonNull` ne bloque rien | Annotation jspecify, faite pour l'analyse statique, pas pour Bean Validation | Utiliser `jakarta.validation.constraints.NotNull` / `NotBlank` |
| Validateur maison qui lève `NullPointerException` | `isValid` ne gère pas `null` | Renvoyer `true` pour `null` |
| Un nouveau DTO de requête fait échouer `ValidationMessagesTest` | DTO absent de `GlobalExceptionHandler.DOMAINS` | L'y déclarer, avec ses clés de messages |

---

## 7. Pour aller plus loin

- **Validation en dehors des controllers** : `@Validated` sur un bean Spring permet de valider les paramètres de ses
  méthodes, grâce à un proxy (section 4.3). Sur une classe `@ConfigurationProperties`, le même `@Validated` sert à
  autre chose : valider la configuration au démarrage (voir [03. Configuration](03-configuration.md)).
- **Groupes de validation** : appliquer des règles différentes selon le contexte (création, modification).
- **Contraintes de classe** : vérifier une règle entre plusieurs champs (« date de fin après date de début »).
- **`@Valid` en cascade** : valider aussi un objet imbriqué dans le DTO.
- **Internationalisation** ⏳ : des fichiers `ValidationMessages_fr.properties` donneraient des phrases en français ;
  ici le choix est de laisser la traduction au front grâce au `code`.
- **Docs officielles** : en plus des liens de la section 4, le
  [Hibernate Validator Reference Guide](https://docs.hibernate.org/validator/9.1/reference/en-US/html_single/) pour la
  liste complète des contraintes.

---

## 8. Quiz

**1. Un champ porte uniquement `@Size(max = 255)`. Le client ne l'envoie pas. Que se passe-t-il ?**
<details><summary>Réponse</summary>

Rien : `@Size` accepte `null`. Le champ est optionnel. Pour le rendre obligatoire, ajouter `@NotBlank`.
</details>

**2. Que se passe-t-il si on retire `@Valid` dans `TaskController.createTask` ?**
<details><summary>Réponse</summary>

Les contraintes ne sont plus vérifiées. Un titre vide atteint le service, et c'est la base (`NOT NULL`, `VARCHAR(50)`)
qui refusera certaines valeurs, avec une erreur 500 au lieu d'un 400 clair.
</details>

**3. Pourquoi `@MaxBytes` existe-t-il alors que `@Size(max = 72)` existe déjà ?**
<details><summary>Réponse</summary>

`@Size` compte des caractères, BCrypt est limité en octets. Un caractère accentué occupe deux octets en UTF-8 : 40
caractères peuvent faire 80 octets.
</details>

**4. Dans une erreur de validation, à quoi servent `code` et `detail` ?**
<details><summary>Réponse</summary>

`code` est une clé stable (`task.title.required`) que le front traduit dans la langue de l'utilisateur. `detail`
est une phrase anglaise lisible par un développeur.
</details>

**5. Pourquoi garder la contrainte `UNIQUE` sur l'email en base alors que le service vérifie déjà
`existsByEmail` ?**
<details><summary>Réponse</summary>

Deux inscriptions simultanées peuvent passer la vérification en même temps. Seule la contrainte de la base garantit
l'unicité ; le service traduit ensuite l'erreur en 409.
</details>
