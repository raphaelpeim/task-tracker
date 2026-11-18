# 04. Architecture en couches

**Ce que tu vas comprendre**
- Pourquoi le code est découpé en controller, service et repository, et ce qui va dans chacun
- Le trajet complet d'une requête HTTP, du client jusqu'à la base et retour
- La différence entre une entité et un DTO, et le rôle du mapper
- Pourquoi les packages sont organisés par fonctionnalité (`task/`, `user/`, `auth/`)
- Ce que Spring impose (presque rien), le seul mécanisme réel lié aux couches (la traduction d'exceptions), et ce
  qui casse concrètement quand on les mélange, observé sur le projet

**Prérequis** : [02. Beans et injection](02-beans-injection.md). Utile ensuite : [05. Web et REST](05-web-rest.md),
[08. Persistance](08-persistance.md), [09. Transactions](09-transactions.md).

---

## 1. Le problème que ça résout

Imagine une seule méthode qui lit le JSON reçu, vérifie les champs, applique les règles métier, écrit le SQL à la
main, puis fabrique la réponse. Elle marche. Mais :

- pour changer une règle métier, tu dois relire du code HTTP et du SQL ;
- pour tester la règle, tu dois simuler une requête HTTP et une base ;
- le jour où la même règle est utile ailleurs (un traitement planifié, un autre end-point), tu la copies.

**Analogie** : un restaurant. Le serveur (controller) prend la commande et rapporte l'assiette ; il ne cuisine pas.
Le cuisinier (service) sait faire les plats ; il ne parle pas aux clients. Le magasinier (repository) range et
ressort les ingrédients ; il ne sait pas ce qu'est une recette. Chacun peut changer de méthode de travail sans
déranger les autres, tant que ce qu'ils s'échangent reste le même.

C'est exactement le découpage du task-tracker.

---

## 2. Les concepts

### 2.1 Les trois couches

| Couche | Annotation | Responsabilité | Ne doit **pas** |
|---|---|---|---|
| **Controller** | `@RestController` | Traduire HTTP ↔ Java : URL, verbe, corps, code de statut | Contenir une règle métier, appeler un repository |
| **Service** | `@Service` | Règles métier, orchestration, transactions | Connaître HTTP (`ResponseEntity`, codes de statut) |
| **Repository** | `@Repository` | Lire et écrire en base | Contenir une décision métier |

Le sens des appels est **toujours** le même :

```
Controller  ──►  Service  ──►  Repository  ──►  Base de données
```

Un service peut appeler un autre service (c'est même la bonne façon de réutiliser une règle), mais une couche du bas
n'appelle jamais une couche du haut.

### 2.2 Entité, DTO, mapper

- **Entité** (`@Entity`) : l'objet Java qui correspond à une ligne de table. Elle reflète la base.
- **DTO** (*Data Transfer Object*) : l'objet qui correspond au JSON échangé avec le client. Il reflète l'API.
- **Mapper** : la petite classe qui convertit l'un en l'autre.

Pourquoi ne pas renvoyer directement l'entité ?

1. **Sécurité** : l'entité `AppUser` contient le hash du mot de passe. Le DTO `UserResponseDto` ne l'a pas : il est
   impossible de le renvoyer par erreur.
2. **Découplage** : tu peux renommer une colonne sans casser le front, ou ajouter un champ calculé à l'API sans
   toucher à la table.
3. **Contrôle des entrées** : le client ne peut envoyer que ce que le DTO de requête prévoit. Il ne peut pas fixer
   l'`id`, la date de création, ni son propre rôle.

C'est pour ça qu'il y a souvent **deux** DTO par ressource : un pour la requête (ce que le client envoie) et un
pour la réponse (ce que le serveur renvoie).

### 2.3 Package par fonctionnalité

Deux façons de ranger les classes :

```
Par couche                       Par fonctionnalité (ce projet)
controllers/                     task/
  TaskController                   controller/  service/  repository/  dto/ ...
  UserController                 user/
services/                          controller/  service/  repository/  dto/ ...
  TaskService                    auth/
  UserService                    commons/
```

Par fonctionnalité, tout ce qui concerne les tâches est au même endroit. Quand le projet grossit, c'est bien plus
facile à parcourir, et on voit tout de suite ce qu'une fonctionnalité dépend d'une autre (ici `task` dépend de
`user` pour l'assigné). `commons/` regroupe ce qui est transverse : sécurité et gestion d'erreurs.

### 2.4 Le trajet d'une requête, en détail

Suivons `PATCH /api/tasks/1` avec le corps `{"status":"DONE"}`.

```
Client HTTP
   │  PATCH /api/tasks/1   Content-Type: application/json   {"status":"DONE"}
   ▼
① Tomcat (serveur embarqué dans le jar)
   │  lit la requête réseau, la transforme en objet HttpServletRequest
   ▼
② Chaîne de filtres Spring Security               commons/config/SecurityConfig.java
   │  aujourd'hui tout est permitAll() ; demain, vérification du token JWT
   │  en cas de refus : 401/403 écrits ICI par SecurityProblemHandler,
   │  la requête n'atteint jamais les étapes suivantes
   ▼
③ DispatcherServlet
   │  cherche quelle méthode correspond à "PATCH" + "/api/tasks/{taskId}"
   │  aucune ? → 404 ; l'URL existe mais pas pour ce verbe ? → 405
   ▼
④ Conversion des paramètres
   │  "1" (texte de l'URL) → Long taskId        échec ("abc") → 400
   │  JSON → TaskRequestPartialDto (Jackson)    JSON cassé ou mauvais type → 400
   ▼
⑤ Validation (@Valid)                              task/dto/TaskRequestPartialDto.java
   │  contraintes @NotBlank, @Size... violées → 400 avec la liste des champs
   ▼
⑥ TaskController.updatePartialTask                 task/controller/TaskController.java
   │  délègue au service, ne décide rien
   ▼
⑦ TaskService.updatePartialTask                    task/service/TaskService.java
   │  @Transactional : une transaction s'ouvre
   │  findTaskById → introuvable ? TaskNotFoundException
   ▼
⑧ TaskRepository.findById                          task/repository/TaskRepository.java
   │  Hibernate génère et exécute : SELECT ... FROM task WHERE id = 1
   ▼
⑨ TaskMapper.applyPartialUpdate                    task/mapper/TaskMapper.java
   │  ne modifie que les champs présents dans le JSON (ici status)
   ▼
⑩ repository.flush()
   │  Hibernate voit que l'entité a changé et exécute l'UPDATE
   ▼
⑪ TaskMapper.toTaskResponseDto, encore dans le service
   │  entité → DTO de réponse, tant que la transaction est ouverte
   │  puis sortie de la méthode : la transaction est validée (commit)
   ▼
⑫ Jackson : DTO → JSON, puis ResponseEntity → code 200
   ▼
Client : 200 OK + {"id":1, "title":..., "status":"DONE", ...}

Exception à n'importe quelle étape de ③ à ⑫
   → GlobalExceptionHandler → réponse ProblemDetail (fiche 07)
```

Si tu sais redessiner ce schéma de tête, tu sais où chercher n'importe quel bug : un 400 vient de ④ ou ⑤, un 404
« tâche introuvable » de ⑦, une donnée mal enregistrée de ⑨ ou ⑩, un champ manquant dans la réponse de ⑪.

---

## 3. Dans le projet

### Le controller ne fait que traduire

`server/src/main/java/com/personal/task_tracker/task/controller/TaskController.java:54-61`

```java
@PatchMapping("/{taskId}")                                   // verbe + URL
public ResponseEntity<TaskResponseDto> updatePartialTask(
		@PathVariable Long taskId,                           // morceau d'URL → Long
		@Valid @RequestBody TaskRequestPartialDto taskDto    // corps JSON → DTO validé
) {
	TaskResponseDto updateTaskDto = taskService.updatePartialTask(taskId, taskDto);  // tout le travail est ici
	return new ResponseEntity<>(updateTaskDto, HttpStatus.OK);                       // choix du code HTTP
}
```

Deux lignes de corps. Aucune condition, aucun accès base. Le seul savoir propre au controller est le code de statut.

### Le service porte les règles

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java:107-125`

```java
@Transactional
public TaskResponseDto updatePartialTask(Long taskId, TaskRequestPartialDto taskDto) {
	Task task = findTaskById(taskId);                    // 404 si absente

	TaskMapper.applyPartialUpdate(task, taskDto);

	if (taskDto.assigneeId().isPresent()) {              // le champ était-il dans le JSON ?
		if (taskDto.assigneeId().get() == null) {        // présent et null : on désassigne
			task.setAssignee(null);
		} else {                                         // présent avec une valeur : on vérifie l'utilisateur
			AppUser user = userService.findUserById(taskDto.assigneeId().get());
			task.setAssignee(user);
		}
	}

	repository.flush();

	return TaskMapper.toTaskResponseDto(task);           // le service rend un DTO, pas l'entité
}
```

Note `userService.findUserById` : pour vérifier l'assigné, `TaskService` passe par **le service** utilisateur, pas
par `UserRepository`. La règle « utilisateur introuvable → `UserNotFoundException` » n'existe ainsi qu'à un endroit
(`user/service/UserService.java`, méthode `findUserById`).

### Le repository ne contient pas de logique

`server/src/main/java/com/personal/task_tracker/task/repository/TaskRepository.java:12-21`

```java
@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

	/**
	 * Loads the assignee in the same query: the mapper reads it for every task
	 */
	@Override
	@EntityGraph(attributePaths = "assignee")
	@NonNull List<Task> findAll(@NonNull Sort sort);
}
```

C'est une interface : Spring en génère l'implémentation. La seule chose qu'on y décide est **comment** charger les
données (ici, l'assigné dans la même requête SQL). Voir [08. Persistance](08-persistance.md).

### DTO de requête et DTO de réponse

`server/src/main/java/com/personal/task_tracker/task/dto/TaskRequestDto.java:10-28` : `title`, `description`,
`type`, `status`, `priority`, `assigneeId`. Pas d'`id`, pas de dates : le client ne les choisit pas.

`server/src/main/java/com/personal/task_tracker/task/dto/TaskResponseDto.java:10-20` : on y retrouve `id`,
`createdDate`, `updatedDate`, et l'assigné sous forme d'objet `UserResponseDto` complet au lieu d'un simple id.

`server/src/main/java/com/personal/task_tracker/user/dto/UserResponseDto.java:3-9` : `id`, `firstname`,
`lastname`, `username`, `email`. Ni mot de passe, ni rôle.

### Le mapper

`server/src/main/java/com/personal/task_tracker/task/mapper/TaskMapper.java:25-37`

```java
public static TaskResponseDto toTaskResponseDto(Task task) {
	return new TaskResponseDto(
			task.getId(),
			...
			task.getAssignee() != null ? UserMapper.toUserResponseDto(task.getAssignee()) : null,
			...
	);
}
```

Un mapper réutilise les autres mappers (`UserMapper`) plutôt que de recopier la conversion. Il est écrit à la main,
en méthodes statiques, dans une classe au constructeur privé (`TaskMapper.java:11-12`) : on ne peut pas
l'instancier, c'est une boîte à outils.

### Un service qui en appelle un autre : l'inscription

`server/src/main/java/com/personal/task_tracker/auth/service/AuthService.java` : `register` convertit le DTO
d'inscription en `UserCreateDto` (`AuthMapper.toUserCreateDto`), appelle `userService.createUser`, puis convertit le
résultat. `auth` gère « s'inscrire », `user` gère « créer un utilisateur valide » : deux responsabilités, deux
services.

---

## 4. Sous le capot

Contrairement aux fiches 02 et 03, il y a peu de "magie" ici : l'architecture en couches est une **convention**, et
Spring n'en impose presque rien. Cette section montre ce que Spring ne vérifie pas, le seul mécanisme réel attaché à
une couche (la traduction d'exceptions), puis **ce qui casse concrètement** quand on mélange les couches. Chaque
observation a été faite sur le projet avec un test temporaire qui étend `IntegrationTest` (méthode décrite dans
[03. Configuration](03-configuration.md), section 4), puis supprimé.

### 4.1 Ce que Spring impose : presque rien

**Le mécanisme.** `@Service`, `@Repository` et `@RestController` sont tous des `@Component` pour le scan (voir
[02. Beans et injection](02-beans-injection.md), section 2.2). Spring construit le graphe des beans
(02, section 4.1) quel que soit le sens des flèches : un controller qui demande un `TaskRepository` dans son
constructeur le reçoit, sans erreur ni avertissement. La règle "Controller → Service → Repository" n'existe que dans
ta tête et en revue de code. La seule chose que Spring refuse est le **cycle** (02, section 2.8), ce qui protège en
partie le sens des dépendances entre services : puisque `TaskService` dépend de `UserService`, l'inverse est
impossible par constructeur.

**L'observer.** Expérience faite sur le projet : retire `@Repository` de `UserRepository`. Le contexte démarre, le
repository est toujours un bean et fonctionne. Spring Data reconnaît ses repositories à leur **type** (une interface
qui étend `JpaRepository`), pas à l'annotation. Le log de démarrage le dit (extrait réel de
`./mvnw spring-boot:run`) :

```
Bootstrapping Spring Data JPA repositories in DEFAULT mode.
Finished Spring Data repository scanning in 22 ms. Found 2 JPA repository interfaces.
```

Sur `TaskRepository` et `UserRepository`, `@Repository` est donc redondant pour Spring : il documente le rôle de la
classe. Remets-le après l'essai.

**La source.** [Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html),
[Spring Data, Core concepts](https://docs.spring.io/spring-data/jpa/reference/repositories/core-concepts.html).

### 4.2 La traduction d'exceptions : la seule "magie" de la couche repository

**Le mécanisme.** Quand PostgreSQL refuse une ligne, l'erreur traverse trois couches techniques avant d'arriver dans
ton code :

1. le driver JDBC lève une `org.postgresql.util.PSQLException` ;
2. Hibernate l'enveloppe dans sa propre `org.hibernate.exception.ConstraintViolationException` ;
3. Spring l'enveloppe à son tour dans une `org.springframework.dao.DataIntegrityViolationException`, qui fait partie
   d'une hiérarchie (`DataAccessException`) **indépendante de la technologie**.

L'étape 3 est faite par un proxy autour du repository, qui contient un `PersistenceExceptionTranslationInterceptor`.
Celui-ci interroge les traducteurs disponibles, ici `HibernateJpaDialect`, qui délègue à
`HibernateExceptionTranslator`. Pour une classe que tu écrirais toi-même et annoterais `@Repository`, c'est le
`BeanPostProcessor` `PersistenceExceptionTranslationPostProcessor` (présent dans le contexte du projet) qui pose ce
proxy, sur le principe vu en 02, section 4.4. Pour une interface Spring Data, Spring Data l'ajoute lui-même au proxy
qu'il génère (02, section 4.5).

**L'observer.** Le test temporaire enregistre deux utilisateurs avec le même email via
`userRepository.saveAndFlush(...)` et affiche la chaîne des causes, puis les lignes de la pile qui contiennent
"Translat" ou "JpaDialect". Sortie réelle :

```
org.springframework.dao.DataIntegrityViolationException : could not execute statement [ERROR: duplicate key value violates unique constraint "app_user_email_key"
org.hibernate.exception.ConstraintViolationException : could not execute statement [ERROR: duplicate key value violates unique constraint "app_user_email_key"
org.postgresql.util.PSQLException : ERROR: duplicate key value violates unique constraint "app_user_email_key"
  at org.springframework.orm.jpa.hibernate.HibernateExceptionTranslator.convertHibernateAccessException(HibernateExceptionTranslator.java:169)
  ...
  at org.springframework.orm.jpa.vendor.HibernateJpaDialect.translateExceptionIfPossible(HibernateJpaDialect.java:223)
  at org.springframework.dao.support.ChainedPersistenceExceptionTranslator.translateExceptionIfPossible(ChainedPersistenceExceptionTranslator.java:61)
  at org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(PersistenceExceptionTranslationInterceptor.java:157)
```

Et avec `@Repository` retiré de `UserRepository` (4.1) ? **Exactement la même chaîne** : pour un repository Spring
Data, la traduction ne dépend pas de l'annotation.

**Pourquoi c'est nécessaire.** Regarde `UserService.createUser`
(`server/src/main/java/com/personal/task_tracker/user/service/UserService.java:81-92`) : il attrape
`DataIntegrityViolationException`, un type Spring, pour transformer un doublon en `UserAlreadyExistsException`. Sans
la traduction, le service devrait attraper une exception de PostgreSQL : la couche métier dépendrait du driver, et
changer de base casserait du code métier. Note l'entorse assumée ligne 84 : pour lire le **nom** de la contrainte,
le service descend quand même jusqu'à la cause Hibernate. La traduction couvre le cas général, pas tous les détails.

Corollaire : un `catch (PSQLException e)` dans un service ne se déclencherait **jamais**, l'exception arrive déjà
traduite.

**La source.** [Consistent Exception Hierarchy](https://docs.spring.io/spring-framework/reference/data-access/dao.html#dao-exceptions),
[Annotations Used to Configure DAO or Repository Classes](https://docs.spring.io/spring-framework/reference/data-access/dao.html#dao-annotations),
[Exception Translation (ORM)](https://docs.spring.io/spring-framework/reference/data-access/orm/general.html#orm-exception-translation).

### 4.3 Pourquoi le mapping se fait dans le service : le proxy Hibernate

**Le mécanisme.** `Task.assignee` est déclaré `@ManyToOne(fetch = FetchType.LAZY)`
(`server/src/main/java/com/personal/task_tracker/task/entity/Task.java:38-41`). Quand Hibernate charge une tâche,
il ne charge pas l'utilisateur : il met à sa place un **proxy**, une sous-classe d'`AppUser` générée, qui ne connaît
que l'identifiant. Au premier accès à ses données, le proxy demande à la session Hibernate de faire le `SELECT`. Or
cette session ne vit que le temps de la transaction ouverte par le service (`@Transactional`, voir 02, section 4.4),
et `spring.jpa.open-in-view=false` interdit de la prolonger jusqu'au controller.

**L'observer.** Le test temporaire charge une tâche assignée avec `taskRepository.findById(...)` **hors de toute
transaction de service**, comme le ferait un controller qui court-circuiterait la couche service, puis appelle
`TaskMapper.toTaskResponseDto`. Sortie réelle :

```
assignee class = com.personal.task_tracker.user.entity.AppUser$HibernateProxy
org.hibernate.LazyInitializationException : Could not initialize proxy [com.personal.task_tracker.user.entity.AppUser#1] - no session
```

C'est pour ça que l'étape ⑪ du schéma de 2.4 (entité → DTO) a lieu **dans** le service. Détails et autres
solutions dans [09. Transactions](09-transactions.md).

### 4.4 Pourquoi un DTO : ce que Jackson ferait d'une entité

**Le mécanisme.** Jackson (le bean `jacksonJsonMapper`, un `JsonMapper` de Jackson 3 créé par l'auto-configuration)
transforme en propriété JSON **chaque getter public**. Il ne sait pas ce qui est sensible, ni ce qui est un proxy
Hibernate.

**L'observer.** Le test temporaire passe directement les entités au `JsonMapper` du contexte. Sortie réelle (le hash
est une valeur factice créée pour l'essai) :

```
{"firstname":"Ada","lastname":"Lovelace","username":"ada","email":"ada@example.com","password":"$2a$10$hashFictif","role":"USER","createdDate":"2026-10-29T15:10:43.137805","id":1,"updatedDate":null}
```

Et pour une `Task` dont l'assigné est le proxy de 4.3 :

```
tools.jackson.databind.DatabindException : Could not initialize proxy [com.personal.task_tracker.user.entity.AppUser#1] - no session
org.hibernate.LazyInitializationException : Could not initialize proxy [com.personal.task_tracker.user.entity.AppUser#1] - no session
```

Les raisons de 2.2 (sécurité, découplage) ne sont donc pas qu'une question de style : renvoyer `AppUser` publierait
le hash et le rôle, et renvoyer `Task` entraînerait les proxies Hibernate dans la sérialisation.

**Et les mappers ?** Aucun mécanisme Spring : des méthodes statiques en Java pur (`TaskMapper.java:11-12`, le
constructeur privé). Il n'y a rien à observer, et c'est voulu : c'est le seul maillon de la chaîne entièrement
explicite. Une bibliothèque comme MapStruct génère ce même code à la compilation à partir d'une interface.

**La source.** [Spring Boot, JSON](https://docs.spring.io/spring-boot/reference/features/json.html),
[JPA (Spring Framework)](https://docs.spring.io/spring-framework/reference/data-access/orm/jpa.html).

### 4.5 Récapitulatif : ce qui casse si on mélange les couches

| Mélange | Ce qui se passe réellement | Vu en |
|---|---|---|
| Un controller injecte directement un repository | Rien : Spring l'accepte sans un mot, seule la revue de code le voit | 4.1 |
| Le controller charge l'entité lui-même et la convertit | `LazyInitializationException` sur l'assigné | 4.3 |
| Le controller renvoie l'entité | Hash du mot de passe dans le JSON ; erreur Jackson sur le proxy | 4.4 |
| Le service attrape une exception du driver (`PSQLException`) | Le `catch` ne se déclenche jamais, l'exception arrive traduite | 4.2 |
| `UserService` injecte `TaskService`, qui injecte déjà `UserService` | Refus de démarrer : dépendance circulaire | 02, exercice 3 |

---

## 5. À toi de jouer

1. **Suivre une requête au débogueur.** Lance l'application (`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
   depuis `server/`, ou en debug dans l'IDE). Mets un point d'arrêt dans `TaskController.updatePartialTask`, un dans
   `TaskService.updatePartialTask`, un dans `TaskMapper.applyPartialUpdate`. Envoie un `PATCH` sur une tâche
   existante. **À observer** : l'ordre d'arrêt correspond aux étapes ⑥ ⑦ ⑨ du schéma ; dans la pile d'appels de
   l'IDE, tu vois au-dessus du controller `DispatcherServlet` et les filtres.
2. **Repérer une fuite potentielle.** Ouvre `user/entity/AppUser.java` et `user/dto/UserResponseDto.java`, liste
   les champs présents dans l'un et pas dans l'autre. **À observer** : `password`, `role`, `createdDate`,
   `updatedDate`. Demande-toi pour chacun s'il serait grave de l'exposer.
3. **Dessiner les dépendances.** Pour chaque classe de `task/`, note les classes d'autres packages qu'elle importe.
   **À observer** : `task` dépend de `user` (assigné), jamais l'inverse. Que se passerait-il si `UserService`
   importait `TaskService` ? (Indice : [02. Beans et injection](02-beans-injection.md), dépendance circulaire.)
4. **Placer une nouvelle règle.** « Une tâche au statut `CLOSED` ne peut plus être modifiée. » Sans l'écrire, décide
   dans quelle classe et quelle méthode elle irait, et quelle exception elle lèverait. **À observer** : si ta réponse
   est le controller ou le mapper, relis la section 2.1.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| Le hash du mot de passe apparaît dans une réponse | Une entité est renvoyée directement | Toujours renvoyer un DTO de réponse |
| Une même vérification est écrite dans deux services | Un service appelle le repository d'un autre domaine | Passer par le service de ce domaine (`userService.findUserById`) |
| Un controller de 50 lignes avec des `if` | Logique métier dans la couche HTTP | Déplacer dans le service, garder le controller à 2-3 lignes |
| Un service renvoie `ResponseEntity` | Le service connaît HTTP | Le service renvoie un DTO ou lève une exception métier ; le code HTTP se décide dans le controller ou le handler d'erreurs |
| `LazyInitializationException` en construisant la réponse | Conversion entité → DTO faite hors transaction | Mapper dans le service (voir [09. Transactions](09-transactions.md)) |
| Le client arrive à modifier un champ qu'il ne devrait pas | Le DTO de requête contient ce champ | Retirer le champ du DTO de requête |

---

## 7. Pour aller plus loin

- **MapStruct** : génère les mappers à la compilation à partir d'une interface. Utile quand les DTO deviennent
  nombreux ; ce projet préfère des mappers écrits à la main, plus explicites.
- **Architecture hexagonale / clean architecture** : pousse le découplage plus loin, le métier ne dépend plus du
  tout de Spring ni de JPA (voir [fiche inventaire, section 16](notions-spring-boot.md#16-au-delà-de-spring)).
- **Événements Spring** ⏳ (`ApplicationEventPublisher`) : permettent à deux domaines de communiquer sans
  s'importer mutuellement.
- **Faire respecter les couches automatiquement** : puisque Spring ne vérifie rien (4.1), des bibliothèques de test
  comme [ArchUnit](https://www.archunit.org/) permettent d'écrire "aucune classe de `controller` n'importe un
  `repository`" et de faire échouer le build sinon. Non utilisé dans ce projet.
- Documentation : en plus des liens de la section 4, *Spring Boot Reference*,
  [Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) ; *Spring
  Framework Reference*, chapitre [Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html).

---

## 8. Quiz

**1. Dans quelle couche décide-t-on qu'une création renvoie 201 plutôt que 200 ?**
<details><summary>Réponse</summary>

Dans le controller (`TaskController.createTask` renvoie `HttpStatus.CREATED`). Le code de statut est une notion
HTTP, le service n'a pas à la connaître.
</details>

**2. Pourquoi `TaskRequestDto` n'a-t-il pas de champ `id` ?**
<details><summary>Réponse</summary>

Parce que c'est la base qui génère l'identifiant à la création, et qu'en modification l'id est porté par l'URL
(`/api/tasks/{taskId}`). Le laisser dans le corps permettrait au client d'envoyer un id incohérent.
</details>

**3. `TaskService` a besoin de vérifier qu'un utilisateur existe. Pourquoi injecte-t-il `UserService` et pas
`UserRepository` ?**
<details><summary>Réponse</summary>

Pour réutiliser la règle existante (« introuvable → `UserNotFoundException` ») au lieu de la réécrire. Chaque domaine
reste propriétaire de ses données et de ses règles.
</details>

**4. Une réponse JSON contient un champ inattendu. Dans quelle classe regarder en premier ?**
<details><summary>Réponse</summary>

Le DTO de réponse (ce qu'il déclare est ce qui est sérialisé), puis le mapper qui le remplit.
</details>

**5. Quel est l'intérêt de ranger le code par fonctionnalité plutôt que par couche ?**
<details><summary>Réponse</summary>

Tout ce qui concerne une fonctionnalité est au même endroit, ce qui facilite la lecture et l'évolution, et les
dépendances entre fonctionnalités deviennent visibles dans les imports.
</details>
