# 06. Validation

**Ce que tu vas comprendre**
- Comment Bean Validation vérifie les données reçues avec de simples annotations
- Le rôle indispensable de `@Valid`
- Comment les messages d'erreur sont externalisés et transformés en codes pour le front
- Comment écrire ta propre contrainte (l'exemple de `@MaxBytes`)
- Pourquoi on valide à la fois dans l'API et dans la base

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

(les propriétés standard `type`, `title` et `instance` sont aussi présentes ; l'ordre des erreurs peut varier).

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

## 4. À toi de jouer

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

## 5. Pièges fréquents

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

## 6. Pour aller plus loin

- **Validation en dehors des controllers** : `@Validated` sur un bean Spring permet de valider les paramètres de ses
  méthodes (utile pour `@ConfigurationProperties`, voir [03. Configuration](03-configuration.md)).
- **Groupes de validation** : appliquer des règles différentes selon le contexte (création, modification).
- **Contraintes de classe** : vérifier une règle entre plusieurs champs (« date de fin après date de début »).
- **`@Valid` en cascade** : valider aussi un objet imbriqué dans le DTO.
- **Internationalisation** ⏳ : des fichiers `ValidationMessages_fr.properties` donneraient des phrases en français ;
  ici le choix est de laisser la traduction au front grâce au `code`.
- Documentation : *Jakarta Bean Validation specification* ; *Hibernate Validator Reference Guide* (liste complète
  des contraintes, interpolation des messages) ; *Spring Framework Reference*, « Validation ».

---

## 7. Quiz

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
