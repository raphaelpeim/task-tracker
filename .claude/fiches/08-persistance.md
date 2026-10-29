# 08. Persistance : JPA, Hibernate, Spring Data, Flyway

**Ce que tu vas comprendre**
- Comment un objet Java devient une ligne de table, et inversement (ORM, JPA, Hibernate).
- Comment Spring Data écrit les requêtes à ta place à partir d'une simple interface.
- Pourquoi les relations entre entités se chargent "paresseusement", et le piège du N+1 qui en découle.
- Pourquoi modifier une entité suffit à mettre la base à jour, sans appeler `save`.
- Comment Flyway fait évoluer le schéma de la base de façon sûre et reproductible.
- Ce qui se passe réellement dans le projet, logs et SQL à l'appui : l'ordre Flyway puis Hibernate, le pool de
  connexions, le contexte de persistance, le proxy LAZY, les méthodes dérivées, et un mini-Hibernate à écrire.

**Prérequis** : [02. Beans et injection](02-beans-injection.md), [04. Architecture en couches](04-architecture-couches.md).
La fiche [09. Transactions](09-transactions.md) complète celle-ci : les deux se lisent ensemble.

---

## 1. Le problème que ça résout

Ton application manipule des **objets** : une `Task` a un titre, un statut, un `AppUser` assigné. La base de données,
elle, stocke des **lignes** dans des **tables**, reliées par des **clés étrangères**. Les deux mondes ne parlent pas
la même langue.

Sans outil, il faudrait écrire à la main, pour chaque opération :

```java
// Ce que tu écrirais sans JPA (JDBC "brut")
PreparedStatement ps = connection.prepareStatement(
		"SELECT id, title, status, assignee_id FROM task WHERE id = ?");
ps.setLong(1, taskId);
ResultSet rs = ps.executeQuery();
if (rs.next()) {
	Task task = new Task();
	task.setId(rs.getLong("id"));
	task.setTitle(rs.getString("title"));
	task.setStatus(TaskStatus.valueOf(rs.getString("status")));
	// ... et encore une requête pour charger l'assigné
}
```

Multiplie ça par chaque entité et chaque requête : c'est long, répétitif et source d'erreurs (une colonne oubliée,
un `null` mal géré).

**L'analogie** : un ORM (Object-Relational Mapping) est un **traducteur** assis entre ton code et la base. Tu lui
parles en objets ("donne-moi la tâche 1", "voici une nouvelle tâche"), il traduit en SQL et te rend des objets.

Deuxième problème : la **structure** de la base (tables, colonnes, contraintes) évolue avec l'application. Il faut
que chaque développeur, chaque environnement de test et la production aient exactement le même schéma. C'est le
rôle de **Flyway** : un historique de scripts SQL appliqués dans l'ordre, comme un git pour le schéma.

---

## 2. Les concepts

### 2.1 Qui fait quoi : JPA, Hibernate, Spring Data

Trois noms reviennent sans cesse ; ils s'empilent :

```
Ton code (TaskService)
   │  repository.findById(1)
   ▼
Spring Data JPA      → génère l'implémentation de tes interfaces Repository
   ▼
JPA (Jakarta Persistence) → la SPÉCIFICATION : annotations @Entity, @Id..., interface EntityManager
   ▼
Hibernate            → l'IMPLÉMENTATION de JPA : génère le SQL, gère le cache, le suivi des entités
   ▼
JDBC + driver PostgreSQL → envoie le SQL à la base
   ▼
PostgreSQL
```

- **JPA** est un contrat (des interfaces et des annotations dans `jakarta.persistence`). Il ne fait rien seul.
- **Hibernate** est le moteur qui respecte ce contrat. Spring Boot le choisit par défaut.
- **Spring Data JPA** ajoute une couche de confort : tu écris une interface, il écrit le code.

Tout cela arrive avec le starter `spring-boot-starter-data-jpa` (voir [01. Maven et pom.xml](01-maven-pom.md)).

### 2.2 L'entité : une classe qui représente une table

```java
@Entity                       // "cette classe correspond à une table"
public class Task {
	@Id                                                   // clé primaire
	@GeneratedValue(strategy = GenerationType.IDENTITY)   // valeur générée par la base
	private Long id;

	@Column(name = "title", length = 50, nullable = false)
	private String title;
	...
}
```

Par défaut, la table porte le nom de la classe (`task`) et chaque champ devient une colonne. `@Column` précise le
nom, la longueur, la nullabilité.

**`IDENTITY`** veut dire : "laisse la base choisir l'id" (ici le `BIGSERIAL` de PostgreSQL, un compteur
auto-incrémenté). Conséquence : Hibernate doit exécuter l'`INSERT` **tout de suite** pour connaître l'id.

**Les enums** : `@Enumerated(EnumType.STRING)` stocke le nom (`"DONE"`). L'autre option, `ORDINAL`, stocke la
position (`3`) : si un jour tu ajoutes une valeur au milieu de l'enum, toutes les données existantes changent de
sens. Toujours `STRING`.

**Le constructeur sans argument** : JPA en exige un pour recréer l'objet à partir d'une ligne (il instancie puis
remplit les champs). On le met `protected` pour que ton code métier utilise le vrai constructeur, complet.

### 2.3 Les relations entre entités

Une tâche a (au plus) un assigné, un utilisateur peut avoir plusieurs tâches : c'est une relation
**plusieurs-vers-un**, vue du côté de la tâche.

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "assignee_id", referencedColumnName = "id")
private AppUser assignee;
```

- `@ManyToOne` : plusieurs `Task` pointent vers un `AppUser`.
- `@JoinColumn` : la colonne de clé étrangère dans la table `task`.
- En base, c'est simplement `assignee_id BIGINT REFERENCES app_user (id)`.

Les autres types : `@OneToOne`, `@OneToMany` (le côté inverse : "un utilisateur a une liste de tâches"),
`@ManyToMany`. Le projet n'utilise que `@ManyToOne`, la relation la plus simple et la plus courante.

### 2.4 Chargement LAZY et EAGER

Quand Hibernate charge une tâche, doit-il aussi charger son assigné ?

- **EAGER** (impatient) : oui, toujours, tout de suite. Simple, mais tu charges des données dont tu n'as souvent
  pas besoin. C'est le défaut de `@ManyToOne` si tu ne précises rien.
- **LAZY** (paresseux) : non. Hibernate met à la place un **proxy**, un objet "vide" qui se charge au premier accès
  (`task.getAssignee().getUsername()` déclenche alors une requête).

Le LAZY est le choix recommandé : tu ne paies que ce que tu utilises. Mais il a deux revers :

1. le proxy ne peut se charger que si la connexion à la base est encore ouverte (voir
   [09. Transactions](09-transactions.md), `LazyInitializationException`) ;
2. le **problème N+1**.

### 2.5 Le problème N+1

Tu veux la liste des tâches **avec** leur assigné :

```java
List<Task> tasks = repository.findAll();   // 1 requête
for (Task task : tasks) {
	task.getAssignee().getUsername();       // 1 requête par assigné non encore chargé
}
```

SQL réellement envoyé :

```sql
select t.* from task t;                     -- 1 requête
select u.* from app_user u where u.id = ?;  -- pour l'assigné 1
select u.* from app_user u where u.id = ?;  -- pour l'assigné 2
...                                         -- N requêtes
```

D'où le nom : **1 + N** requêtes. Avec 10 tâches, ça passe ; avec 10 000, la page met des secondes. (Hibernate garde
en mémoire les entités déjà chargées dans la transaction : un même utilisateur assigné à 50 tâches n'est chargé
qu'une fois. N est donc le nombre d'assignés **distincts**.)

La solution : demander la jointure dès la première requête.

```java
@EntityGraph(attributePaths = "assignee")
List<Task> findAll(Sort sort);
```

```sql
select t.*, u.* from task t left join app_user u on u.id = t.assignee_id order by t.id;  -- 1 seule requête
```

`@EntityGraph` dit : "pour cette méthode, charge aussi `assignee`". On garde le LAZY par défaut partout ailleurs, et
on charge en une fois là où on sait qu'on en aura besoin.

### 2.6 Le contexte de persistance et le "dirty checking"

C'est la notion la plus surprenante au début.

Pendant une transaction, Hibernate tient un **contexte de persistance** : la liste des entités qu'il a chargées ou
enregistrées, avec une copie de leur état d'origine. Ces entités sont dites **managées**.

À la fin de la transaction (ou à un `flush`), Hibernate compare chaque entité managée à sa copie. Si un champ a
changé, il écrit l'`UPDATE` tout seul. C'est le **dirty checking** (vérification de ce qui est "sale").

```java
@Transactional
public void renommer(Long id, String titre) {
	Task task = repository.findById(id).orElseThrow();   // SELECT ; task devient managée
	task.setTitle(titre);                                // simple modification en mémoire
}                                                        // fin de transaction → UPDATE automatique
```

Aucun `save` : l'entité est déjà suivie. Appeler `save` ne serait pas une erreur, juste inutile.

Le cycle de vie d'une entité, en résumé :

| État | Signification | Exemple |
|---|---|---|
| **Nouvelle** (transient) | Créée avec `new`, Hibernate ne la connaît pas | `new Task(...)` |
| **Managée** | Suivie : ses changements seront écrits | après `findById` ou `save` dans une transaction |
| **Détachée** | Était managée, mais la transaction est finie : plus suivie | l'entité renvoyée hors du service |
| **Supprimée** | Marquée pour un `DELETE` | après `repository.delete(task)` |

**Le flush** est le moment où Hibernate envoie réellement le SQL en attente. Il a lieu automatiquement avant le
commit (et avant certaines requêtes). `flush()` le force tout de suite : utile quand tu veux que le SQL s'exécute
**maintenant**, par exemple pour qu'une erreur de contrainte remonte à un endroit précis.

### 2.7 Les callbacks de cycle de vie

JPA peut appeler tes méthodes à certains moments : `@PrePersist`, `@PreUpdate`, `@PostLoad`... Le projet s'en sert
pour remplir les dates :

- `@CreationTimestamp` (annotation Hibernate) remplit le champ à l'insertion ;
- `@PreUpdate` (annotation JPA) appelle une méthode juste avant chaque `UPDATE`, donc **au moment du flush**.

### 2.8 Spring Data JPA : les repositories

```java
public interface TaskRepository extends JpaRepository<Task, Long> { }
```

Cette interface vide te donne déjà : `findAll`, `findById`, `save`, `saveAndFlush`, `delete`, `deleteAll`, `count`,
`existsById`, et d'autres. Spring génère l'implémentation au démarrage et en fait un bean injectable.

**Les méthodes dérivées** : tu nommes une méthode selon une grammaire, Spring en déduit la requête.

| Méthode | Requête générée (simplifiée) |
|---|---|
| `existsByEmail(String email)` | `select id from app_user where email = ? fetch first 1 rows only` (SQL réel en 4.7) |
| `existsByUsernameIgnoreCase(String u)` | `... where upper(username) = upper(?)` |
| `findByStatus(TaskStatus s)` | `select * from task where status = ?` |
| `findByStatusOrderByCreatedDateDesc(...)` | `... where status = ? order by created_date desc` |
| `countByAssigneeId(Long id)` | `select count(*) from task where assignee_id = ?` |

Les mots-clés (`By`, `And`, `Or`, `IgnoreCase`, `OrderBy`, `Between`, `Like`, `IsNull`...) sont listés dans la doc
Spring Data. Si le nom devient illisible, on passe à `@Query` (voir §7).

**`Optional`** : `findById` renvoie un `Optional<Task>`, pour t'obliger à traiter le cas "introuvable"
(`orElseThrow`).

**`Sort`** : `Sort.by("lastname", "firstname")` trie par champs **Java** (pas par noms de colonnes). Sans tri, une
base relationnelle ne garantit **aucun** ordre : la même requête peut renvoyer les lignes dans un ordre différent
d'un appel à l'autre.

### 2.9 La base de données et ses garde-fous

**La DataSource et le pool de connexions** : ouvrir une connexion à PostgreSQL coûte cher (réseau,
authentification). Spring Boot configure **HikariCP**, un pool qui garde quelques connexions ouvertes et les prête
à chaque transaction. Tu le configures via `spring.datasource.*` (voir [03. Configuration](03-configuration.md)).

**Les contraintes SQL** : `NOT NULL`, `UNIQUE`, `CHECK`, `REFERENCES`. Elles s'appliquent **quoi qu'il arrive** :
bug applicatif, script lancé à la main, deux requêtes simultanées. La validation des DTO
([06. Validation](06-validation.md)) protège l'API ; les contraintes protègent les données.

### 2.10 Flyway : versionner le schéma

Un dossier `db/migration/` contient des scripts nommés `V<version>__<description>.sql` :

```
V1__create_app_user_table.sql
V2__create_task_table.sql
V3__add_due_date_to_task.sql      ← la prochaine évolution
```

Au démarrage de l'application, Flyway :

1. regarde la table `flyway_schema_history` (qu'il crée la première fois) ;
2. voit quelles versions sont déjà appliquées ;
3. exécute les scripts manquants, dans l'ordre, et note chacun avec un **checksum** (une empreinte du contenu).

**La règle d'or** : un script déjà appliqué quelque part ne se modifie **jamais**. Son checksum ne correspondrait
plus et Flyway refuserait de démarrer. Pour changer le schéma, on écrit une **nouvelle** migration.

**`ddl-auto`** : Hibernate sait aussi créer le schéma lui-même à partir des entités (`create`, `update`). C'est
pratique pour un prototype, dangereux ensuite (il ne sait pas migrer proprement des données existantes). Avec Flyway,
on met `validate` : Hibernate vérifie au démarrage que les tables et colonnes attendues par les entités existent, et
refuse de démarrer sinon, mais ne modifie rien. Ce contrôle porte sur la structure (tables, colonnes, types) ; il ne
vérifie pas toutes les contraintes, comme les `CHECK`.

---

## 3. Dans le projet

### 3.1 Les entités

`server/src/main/java/com/personal/task_tracker/task/entity/Task.java`

```java
14	@Entity
15	public class Task {
16		@Id
17		@GeneratedValue(strategy = GenerationType.IDENTITY)
18		private Long id;
...
26		@Enumerated(EnumType.STRING)
27		@Column(name = "type", length = 10, nullable = false)
28		private TaskType type;
...
38		@ManyToOne(fetch = FetchType.LAZY)
39		@JoinColumn(name = "assignee_id", referencedColumnName = "id")
40		@OnDelete(action = OnDeleteAction.SET_NULL)
41		private AppUser assignee;
42
43		@CreationTimestamp
44		@Column(name = "created_date", nullable = false, updatable = false)
45		private LocalDateTime createdDate;
...
50		@PreUpdate
51		void onUpdate() {
52			updatedDate = LocalDateTime.now();
53		}
54
55		protected Task() {
56
57		}
```

- Ligne 27 : `length = 10` correspond au `VARCHAR(10)` de la migration. Les deux doivent rester d'accord.
- Ligne 38 : relation LAZY explicite (le défaut de `@ManyToOne` serait EAGER).
- Ligne 40 : `@OnDelete` décrit le `ON DELETE SET NULL` de la base : supprimer un utilisateur désassigne ses tâches
  au lieu de les supprimer. Comme ici ce n'est pas Hibernate qui crée le schéma, l'annotation sert surtout à
  documenter ce comportement ; c'est la clause SQL de la migration qui l'applique.
- Ligne 44 : `updatable = false`, la date de création n'est jamais réécrite.
- Lignes 50 à 53 : la date de modification est posée juste avant l'`UPDATE`.
- Ligne 55 : le constructeur exigé par JPA, protégé.

`AppUser.java` suit exactement le même modèle (même dossier `user/entity/`).

### 3.2 Les migrations

`server/src/main/resources/db/migration/V2__create_task_table.sql`

```sql
1	CREATE TABLE task
2	(
3	    id           BIGSERIAL PRIMARY KEY,
4	    title        VARCHAR(50)  NOT NULL,
5	    description  VARCHAR(255),
6	    type         VARCHAR(10)  NOT NULL CHECK (type IN ('FEATURE', 'EVOLUTION', 'BUG', 'HOTFIX', 'CHORE')),
...
9	    assignee_id  BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
10	    created_date timestamp    NOT NULL,
11	    updated_date timestamp
12	);
```

- Ligne 3 : `BIGSERIAL` est le compteur qui fournit les id (`GenerationType.IDENTITY` côté Java).
- Ligne 6 : le `CHECK` refuse toute valeur hors de l'enum, même insérée à la main.
- Ligne 9 : la clé étrangère, et ce qu'elle fait quand l'utilisateur disparaît.
- La suite du fichier (`COMMENT ON COLUMN ...`) documente chaque colonne dans la base elle-même.

`V1__create_app_user_table.sql`, ligne 14 :

```sql
14	CREATE UNIQUE INDEX app_user_username_upper ON app_user (upper(username));
```

Un index unique **sur une expression** : `JDoe` et `jdoe` donnent le même `upper(...)`, donc la base refuse le
doublon. La colonne `email` a un simple `UNIQUE` (ligne 7), car l'email est déjà mis en minuscules par le service.

### 3.3 Les repositories

`server/src/main/java/com/personal/task_tracker/user/repository/UserRepository.java`

```java
8	public interface UserRepository extends JpaRepository<AppUser, Long> {
9		boolean existsByUsernameIgnoreCase(String username);
10
11		boolean existsByEmail(String email);
12	}
```

Deux méthodes dérivées, aucune ligne de SQL écrite. `IgnoreCase` fait générer une comparaison sur `upper(...)`,
cohérente avec l'index de V1.

`server/src/main/java/com/personal/task_tracker/task/repository/TaskRepository.java`

```java
15		/**
16		 * Loads the assignee in the same query: the mapper reads it for every task
17		 */
18		@Override
19		@EntityGraph(attributePaths = "assignee")
20		@NonNull List<Task> findAll(@NonNull Sort sort);
```

On **redéfinit** la méthode héritée `findAll(Sort)` uniquement pour lui ajouter `@EntityGraph`. Le commentaire dit
pourquoi : `TaskMapper.toTaskResponseDto` lit l'assigné de chaque tâche, ce qui provoquerait un N+1.

### 3.4 Le test qui prouve l'absence de N+1

`server/src/test/java/com/personal/task_tracker/task/TaskControllerTest.java`

```java
426		@Test
427		void shouldLoadTasksAndTheirAssigneesInASingleQuery() {
428			AppUser otherAssignee = saveUser("other", "other@mail.com");
429			saveTask("First");
430			repository.save(new Task("Second", "Description", TaskType.BUG, TaskStatus.BACKLOG, TaskPriority.LOW, otherAssignee));
431			Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
432			statistics.clear();
433
434			given()
435					.when()
436					.get("/api/tasks")
...
441			assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
442		}
```

- Deux tâches avec deux assignés différents : sans `@EntityGraph`, on aurait 1 + 2 = 3 requêtes.
- Ligne 431 : on récupère les statistiques d'Hibernate, activées dans `IntegrationTest.java` (ligne 25,
  `hibernate.generate_statistics=true`).
- Ligne 432 : remise à zéro après la préparation des données, pour ne compter que la requête HTTP.
- Ligne 441 : exactement **une** requête SQL. Si quelqu'un retire l'`@EntityGraph`, ce test casse.

### 3.5 Le dirty checking dans les services

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java`

```java
80		@Transactional
81		public TaskResponseDto updateTask(Long taskId, TaskRequestDto taskDto) {
82			Task task = findTaskById(taskId);
83
84			TaskMapper.applyUpdate(task, taskDto);
...
93			repository.flush();
94
95			return TaskMapper.toTaskResponseDto(task);
96		}
```

- Ligne 82 : la tâche est chargée, donc managée.
- Ligne 84 : on modifie ses champs en mémoire. **Aucun `save`.**
- Ligne 93 : `flush()` force l'`UPDATE` maintenant. Le `@PreUpdate` de `Task` s'exécute donc ici, et `updatedDate`
  est rempli **avant** la construction du DTO de réponse ligne 95. Sans ce flush, l'`UPDATE` n'aurait lieu qu'au
  commit, après la ligne 95, et la réponse contiendrait un `updatedDate` vide.

Comparaison avec la création, ligne 66 :

```java
66			Task createdTask = repository.save(task);
```

Ici `save` est nécessaire : l'objet vient d'un `new` (via le mapper), Hibernate ne le connaît pas encore. Comme l'id
est en `IDENTITY`, l'`INSERT` part immédiatement, et `createdTask.getId()` est disponible tout de suite.

### 3.6 `saveAndFlush` et les contraintes de la base

`server/src/main/java/com/personal/task_tracker/user/service/UserService.java`

```java
71			if (repository.existsByUsernameIgnoreCase(username)) {
72				throw UserAlreadyExistsException.username();
73			}
...
81			try {
82				return repository.saveAndFlush(user);
83			} catch (DataIntegrityViolationException exception) {
84				String constraintName = exception.getCause() instanceof ConstraintViolationException cve ? cve.getConstraintName() : null;
85				if (USERNAME_UNIQUE_INDEX.equals(constraintName)) {
86					throw UserAlreadyExistsException.username(exception);
87				}
```

Deux protections superposées :

1. Lignes 71 à 73 : une vérification préalable, qui donne un message clair dans le cas normal.
2. Lignes 81 à 87 : si deux inscriptions arrivent **en même temps**, les deux passent la vérification, et c'est
   l'index unique de la base qui refuse la seconde. `saveAndFlush` force l'`INSERT` **dans** le `try`, pour que
   l'erreur soit attrapée ici et traduite en 409. Avec un simple `save`, l'`INSERT` partirait aussi tout de suite
   (à cause de `IDENTITY`, vérifié en 4.3), mais `saveAndFlush` rend l'intention explicite et ne dépend pas de ce
   détail.

Le nom de la contrainte (`app_user_username_upper`, ligne 25) est celui de l'index créé dans V1 : c'est ce lien qui
permet de savoir quel champ est en doublon.

Les tests `shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex` et `...HitsEmailConstraint`
(`AuthControllerTest.java`, lignes 217 et 237) simulent cette situation en forçant `existsBy...` à répondre `false`.

### 3.7 La configuration

`server/src/main/resources/application.properties`

```properties
2	spring.jpa.hibernate.ddl-auto=validate
3	spring.jpa.open-in-view=false
```

- Ligne 2 : Flyway gère le schéma, Hibernate ne fait que vérifier.
- Ligne 3 : expliqué dans [09. Transactions](09-transactions.md).

---

## 4. Sous le capot

Les sections précédentes disent **ce que** font Flyway, Hibernate et Spring Data. Celle-ci montre **comment**, dans
ce projet, et surtout comment le **voir**. Même plan que dans [02. Beans et injection](02-beans-injection.md) : le
mécanisme réel, comment l'observer, ce qui se passerait sans, la source.

Deux rappels de la fiche 02 qu'on ne refait pas ici : un repository est un proxy JDK qui délègue à
`SimpleJpaRepository` (02, section 4.5), et `@Transactional` passe par un proxy CGLIB (02, section 4.4).

Tous les extraits ci-dessous sont réels : ils viennent d'un test d'intégration temporaire lancé sur le projet
(PostgreSQL 16 via Testcontainers, Hibernate 7.4.5, Spring Data JPA 4.1.1, Flyway 12.4.0, HikariCP 7.0.2). Les
horodatages ont été retirés et les lignes trop longues raccourcies (`...`). Les lignes qui commencent par `@@@` sont
des `System.out.println` du test, pour se repérer.

Pour activer les logs :

- en lançant l'application :
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.arguments=--logging.level.org.hibernate.SQL=DEBUG --logging.level.org.hibernate.orm.jdbc.bind=TRACE"`
- en lançant un test : `./mvnw test -Dtest=TaskControllerTest "-DargLine=-Dlogging.level.org.hibernate.SQL=DEBUG"`.
  Tant que la configuration JWT est en chantier, ajoute aussi dans l'`argLine` les propriétés `app.jwt.secret`,
  `app.jwt.issuer` et `app.jwt.expiration`, sinon le contexte ne démarre pas.

### 4.1 Le démarrage : Flyway d'abord, Hibernate ensuite

**Le mécanisme.** Avec `ddl-auto=validate`, Hibernate compare les entités aux tables **au moment où il crée
l'`EntityManagerFactory`**, pendant le démarrage. Il faut donc que Flyway ait fini ses migrations avant. Spring Boot ne
compte pas sur la chance : un post-processeur de Spring Boot (`DatabaseInitializationDependencyConfigurer`) repère
d'un côté les beans qui **initialisent** la base (Flyway, les scripts SQL), de l'autre les beans qui **ont besoin**
d'une base initialisée (l'`EntityManagerFactory`), et ajoute aux seconds un `depends-on` vers les premiers. C'est le
même `depends-on` que tu pourrais écrire à la main avec `@DependsOn`.

Qui est "initialiseur" et qui "dépend" est déclaré dans un simple fichier texte, `META-INF/spring.factories`, présent
dans les jars `spring-boot-flyway` et `spring-boot-jpa` (même principe que les fichiers `.imports` de 02, section 4.6).

**L'observer.**

- Les logs INFO suffisent. Extrait réel du démarrage d'un test :
  ```
  o.f.c.i.s.JdbcTableSchemaHistory : Schema history table "public"."flyway_schema_history" does not exist yet
  o.f.core.internal.command.DbValidate : Successfully validated 2 migrations (execution time 00:00.027s)
  o.f.c.i.s.JdbcTableSchemaHistory : Creating Schema History table "public"."flyway_schema_history" ...
  o.f.core.internal.command.DbMigrate : Current version of schema "public": << Empty Schema >>
  o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "1 - create app user table"
  o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "2 - create task table"
  o.f.core.internal.command.DbMigrate : Successfully applied 2 migrations to schema "public", now at version v2 (execution time 00:00.044s)
  org.hibernate.orm.jpa               : HHH008540: Processing PersistenceUnitInfo [name: default]
  org.hibernate.orm.core              : HHH000001: Hibernate ORM core version 7.4.5.Final
  com.zaxxer.hikari.HikariDataSource  : HikariPool-1 - Starting...
  com.zaxxer.hikari.HikariDataSource  : HikariPool-1 - Start completed.
  j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
  ```
  Remarque le nom du script (`V1__create_app_user_table.sql`) transformé en `"1 - create app user table"` : version
  avant les `__`, description après, `_` remplacés par des espaces.
- Le `depends-on` lui-même : dans un test, `beanFactory.getBeanDefinition("entityManagerFactory").getDependsOn()`
  renvoie `[flyway, flywayInitializer, dataSourceScriptDatabaseInitializer]`.
- Les déclarations :
  `unzip -p ~/.m2/repository/org/springframework/boot/spring-boot-jpa/4.1.1/spring-boot-jpa-4.1.1.jar META-INF/spring.factories`
  affiche notamment `JpaDependsOnDatabaseInitializationDetector`.

Détail qui intrigue en lisant ce log : en test, Flyway migre **avant** que le pool Hikari ne démarre. C'est que
le `@ServiceConnection` de `TestcontainersConfiguration` fournit aussi à Flyway ses propres informations de
connexion (l'URL du conteneur), et `FlywayAutoConfiguration` crée alors une
petite `DataSource` dédiée (`SimpleDriverDataSource`, sans pool) pour les migrations. En local, sans propriété
`spring.flyway.url`, le même code fait utiliser à Flyway la `DataSource` principale, donc le pool Hikari (lu dans le
code de `FlywayAutoConfiguration`, pas observé ici).

**Et sans ?** Vérifié en relançant un test avec `-Dspring.flyway.enabled=false` (la base Testcontainers est vide) :

```
Error creating bean with name 'entityManagerFactory' defined in class path resource [org/springframework/boot/hibernate/autoconfigure/HibernateJpaConfiguration.class]: Unable to build Hibernate SessionFactory  [persistence unit: default] ; nested exception is org.hibernate.tool.schema.spi.SchemaManagementException: Schema validation: missing table [app_user]
```

C'est exactement le rôle de `validate` : refuser de démarrer plutôt que d'échouer à la première requête. Les autres
messages possibles, lus dans `hibernate-core` 7.4.5 : `Schema validation: missing column [...] in table [...]` et
`Schema validation: wrong column type encountered in column [...] in table [...]`.

Et sans la ligne `ddl-auto=validate` ? D'après la documentation de Spring Boot, la valeur par défaut serait `none`
(Flyway est détecté et la base n'est pas une base embarquée) : Hibernate ne vérifierait rien, et un désaccord entre
entité et migration ne se verrait qu'à l'exécution. Avec `update` ou `create`, Hibernate modifierait lui-même le
schéma, dans le dos de Flyway.

**La source.** [Database Initialization (Spring Boot how-to)](https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway),
[Initialize a Database Using Hibernate](https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.using-hibernate),
[Flyway : Migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations).

### 4.2 Le pool HikariCP : des connexions prêtées, pas ouvertes

**Le mécanisme.** Le starter JPA tire `HikariCP`, et l'auto-configuration de la `DataSource` le choisit parce qu'il
est sur le classpath. Le bean `DataSource` du projet est donc un `com.zaxxer.hikari.HikariDataSource`. Au démarrage,
il ouvre un lot de connexions PostgreSQL et les garde. Quand une transaction commence, elle en **emprunte** une ;
`close()` ne ferme pas la connexion, il la **rend** au pool. Ce que ton code manipule n'est d'ailleurs pas la
connexion PostgreSQL elle-même mais une enveloppe, `com.zaxxer.hikari.pool.HikariProxyConnection`, qui sait se rendre
au pool.

**L'observer.** Avec `logging.level.com.zaxxer.hikari=DEBUG`, Hikari affiche toute sa configuration au démarrage,
puis chaque connexion ouverte. Extrait réel :

```
HikariConfig : autoCommit......................true
HikariConfig : connectionTimeout...............30000
HikariConfig : maximumPoolSize.................10
HikariConfig : minimumIdle.....................10
HikariConfig : poolName........................"HikariPool-1"
HikariPool   : HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@362ae951
HikariPool   : HikariPool-1 - After adding stats (total=10/10, idle=10/10, active=0, waiting=0)
```

Dix connexions ouvertes d'avance (`minimumIdle` vaut par défaut `maximumPoolSize`), toutes inactives. Des lignes
`Pool stats (total=..., idle=..., active=..., waiting=...)` apparaissent aussi au démarrage puis lors des passages
de maintenance du pool : `active` compte les connexions prêtées, `waiting` les threads qui attendent une connexion
libre.

Autre observation, dans une transaction : `connection.getAutoCommit()` renvoie `false`, alors que le pool est
configuré à `true`. C'est le gestionnaire de transactions qui coupe l'auto-commit en début de transaction (voir
[09. Transactions](09-transactions.md), section 4.1).

**Pourquoi c'est nécessaire.** Ouvrir une connexion PostgreSQL, c'est une connexion réseau, une authentification, et
un processus créé côté serveur. Sans pool, chaque transaction paierait ce coût, et sous charge tu pourrais dépasser le
nombre maximal de connexions accepté par PostgreSQL. Le pool fait aussi office de limite : avec 10 connexions, une
onzième transaction simultanée attend, jusqu'à `connectionTimeout` (30 s) avant d'échouer. D'où la règle "transactions
courtes" de la fiche 09. Tout se règle par `spring.datasource.hikari.*`.

**La source.** [Spring Boot : Supported Connection Pools](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.connection-pool),
[HikariCP](https://github.com/brettwooldridge/HikariCP) (le README liste chaque réglage et sa valeur par défaut).

### 4.3 Voir le SQL réellement envoyé

**Le mécanisme.** Hibernate écrit chaque requête dans le logger `org.hibernate.SQL` (niveau DEBUG), et chaque valeur
liée à un `?` dans `org.hibernate.orm.jdbc.bind` (niveau TRACE). `spring.jpa.show-sql=true` (exercice 1) est une
version simplifiée : la requête est imprimée directement sur la sortie standard, sans passer par le système de logs,
et sans les valeurs.

**L'observer.** Un `taskRepository.save(...)` d'une nouvelle tâche, dans une transaction :

```
@@@ avant save
org.hibernate.SQL : insert into task (assignee_id,created_date,description,priority,status,title,type,updated_date) values (?,?,?,?,?,?,?,?)
org.hibernate.orm.jdbc.bind : binding parameter (1:BIGINT) <- [null]
org.hibernate.orm.jdbc.bind : binding parameter (2:TIMESTAMP) <- [2026-10-29T14:46:12.763421]
org.hibernate.orm.jdbc.bind : binding parameter (3:VARCHAR) <- [D]
...
org.hibernate.orm.jdbc.bind : binding parameter (8:TIMESTAMP) <- [null]
@@@ après save, id=2
```

À lire :

- pas de colonne `id` dans l'`INSERT` : avec `IDENTITY`, c'est PostgreSQL qui la remplit ;
- l'`INSERT` part **pendant** `save`, avant la fin de la transaction : Hibernate a besoin de l'id tout de suite, et
  `getId()` vaut déjà 2 juste après (c'est ce que 2.2 annonçait) ;
- `created_date` a une valeur : c'est `@CreationTimestamp` qui l'a posée juste avant l'`INSERT` ;
- les alias du type `t1_0`, `au1_0` que tu verras dans les `select` sont générés par Hibernate (initiales de
  l'entité, ou de l'attribut pour une jointure comme `a1_0` pour `assignee`, puis des numéros).

Attention : le niveau TRACE de `bind` affiche **toutes** les valeurs, y compris les hash de mots de passe à
l'inscription. À réserver au poste de développement.

**La source.** [Hibernate User Guide : Flushing](https://docs.hibernate.org/orm/7.4/userguide/html_single/#flushing)
pour le moment où le SQL part, [Spring Boot : SQL Databases](https://docs.spring.io/spring-boot/reference/data/sql.html).

### 4.4 Le contexte de persistance : une table d'objets par transaction

**Le mécanisme.** À chaque transaction, Spring ouvre une nouvelle session Hibernate (classe `SessionImpl`, qui
implémente `EntityManager`). Dans la session, une table de correspondance "type + id → objet Java" : c'est le
contexte de persistance, ou cache de premier niveau. `findById` regarde d'abord dans cette table ; il n'interroge la
base que si l'entité n'y est pas encore.

Subtilité : l'`EntityManager` que Spring injecte (et que `SimpleJpaRepository` utilise) n'est **pas** une session.
C'est un proxy partagé (`jdk.proxy2.$Proxy174` dans le test), un objet unique utilisé par tous les threads, qui à
chaque appel va chercher la session **de la transaction en cours**. Où il la trouve : [09. Transactions](09-transactions.md),
section 4.1.

**L'observer.** Deux `findById(1L)` dans la **même** transaction :

```
org.hibernate.SQL : select t1_0.id,t1_0.assignee_id,...,t1_0.updated_date from task t1_0 where t1_0.id=?
@@@ first findById done
@@@ second findById done, same instance: true
@@@ contains: true
```

Un seul `select`, et le même objet (`==`). Les deux mêmes appels **hors** transaction, donc chacun dans la sienne
(celle de `SimpleJpaRepository`) : deux `select`, et `same instance: false`.

**Pourquoi c'est nécessaire.** Sans cette table, deux lectures de la même ligne dans une transaction donneraient deux
objets distincts : si ton code modifie l'un puis l'autre, lequel écrire ? Le contexte garantit **un objet par ligne
et par transaction**. C'est aussi lui qui explique le N+1 "par assigné distinct" de 2.5 (un assigné déjà dans la table
n'est pas rechargé), et c'est la base du dirty checking (4.5). Sa limite : il grossit avec tout ce que la transaction
charge, et disparaît avec elle.

**La source.** [Hibernate User Guide : Persistence Context](https://docs.hibernate.org/orm/7.4/userguide/html_single/#pc),
[Obtain an entity](https://docs.hibernate.org/orm/7.4/userguide/html_single/#pc-find).

### 4.5 Le dirty checking, et le `save` inutile

**Le mécanisme.** En chargeant une entité, Hibernate garde une **photo** de ses valeurs. Au flush (au commit, ou à un
`flush()` explicite), il compare chaque entité managée à sa photo, champ par champ, et écrit un `UPDATE` pour celles
qui ont changé. Côté Spring Data, `save(entity)` fait : "si l'entité est nouvelle (id `null`), `persist` ; sinon,
`merge`". Et un `merge` sur une entité **déjà managée** ne fait rien d'autre que la renvoyer.

**L'observer.** Six cas réels, chacun dans sa transaction, sur la tâche 1 (logs SQL activés) :

| Code | SQL envoyé |
|---|---|
| `findById`, `setTitle(...)`, pas de `save` | `select`, puis `update` au commit |
| `findById`, `setTitle(...)`, puis `save(task)` | **exactement le même** : `select`, puis `update` au commit. `save` renvoie le même objet (`same instance: true`), aucune requête en plus |
| `findById`, aucune modification | `select` seulement |
| `findById`, `setTitle("X")` puis `setTitle` de la valeur d'origine | `select` seulement : Hibernate compare des valeurs, pas des appels de setter |
| entité chargée dans une transaction **précédente**, modifiée, pas de `save` | rien du tout : elle est détachée, la modification est perdue |
| la même entité détachée, puis `save` | `select` (le `merge` recharge la ligne) puis `update` |

L'`UPDATE` réel :

```
org.hibernate.SQL : update task set assignee_id=?,description=?,priority=?,status=?,title=?,type=?,updated_date=? where id=?
org.hibernate.orm.jdbc.bind : binding parameter (5:VARCHAR) <- [Renamed]
org.hibernate.orm.jdbc.bind : binding parameter (7:TIMESTAMP) <- [2026-10-29T14:46:12.793537]
org.hibernate.orm.jdbc.bind : binding parameter (8:BIGINT) <- [1]
```

Toutes les colonnes sauf `created_date` (`updatable = false`), et `updated_date` remplie par le `@PreUpdate` juste
avant. Conclusion pour `TaskService.updateTask` : un `save` n'y changerait rien. À l'inverse, le seul vrai danger est
le cas détaché : une modification faite hors transaction disparaît **sans erreur**.

**Il existe une autre technique : l'enhancement.** Hibernate peut réécrire le bytecode des entités à la compilation
(plugin `hibernate-enhance-maven-plugin`) pour que chaque setter note lui-même "je suis modifié", ce qui évite la
comparaison. Le projet **ne l'utilise pas**, et tu peux le vérifier : pas de plugin Hibernate dans le `pom.xml`,
`Task.class.getInterfaces()` renvoie un tableau vide (une entité enrichie implémenterait des interfaces Hibernate
comme `ManagedEntity`), et le log `No LoadTimeWeaver setup: ignoring JPA class transformer` au démarrage indique
qu'aucune transformation n'a lieu non plus à l'exécution. Conséquence : le coût du flush grandit avec le nombre
d'entités chargées. C'est une des raisons d'être de `readOnly = true` ([09. Transactions](09-transactions.md),
section 4.4), qui supprime ce flush.

**La source.** [Spring Data JPA : Saving Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html),
[Hibernate User Guide : Modifying managed/persistent state](https://docs.hibernate.org/orm/7.4/userguide/html_single/#pc-managed-state),
[Merging detached data](https://docs.hibernate.org/orm/7.4/userguide/html_single/#pc-merge),
[Bytecode Enhancement](https://docs.hibernate.org/orm/7.4/userguide/html_single/#BytecodeEnhancement).

### 4.6 Le proxy LAZY : une sous-classe générée en mémoire

**Le mécanisme.** Pour `@ManyToOne(fetch = FetchType.LAZY)`, Hibernate lit `assignee_id` dans la ligne de `task`,
mais ne charge pas l'utilisateur. Il place dans le champ `assignee` une instance d'une **sous-classe** d'`AppUser`,
générée en mémoire par la bibliothèque Byte Buddy (dépendance de `hibernate-core`), qui ne connaît que l'id. Au
premier appel d'une méthode autre que `getId()`, elle lance le `select` puis délègue à un vrai `AppUser`.

**L'observer.** Dans une transaction, après `taskRepository.findById(1L)` :

```
@@@ assignee class: com.personal.task_tracker.user.entity.AppUser$HibernateProxy
@@@ superclass: com.personal.task_tracker.user.entity.AppUser
@@@ interfaces: [interface org.hibernate.proxy.HibernateProxy, interface org.hibernate.proxy.ProxyConfiguration]
@@@ initialized: false
@@@ getId: 1
@@@ initialized after getId: false
org.hibernate.SQL : select au1_0.id,au1_0.created_date,au1_0.email,...,au1_0.username from app_user au1_0 where au1_0.id=?
@@@ username: jdoe
@@@ initialized after getUsername: true
@@@ getClass after init: com.personal.task_tracker.user.entity.AppUser$HibernateProxy
```

- `initialized` vient de `Hibernate.isInitialized(...)`. `getId()` ne déclenche rien (l'id est déjà connu),
  `getUsername()` déclenche le `select`.
- Après chargement, l'objet reste un proxy : `getClass()` ne renvoie jamais `AppUser.class`.
  `Hibernate.unproxy(assignee).getClass()` renvoie, lui, `AppUser`. Teste donc le type avec `instanceof`, jamais
  avec `getClass() ==`.
- Cherche `AppUser$HibernateProxy` dans `server/target/classes` : il n'y est pas, la classe n'existe qu'en mémoire.
- Le même accès **après** la fin de la transaction donne, mot pour mot :
  `org.hibernate.LazyInitializationException: Could not initialize proxy [com.personal.task_tracker.user.entity.AppUser#1] - no session`.

**Pourquoi les règles de JPA.** Un proxy est une sous-classe, comme les proxies de Spring (02, section 4.4). D'où les
contraintes de JPA sur les entités : classe non `final`, méthodes non `final`, et un constructeur sans argument au
moins `protected` (celui de `Task.java`, ligne 55, et d'`AppUser`) pour que la sous-classe puisse être instanciée.

**La source.** [Hibernate User Guide : Fetching](https://docs.hibernate.org/orm/7.4/userguide/html_single/#fetching),
[Obtain an entity reference without initializing its data](https://docs.hibernate.org/orm/7.4/userguide/html_single/#pc-get-reference).

### 4.7 Les méthodes dérivées : un nom analysé au démarrage

**Le mécanisme.** Au démarrage (`Bootstrapping Spring Data JPA repositories in DEFAULT mode`, puis
`Found 2 JPA repository interfaces` dans les logs), Spring Data crée le proxy de chaque repository. Pour chaque
méthode que `SimpleJpaRepository` ne fournit pas et qui n'a pas de `@Query`, la stratégie par défaut
(`CREATE_IF_NOT_FOUND`) **analyse le nom** : le préfixe (`existsBy`), puis les propriétés (`Email`, comparé aux champs
d'`AppUser`), puis les mots-clés (`IgnoreCase`). Elle en construit une requête JPA, qu'Hibernate traduit en SQL.

**L'observer.** Le SQL réel des méthodes de `UserRepository` :

```
@@@ existsByEmail("jdoe@mail.com")
org.hibernate.SQL : select au1_0.id from app_user au1_0 where au1_0.email=? fetch first ? rows only
org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [jdoe@mail.com]
org.hibernate.orm.jdbc.bind : binding parameter (2:INTEGER) <- [1]
@@@ existsByUsernameIgnoreCase("JDOE")
org.hibernate.SQL : select au1_0.id from app_user au1_0 where upper(au1_0.username)=upper(?) fetch first ? rows only
@@@ existsById(1), méthode de SimpleJpaRepository, pas dérivée
org.hibernate.SQL : select count(*) from app_user au1_0 where au1_0.id=?
```

- Un `exists` dérivé n'est pas un `count(*)` : il demande une seule ligne (`fetch first 1 rows only`), la base peut
  s'arrêter à la première trouvée.
- `IgnoreCase` produit `upper(username) = upper(?)`, la même expression que l'index `app_user_username_upper` de V1.

**La preuve que c'est fait au démarrage.** Vérifié avec un repository de test contenant une faute de frappe,
`boolean existsByEmial(String email)` : l'application **ne démarre pas**.

```
Error creating bean with name 'tmpProbeBadRepository' ...: No property 'emial' found for type 'AppUser'; Did you mean 'email'
```

Spring Data se comporte comme un compilateur de noms de méthodes : l'erreur arrive au démarrage, pas au premier appel.
Autre conséquence, visible dans [09. Transactions](09-transactions.md), section 4.2 : ces méthodes déclarées dans ton
interface ne sont pas transactionnelles par défaut, contrairement à celles héritées de `SimpleJpaRepository`.

**La source.** [Spring Data : Defining Query Methods](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html),
[Spring Data JPA : Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html).

### 4.8 Le refaire toi-même : un contexte de persistance en 50 lignes

Comme le mini-conteneur de la fiche 02, écris ce fichier **hors du projet** et lance-le avec
`java MiniHibernate.java` :

```java
import java.util.HashMap;
import java.util.Map;

public class MiniHibernate {

	// Une "entité" ordinaire
	static class Task {
		Long id;
		String title;

		Task(Long id, String title) {
			this.id = id;
			this.title = title;
		}
	}

	// La "base de données" : une ligne par id
	static final Map<Long, String> table = new HashMap<>(Map.of(1L, "Écrire la fiche"));

	// Le "contexte de persistance" : les entités chargées, et une copie de leur état d'origine
	static final Map<Long, Task> managed = new HashMap<>();
	static final Map<Long, String> snapshots = new HashMap<>();

	static Task find(Long id) {
		Task existing = managed.get(id);
		if (existing != null) {
			return existing;                                         // cache de premier niveau : pas de SQL
		}
		System.out.println("select title from task where id=" + id);
		Task task = new Task(id, table.get(id));
		managed.put(id, task);
		snapshots.put(id, task.title);                               // la photo "avant"
		return task;
	}

	static void flush() {
		for (Task task : managed.values()) {
			if (!task.title.equals(snapshots.get(task.id))) {        // dirty checking : comparaison avec la photo
				System.out.println("update task set title='" + task.title + "' where id=" + task.id);
				table.put(task.id, task.title);
				snapshots.put(task.id, task.title);
			}
		}
	}

	public static void main(String[] args) {
		Task a = find(1L);
		Task b = find(1L);
		System.out.println("même objet : " + (a == b));
		a.title = "Relire la fiche";                                  // aucune méthode save
		flush();                                                     // ce que fait le commit
		flush();                                                     // rien n'a changé depuis : aucun SQL
	}
}
```

Sortie :

```
select title from task where id=1
même objet : true
update task set title='Relire la fiche' where id=1
```

Compare avec 4.4 et 4.5 : un seul `select` pour deux `find`, le même objet, un `UPDATE` sans `save`, et rien au
second flush. Ce qui manque par rapport à Hibernate : la génération du SQL à partir des annotations, les proxies
(4.6), les transactions et la vraie base.

**Défis :**

1. Ajoute une méthode `clear()` qui vide `managed` et `snapshots`, et appelle-la entre les deux `find`. Qu'affiche le
   programme, et à quelle situation réelle cela correspond-il ?
2. Écris un `persist(Task)` pour une tâche nouvelle, qui l'ajoute à `managed` et à `snapshots` et affiche un
   `insert`. Pourquoi faut-il **aussi** prendre la photo à ce moment-là ?

<details><summary>Réponses</summary>

1. Deux `select` et `même objet : false`. C'est ce qui se passe entre deux transactions : chacune a son propre
   contexte (4.4, les deux `findById` hors transaction).
2. Sans photo, le `flush()` suivant comparerait le titre à `null` et enverrait un `UPDATE` inutile juste après
   l'`INSERT`. Hibernate prend lui aussi l'état de référence au moment du `persist`.
</details>

---

## 5. À toi de jouer

### Exercice 1 : voir le SQL et le N+1

1. Dans ton `application-local.properties`, mets `spring.jpa.show-sql=true` (la clé existe déjà dans
   `application-local.properties.example`). Ajoute `spring.jpa.properties.hibernate.format_sql=true` pour un SQL
   indenté.
2. Lance l'application, crée deux utilisateurs et trois tâches assignées, puis appelle `GET /api/tasks`.
3. **À observer** : une seule requête `select ... from task ... left join app_user ...`.
4. En local, sans commiter, mets en commentaire la ligne `@EntityGraph` de `TaskRepository` et recommence.
5. **À observer** : une requête sur `task`, puis une requête sur `app_user` par assigné distinct. Lance aussi
   `shouldLoadTasksAndTheirAssigneesInASingleQuery` : il échoue avec un compte supérieur à 1.
6. Remets la ligne.

### Exercice 2 : le dirty checking en direct

Avec `show-sql` actif, envoie un `PATCH /api/tasks/{id}` qui ne change que le statut.

**À observer** : un `select` (le `findById`), puis un `update task set ...` alors que le code n'appelle jamais
`save`. Remarque que l'`UPDATE` contient **toutes** les colonnes modifiables, pas seulement `status` (seules `id` et
`created_date`, en `updatable = false`, en sont absentes) : c'est le comportement par défaut d'Hibernate
(`@DynamicUpdate` permet de changer ça, rarement utile). Le SQL réel est en 4.5.

### Exercice 3 : lire l'historique Flyway

Connecte-toi à la base locale (`docker compose exec postgres psql -U <utilisateur> -d task_tracker`) et lance cette
requête en lecture seule :

```sql
SELECT installed_rank, version, description, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

**À observer** : une ligne par migration (V1, V2), avec son checksum. Question à te poser : que se passerait-il au
prochain démarrage si tu modifiais une virgule dans `V1__create_app_user_table.sql` ?

### Exercice 4 : prédire une méthode dérivée

Sans la coder, écris sur papier le SQL que génèrerait `List<Task> findByStatusOrderByPriorityAsc(TaskStatus status)`.
Puis demande-toi dans quel ordre sortiraient les priorités `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`.

<details><summary>Réponse</summary>

`select ... from task where status = ? order by priority asc`. Les priorités étant stockées en texte, le tri est
**alphabétique** : `CRITICAL`, `HIGH`, `LOW`, `MEDIUM`. Pas l'ordre métier ! Pour trier par importance, il faut une
autre solution (une colonne numérique, ou une expression `CASE` dans une `@Query`).
</details>

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| La page liste devient lente quand les données grossissent | Problème N+1 sur une relation LAZY | `@EntityGraph` ou `join fetch` dans une `@Query` ; un test qui compte les requêtes |
| `LazyInitializationException: Could not initialize proxy [...] - no session` | Accès à une relation LAZY après la fin de la transaction | Faire le mapping vers le DTO dans le service ; voir [09. Transactions](09-transactions.md) |
| L'application refuse de démarrer : `Schema validation: missing column [...] in table [...]` | Entité et migration en désaccord (`ddl-auto=validate`) | Écrire la migration correspondante, ou corriger le nom dans `@Column` |
| `Migration checksum mismatch` au démarrage | Une migration déjà appliquée a été modifiée | Annuler la modification et créer une nouvelle migration `V3__...` |
| Les données d'un enum changent de sens après ajout d'une valeur | `EnumType.ORDINAL` (le défaut si on ne met rien) | Toujours `@Enumerated(EnumType.STRING)` |
| Une modification n'est pas enregistrée | L'entité est détachée (chargée hors transaction ou dans une autre) | Charger et modifier dans la même méthode `@Transactional` |
| Une modification est enregistrée alors qu'on ne voulait pas | Dirty checking : toute entité managée modifiée est écrite | Ne pas modifier une entité "pour l'affichage" ; travailler sur le DTO |
| L'ordre d'une liste change d'un appel à l'autre | Aucun `Sort` : SQL ne garantit aucun ordre | Toujours trier ce qui est renvoyé au client |
| `catch (ConstraintViolationException e)` n'attrape rien | Confusion entre `jakarta.validation.ConstraintViolationException` (validation) et `org.hibernate.exception.ConstraintViolationException` (base) | Vérifier l'import ; côté Spring, l'erreur arrive enveloppée dans `DataIntegrityViolationException` |
| Erreur de contrainte remontée "trop tard", hors du `try` | Le SQL n'est envoyé qu'au flush, à la fin de la transaction | `saveAndFlush` ou `flush()` dans le `try` |

---

## 7. Pour aller plus loin

- **`@Query`** : écrire soi-même la requête en JPQL (langage de requête sur les entités) ou en SQL natif, quand une
  méthode dérivée devient illisible. Exemple : `@Query("select t from Task t join fetch t.assignee where t.status = :status")`.
- **Projections** : renvoyer directement un DTO ou une interface depuis le repository, sans charger l'entité entière.
- **`Pageable` et `Page<T>`** : la pagination (`findAll(PageRequest.of(0, 20, Sort.by("id")))`), indispensable dès
  que la table grossit. Se combine avec `@RequestParam` côté contrôleur ([05. Web et REST](05-web-rest.md)).
- **`@Version` (verrouillage optimiste)** : une colonne de version incrémentée à chaque `UPDATE`. Si deux personnes
  modifient la même tâche en même temps, la seconde reçoit une erreur au lieu d'écraser la première.
- **Audit Spring Data** (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` avec
  `@EnableJpaAuditing`) : alternative à `@CreationTimestamp` / `@PreUpdate`, et surtout le moyen d'enregistrer
  **qui** a fait la modification, une fois l'authentification en place ([10. Sécurité](10-securite.md)).
- **Relations bidirectionnelles** (`@OneToMany(mappedBy = ...)`) et leurs pièges (boucles infinies en JSON,
  `cascade`, `orphanRemoval`).
- **Index** : une colonne souvent filtrée (`status`, `assignee_id`) gagne à être indexée, dans une migration.

Documentation officielle : les liens de la section 4 (chaque sous-section se termine par ses sources), en
particulier :
- [Spring Data JPA : Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Hibernate ORM 7.4 User Guide](https://docs.hibernate.org/orm/7.4/userguide/html_single/) et, plus abordable,
  [An Introduction to Hibernate 7.4](https://docs.hibernate.org/orm/7.4/introduction/html_single/)
- [Flyway : Migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations)
- [Spring Boot : SQL Databases](https://docs.spring.io/spring-boot/reference/data/sql.html)
- *Jakarta Persistence Specification*

---

## 8. Quiz

**1. Quelle est la différence entre JPA et Hibernate ?**

<details><summary>Réponse</summary>

JPA est une spécification (des interfaces et des annotations, dans `jakarta.persistence`). Hibernate est une
implémentation de cette spécification : c'est lui qui génère et exécute le SQL. Spring Data JPA se pose par-dessus
pour générer les repositories.
</details>

**2. Pourquoi `TaskService.updateTask` n'appelle-t-il pas `save`, et à quoi sert son `flush()` ?**

<details><summary>Réponse</summary>

La tâche, chargée par `findById` dans une transaction, est managée : Hibernate détecte ses modifications (dirty
checking) et écrit l'`UPDATE` tout seul. Le `flush()` force cet `UPDATE` immédiatement, ce qui déclenche
`@PreUpdate` et remplit `updatedDate` avant la construction du DTO de réponse.
</details>

**3. Avec 3 tâches assignées à 2 utilisateurs différents, combien de requêtes ferait `findAll` sans `@EntityGraph`,
si le code lit l'assigné de chaque tâche ?**

<details><summary>Réponse</summary>

3 : une pour les tâches, puis une par assigné **distinct** (2), car Hibernate réutilise un utilisateur déjà chargé
dans la même transaction. Avec `@EntityGraph`, une seule.
</details>

**4. Tu veux ajouter une colonne `due_date` à la table `task`. Que fais-tu ?**

<details><summary>Réponse</summary>

Tu crées une nouvelle migration `V3__add_due_date_to_task.sql` (sans toucher à V2, déjà appliquée), et tu ajoutes
le champ correspondant dans l'entité `Task`. Au démarrage, Flyway applique V3, puis Hibernate valide que la colonne
existe bien.
</details>

**5. Pourquoi `UserService.createUser` vérifie-t-il les doublons avec `existsBy...` ET attrape-t-il
`DataIntegrityViolationException` ?**

<details><summary>Réponse</summary>

La vérification préalable couvre le cas normal. Mais entre la vérification et l'`INSERT`, une autre requête
simultanée peut insérer le même utilisateur : seule la contrainte unique de la base est fiable dans ce cas. Le
`catch` traduit alors cette erreur technique en 409 lisible.
</details>

**6. Pourquoi ne jamais utiliser `EnumType.ORDINAL` ?**

<details><summary>Réponse</summary>

Il stocke la position de la valeur dans l'enum. Ajouter, retirer ou réordonner une valeur décale toutes les
positions et change silencieusement le sens des données déjà en base. `STRING` stocke le nom, stable.
</details>
