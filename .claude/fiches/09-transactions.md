# 09. Transactions

**Ce que tu vas comprendre**
- Ce qu'est une transaction et pourquoi "tout ou rien" protège tes données.
- Comment `@Transactional` fonctionne réellement (un proxy autour de ton service), et quand il est ignoré.
- Ce que changent `readOnly`, la propagation et les règles de rollback.
- Pourquoi le projet désactive l'Open Session In View, et ce que ça impose.
- Pourquoi une transaction ne suffit pas contre deux requêtes simultanées.
- Ce qui se passe réellement, logs à l'appui : la transaction rangée dans le thread, la propagation, ce que fait
  vraiment `readOnly`, le flush au commit, l'auto-invocation et le rollback des tests, puis un `@Transactional` à
  écrire toi-même.

**Prérequis** : [02. Beans et injection](02-beans-injection.md) (la notion de proxy),
[08. Persistance](08-persistance.md) (contexte de persistance, flush, LAZY).

---

## 1. Le problème que ça résout

Prends un virement bancaire : débiter le compte A, puis créditer le compte B. Si le serveur plante entre les deux,
l'argent a disparu. Il faut que les deux opérations réussissent **ensemble** ou échouent **ensemble**.

C'est exactement ce qu'est une **transaction** : un groupe d'opérations sur la base qui se comporte comme une seule.
Soit tout est validé (**commit**), soit tout est annulé (**rollback**), comme si rien ne s'était passé.

Dans le task-tracker, l'inscription (`AuthService.register`) vérifie que le nom d'utilisateur est libre, vérifie
l'email, puis insère l'utilisateur. Demain, elle pourrait aussi créer une tâche de bienvenue. Si cette seconde
insertion échoue, tu ne veux pas d'un utilisateur à moitié créé.

**Sans Spring**, tu écrirais à chaque méthode :

```java
Connection connection = dataSource.getConnection();
try {
	connection.setAutoCommit(false);
	// ... tes opérations ...
	connection.commit();
} catch (Exception e) {
	connection.rollback();
	throw e;
} finally {
	connection.close();
}
```

Avec Spring, tu écris `@Transactional` et c'est tout.

---

## 2. Les concepts

### 2.1 ACID en une phrase par lettre

- **Atomicité** : tout ou rien.
- **Cohérence** : la base passe d'un état valide à un autre (les contraintes sont respectées à la fin).
- **Isolation** : deux transactions simultanées ne voient pas les états intermédiaires l'une de l'autre (dans une
  certaine mesure, voir §2.7).
- **Durabilité** : une fois validé, c'est écrit, même si le serveur s'éteint juste après.

### 2.2 Comment marche `@Transactional` : le proxy

Quand Spring crée le bean `TaskService`, il voit `@Transactional` et n'injecte pas directement ton objet : il
injecte un **proxy**, un objet intermédiaire qui l'enveloppe.

```
TaskController
   │  taskService.updateTask(1, dto)
   ▼
┌──────────── Proxy de TaskService ─────────────┐
│ 1. ouvre une transaction (prend une connexion) │
│ 2. appelle le vrai TaskService.updateTask ───────────► ton code
│ 3. pas d'exception ? → flush + commit          │
│    RuntimeException ? → rollback               │
│ 4. rend la connexion au pool                   │
└────────────────────────────────────────────────┘
```

Conséquences directes, à connaître par cœur :

1. **L'appel doit passer par le proxy.** Si une méthode de `TaskService` appelle une autre méthode de
   `TaskService` (`this.autreMethode()`), elle ne passe pas par le proxy : l'annotation de la méthode appelée est
   **ignorée**. C'est le piège de l'**auto-invocation**.
2. **La méthode doit être publique** et appelée depuis un autre bean. Sur une méthode `private`, `@Transactional`
   ne sert à rien.
3. La transaction dure **le temps de la méthode** annotée, pas plus.

### 2.3 Sur la classe ou sur la méthode

`@Transactional` posé sur la **classe** s'applique à toutes ses méthodes publiques. Posé sur une **méthode**, il
remplace celui de la classe pour cette méthode.

Le schéma utilisé dans le projet :

```java
@Service
@Transactional(readOnly = true)     // par défaut : lecture seule
public class TaskService {

	public List<TaskResponseDto> getAllTasks() { ... }    // hérite de readOnly = true

	@Transactional                                       // écriture : readOnly = false
	public TaskResponseDto createTask(...) { ... }
}
```

Avantage : impossible d'oublier la transaction sur une lecture, et chaque écriture est visiblement marquée.

### 2.4 `readOnly = true`

C'est une **indication**, pas une interdiction absolue. Elle permet à Spring et Hibernate d'optimiser : en
lecture seule, Hibernate n'a pas besoin de faire de flush, donc pas de dirty checking à la fin. Selon la base et le
driver, la connexion peut aussi être passée en mode lecture seule : c'est le cas avec PostgreSQL dans ce projet
(voir 4.4). Ne compte pas dessus comme mécanisme de sécurité : une entité modifiée dans une transaction `readOnly`
n'est simplement pas écrite, sans erreur. C'est surtout une optimisation et une documentation de l'intention.

### 2.5 Les règles de rollback

Par défaut, Spring annule la transaction si une exception **non vérifiée** sort de la méthode : `RuntimeException`
et ses sous-classes, ou `Error`. Une exception **vérifiée** (`Exception` qui n'est pas une `RuntimeException`,
comme `IOException`) provoque un **commit** par défaut.

C'est l'une des raisons pour lesquelles les exceptions métier du projet (`TaskNotFoundException`,
`UserAlreadyExistsException`) héritent de `RuntimeException` (voir [07. Exceptions](07-exceptions.md)).

Points subtils :

- Si tu **attrapes** l'exception à l'intérieur de la méthode et ne la relances pas, la méthode se termine
  normalement et Spring tente un commit.
- On peut changer les règles : `@Transactional(rollbackFor = IOException.class)` ou `noRollbackFor = ...`.

### 2.6 La propagation : que se passe-t-il quand une méthode transactionnelle en appelle une autre ?

`AuthService.register` (transactionnelle) appelle `UserService.createUser` (transactionnelle). Une ou deux
transactions ?

Avec la propagation par défaut, **`REQUIRED`** : "s'il y a déjà une transaction, je la rejoins, sinon j'en crée
une". Donc **une seule** transaction, ouverte par `register`. Si `createUser` échoue, tout est annulé.

| Propagation | Comportement | Usage typique |
|---|---|---|
| `REQUIRED` (défaut) | Rejoint la transaction existante ou en crée une | 99 % des cas |
| `REQUIRES_NEW` | Suspend la transaction en cours et en ouvre une nouvelle, indépendante | Écrire un journal d'audit qui doit rester même si l'opération principale échoue |
| `SUPPORTS` | Rejoint s'il y en a une, sinon s'exécute sans | Rare |
| `MANDATORY` | Exige une transaction existante, sinon erreur | Garde-fou |

Piège lié : quand une méthode interne qui a **rejoint** la transaction lève une `RuntimeException`, la transaction
entière est marquée "à annuler". Même si la méthode externe attrape l'exception et continue, le commit final
échouera (`UnexpectedRollbackException`).

### 2.7 L'isolation et la concurrence

Deux requêtes HTTP arrivent en même temps : chacune a sa propre transaction. Que voient-elles l'une de l'autre ?

PostgreSQL utilise par défaut le niveau **READ COMMITTED** : une transaction ne voit que ce que les autres ont déjà
**validé**. C'est raisonnable, mais ça n'empêche pas ce scénario, appelé *check-then-act* :

```
Requête A                              Requête B
existsByEmail("x") → false
                                       existsByEmail("x") → false
INSERT x
COMMIT
                                       INSERT x   ← doublon !
```

Chaque transaction, prise seule, est correcte. Ensemble, elles créent un doublon. Les transactions ne te protègent
pas de ça ; ce qui te protège, c'est une **contrainte unique en base**, qui fait échouer le second `INSERT`. C'est
exactement la stratégie du projet (voir §3.4).

Il existe des niveaux plus stricts (`REPEATABLE READ`, `SERIALIZABLE`, via `@Transactional(isolation = ...)`) et des
verrous (`@Lock`, `@Version`), mais ils ont un coût. Pour l'unicité, la contrainte en base reste la solution
simple et fiable.

### 2.8 Open Session In View et `LazyInitializationException`

Rappel de [08. Persistance](08-persistance.md) : une relation LAZY ne peut se charger que tant que la session
Hibernate (liée à la transaction) est ouverte.

Spring Boot active par défaut un mécanisme appelé **Open Session In View** (OSIV) : la session reste ouverte pendant
**toute** la requête HTTP, y compris dans le contrôleur et pendant l'écriture du JSON. Pratique : les relations LAZY
se chargent n'importe où. Mais :

- des requêtes SQL peuvent partir de n'importe où, y compris pendant la sérialisation JSON, sans que tu le voies ;
- la connexion à la base peut être gardée plus longtemps que nécessaire ;
- les N+1 se cachent facilement.

Spring Boot affiche d'ailleurs un avertissement au démarrage tant que tu n'as pas choisi explicitement.

Avec `spring.jpa.open-in-view=false`, la session se ferme à la fin de la méthode `@Transactional`. Accéder ensuite à
une relation non chargée lève :

```
org.hibernate.LazyInitializationException: Could not initialize proxy [com.personal.task_tracker.user.entity.AppUser#1] - no session
```

La règle qui en découle : **le service renvoie des DTO complets**, construits à l'intérieur de la transaction. Le
contrôleur ne touche jamais une entité. C'est plus strict, mais prévisible : tout le SQL d'une requête part du
service.

### 2.9 Transactions et tests

Dans beaucoup de projets, on pose `@Transactional` sur une classe de test pour que chaque test soit annulé à la fin.
**Ça ne marche pas** avec `@SpringBootTest(webEnvironment = RANDOM_PORT)` : la requête HTTP est traitée par un autre
thread (celui du serveur), dans sa propre transaction, que le test ne contrôle pas. Les données sont réellement
validées. D'où, dans le projet, le nettoyage explicite de la base après chaque test (voir [11. Tests](11-tests.md)).

---

## 3. Dans le projet

### 3.1 Le schéma "readOnly par défaut"

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java`

```java
19	@Service
20	@Transactional(readOnly = true)
21	public class TaskService {
...
35		public List<TaskResponseDto> getAllTasks() {
36			return repository.findAll(Sort.by("id")).stream().map(TaskMapper::toTaskResponseDto).toList();
37		}
...
57		@Transactional
58		public TaskResponseDto createTask(TaskRequestDto taskDto) {
...
80		@Transactional
81		public TaskResponseDto updateTask(Long taskId, TaskRequestDto taskDto) {
...
133		@Transactional
134		public void deleteTask(Long taskId) {
```

- Ligne 20 : toutes les méthodes sont en lecture seule par défaut.
- Lignes 57, 80, 107 (`updatePartialTask`) et 133 : les quatre écritures repassent en lecture-écriture.
- `UserService` suit le même schéma (`UserService.java`, ligne 22, puis `@Transactional` ligne 66 sur `createUser`).

### 3.2 Le mapping dans la transaction

```java
46		public TaskResponseDto getTaskById(Long taskId) {
47			return TaskMapper.toTaskResponseDto(findTaskById(taskId));
48		}
```

`findTaskById` charge la tâche sans son assigné (relation LAZY, `findById` n'a pas d'`@EntityGraph`). Le mapper lit
ensuite `task.getAssignee()` : comme on est **encore dans la transaction** de `getTaskById`, Hibernate peut charger
l'assigné (une seconde requête). Si le mapping était fait dans le contrôleur, avec `open-in-view=false`, ce serait
une `LazyInitializationException`.

C'est aussi pourquoi `findTaskById` (ligne 146) peut rester `private` sans problème : elle ne porte pas
d'annotation, elle s'exécute simplement dans la transaction de la méthode publique qui l'appelle.

### 3.3 La propagation à l'inscription

`server/src/main/java/com/personal/task_tracker/auth/service/AuthService.java`

```java
26		@Transactional
27		public RegisterResponseDto register(RegisterRequestDto userDto) {
28			AppUser createdUser = userService.createUser(AuthMapper.toUserCreateDto(userDto));
29			return AuthMapper.toRegisterResponseDto(createdUser);
30		}
```

- Ligne 26 : `register` ouvre la transaction.
- Ligne 28 : `createUser` est aussi `@Transactional` ; avec `REQUIRED`, elle **rejoint** celle de `register`.
  L'appel passe bien par le proxy de `UserService` (c'est un autre bean), donc il n'y a pas de piège
  d'auto-invocation.
- Ligne 29 : le mapping vers le DTO se fait avant la fin de la transaction.

### 3.4 Rollback et concurrence à l'inscription

`server/src/main/java/com/personal/task_tracker/user/service/UserService.java`

```java
66		@Transactional
67		public AppUser createUser(UserCreateDto userDto) {
...
71			if (repository.existsByUsernameIgnoreCase(username)) {
72				throw UserAlreadyExistsException.username();
73			}
...
81			try {
82				return repository.saveAndFlush(user);
83			} catch (DataIntegrityViolationException exception) {
...
85				if (USERNAME_UNIQUE_INDEX.equals(constraintName)) {
86					throw UserAlreadyExistsException.username(exception);
87				}
...
91				throw exception;
```

- Lignes 71 à 73 : le *check* du scénario check-then-act. Insuffisant seul face à deux requêtes simultanées.
- Ligne 82 : l'*act*. Si une autre transaction a inséré le même nom entre-temps, l'index unique fait échouer
  l'`INSERT`.
- Lignes 83 à 91 : l'exception est attrapée **puis relancée** (traduite ou telle quelle). Une `RuntimeException`
  sort donc de la méthode : rollback. On n'avale jamais l'erreur en silence.

Le test qui reproduit la course, `server/src/test/java/com/personal/task_tracker/auth/AuthControllerTest.java` :

```java
218		void shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex() {
219			saveUser("JDoe", "jdoe@mail.com");
220			// Simulates a concurrent registration: the check passes, the insert hits the unique index
221			doReturn(false).when(repository).existsByUsernameIgnoreCase(anyString());
...
229					.statusCode(409)
...
234			assertThat(repository.count()).isEqualTo(1);
```

- Ligne 221 : on force le *check* à répondre "libre", comme si l'autre inscription n'était pas encore validée.
- Ligne 234 : après le rollback, il n'y a toujours qu'un seul utilisateur.

### 3.5 La configuration OSIV

`server/src/main/resources/application.properties`, ligne 3 :

```properties
3	spring.jpa.open-in-view=false
```

Choix explicite : la session Hibernate ne déborde pas du service.

### 3.6 Les tests et le nettoyage

`server/src/test/java/com/personal/task_tracker/IntegrationTest.java`, lignes 46 à 52 : pas de `@Transactional`
sur les tests, mais un `@AfterEach` qui vide les tables (les tâches d'abord, car elles référencent les
utilisateurs). Conséquence directe du §2.9.

---

## 4. Sous le capot

La fiche [02. Beans et injection](02-beans-injection.md) a déjà montré **qui** intercepte l'appel : un proxy CGLIB
généré autour de `TaskService`, avec `TransactionInterceptor` dans la pile d'appels et le log de `getAllTasks()`
(section 4.4). Cette section montre **ce que fait** ce proxy une fois appelé : où il range la transaction, comment
les méthodes suivantes la retrouvent, et ce que changent réellement `readOnly`, la propagation et les exceptions.
Même plan que dans la fiche 02 : le mécanisme, l'observer, ce qui se passerait sans, la source.

Tous les extraits sont réels : ils viennent d'un test d'intégration temporaire lancé sur le projet (PostgreSQL 16
via Testcontainers, Spring Framework 7.0.9, Hibernate 7.4.5), avec
`logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG` et
`logging.level.org.springframework.transaction.interceptor=TRACE`. Horodatages retirés, noms de classes raccourcis
(`...`). Les lignes `@@@` sont des `System.out.println` du test. Quand le thread compte, il est indiqué entre
crochets, comme dans le vrai log.

Pour reproduire, en lançant l'application :
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.arguments=--logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG --logging.level.org.springframework.transaction.interceptor=TRACE"`

### 4.1 Où est rangée la transaction courante : dans le thread

**Le mécanisme.** Quand le proxy démarre une transaction, `JpaTransactionManager` :

1. ouvre une session Hibernate (`EntityManager`) ;
2. emprunte une connexion au pool Hikari et coupe son auto-commit ;
3. range les deux dans `TransactionSynchronizationManager`, une classe de Spring qui n'est qu'un ensemble de
   variables `ThreadLocal` : des cases mémoire propres à **chaque thread**.

Ensuite, personne ne se passe la transaction en paramètre. Le repository, l'`EntityManager` injecté (un proxy partagé,
voir [08. Persistance](08-persistance.md), section 4.4) ou un autre `@Transactional` regardent simplement dans le
thread courant. Une requête HTTP étant traitée de bout en bout par un seul thread de Tomcat, tout fonctionne sans
que tu y penses.

**L'observer.** Le test affiche l'état de `TransactionSynchronizationManager` à différents moments :

```
@@@ [main] test method, no tx  active=false name=null readOnly=false resources=[]
@@@ [main] outer  active=true name=...ProbeService.otherThread readOnly=false resources=[$Proxy174->EntityManagerHolder, HikariDataSource->ConnectionHolder]
```

Les deux ressources rangées dans le thread : l'`EntityManagerFactory` (vue à travers son proxy Spring) associée à la
session ouverte, et la `HikariDataSource` associée à la connexion JDBC. Côté logs, c'est la paire
`Opened new EntityManager [SessionImpl(...)] for JPA transaction` puis
`Exposing JPA transaction as JDBC [...HibernateConnectionHandle@...]` au démarrage, et
`Found thread-bound EntityManager [SessionImpl(...)] for JPA transaction` chaque fois que quelqu'un vient la chercher.
"Thread-bound" : attachée au thread.

**Pourquoi ça casse avec un autre thread.** Vérifié : une méthode `@Transactional` enregistre un utilisateur
`outer`, lance un thread qui enregistre un utilisateur `worker`, attend sa fin, puis lève une `RuntimeException`.

```
@@@ [main]          outer  active=true ... resources=[$Proxy174->EntityManagerHolder, HikariDataSource->ConnectionHolder]
    [main]          insert into app_user (...)
@@@ [worker-thread] worker active=false name=null readOnly=false resources=[]
    [worker-thread] Creating new transaction with name [...SimpleJpaRepository.save]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
    [worker-thread] insert into app_user (...)
    [worker-thread] Committing JPA transaction on EntityManager [SessionImpl(955850752<open>)]
    [main]          Initiating transaction rollback
    [main]          Rolling back JPA transaction on EntityManager [SessionImpl(1471794371<open>)]
@@@ [main]          users after: [worker]
```

Le thread `worker-thread` trouve des cases vides : il ne voit pas la transaction de `main`. Le `save` de
`SimpleJpaRepository` en ouvre donc une nouvelle (autre session, autre connexion), qui est validée tout de suite. Le
rollback de `main` annule `outer`, mais `worker` reste en base. Même résultat avec tout ce qui change de thread :
`@Async`, `CompletableFuture.supplyAsync(...)`, `parallelStream()`, un thread virtuel. Et c'est aussi pour cela que
`@Transactional` sur un test ne couvre pas le thread de Tomcat (4.8).

**La source.** [Synchronizing Resources with Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/tx-resource-synchronization.html),
[TransactionSynchronizationManager (javadoc)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronizationManager.html),
[Understanding the Spring Framework Transaction Abstraction](https://docs.spring.io/spring-framework/reference/data-access/transaction/strategies.html).

### 4.2 La propagation, lue dans les logs

**Le mécanisme.** À chaque méthode transactionnelle, le proxy demande une transaction au gestionnaire. Celui-ci
regarde d'abord le thread (4.1) :

- rien dans le thread : il en **crée** une (`Creating new transaction`) ;
- une transaction existe et la propagation est `REQUIRED` : il la fait **rejoindre** (`Participating in existing
  transaction`). Pas de nouvelle session, pas de nouvelle connexion, et surtout **pas de commit** à la fin de cette
  méthode : seul celui qui a créé la transaction la valide ;
- `REQUIRES_NEW` : il retire la transaction en cours du thread, la met de côté (`Suspending`), en ouvre une nouvelle,
  puis remet l'ancienne en place (`Resuming`).

**L'observer.** `AuthService.register`, appelée directement dans le test (log réel, complet) :

```
Creating new transaction with name [com.personal.task_tracker.auth.service.AuthService.register]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
Opened new EntityManager [SessionImpl(912577157<open>)] for JPA transaction
Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@827f9c8]
Getting transaction for [com.personal.task_tracker.auth.service.AuthService.register]
Found thread-bound EntityManager [SessionImpl(912577157<open>)] for JPA transaction
Participating in existing transaction
Getting transaction for [com.personal.task_tracker.user.service.UserService.createUser]
No need to create transaction for [...SimpleJpaRepository.existsByUsernameIgnoreCase]: This method is not transactional.
select au1_0.id from app_user au1_0 where upper(au1_0.username)=upper(?) fetch first ? rows only
No need to create transaction for [...SimpleJpaRepository.existsByEmail]: This method is not transactional.
select au1_0.id from app_user au1_0 where au1_0.email=? fetch first ? rows only
Found thread-bound EntityManager [SessionImpl(912577157<open>)] for JPA transaction
Participating in existing transaction
Getting transaction for [...SimpleJpaRepository.saveAndFlush]
insert into app_user (created_date,email,firstname,lastname,password,role,updated_date,username) values (?,?,?,?,?,?,?,?)
Completing transaction for [...SimpleJpaRepository.saveAndFlush]
Completing transaction for [com.personal.task_tracker.user.service.UserService.createUser]
Completing transaction for [com.personal.task_tracker.auth.service.AuthService.register]
Initiating transaction commit
Committing JPA transaction on EntityManager [SessionImpl(912577157<open>)]
Closing JPA EntityManager after transaction
```

À lire :

- un seul `Creating new transaction`, un seul `Committing`, et le même numéro de session `SessionImpl(912577157)`
  du début à la fin : c'est bien **une** transaction, comme annoncé en 3.3 ;
- `createUser` et `saveAndFlush` "participent". Leur `Completing transaction` ne fait rien en base ;
- les deux `existsBy...` sont marquées `This method is not transactional` : Spring Data ne met `@Transactional` que
  sur les méthodes de `SimpleJpaRepository` (en `readOnly` pour les lectures), pas sur les méthodes que **tu**
  déclares dans l'interface. Ici ce n'est pas un problème, elles s'exécutent dans la transaction de `register`. C'est
  le même mécanisme qui fait rejoindre `SimpleJpaRepository.findAll` dans le log de la fiche 02 (section 4.4).

Et `REQUIRES_NEW`, avec deux `TransactionTemplate` imbriqués (l'équivalent programmatique de `@Transactional`) :

```
Creating new transaction with name [outerTx]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
Opened new EntityManager [SessionImpl(867222659<open>)] for JPA transaction
Suspending current transaction, creating new transaction with name [innerTx]
Opened new EntityManager [SessionImpl(1965312210<open>)] for JPA transaction
Committing JPA transaction on EntityManager [SessionImpl(1965312210<open>)]
Resuming suspended transaction after completion of inner transaction
Committing JPA transaction on EntityManager [SessionImpl(867222659<open>)]
```

Deux sessions, donc deux connexions empruntées **en même temps** : la transaction suspendue garde la sienne. Un
`REQUIRES_NEW` appelé dans une boucle, sous charge, consomme deux connexions du pool par requête.

**La source.** [Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html),
[Spring Data JPA : Transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).

### 4.3 Le participant qui échoue : rollback-only

**Le mécanisme.** Un participant (4.2) ne possède pas la transaction : il ne peut pas l'annuler lui-même. Quand une
`RuntimeException` en sort, il pose un **drapeau** "à annuler" sur la transaction partagée. Le créateur, au moment de
valider, voit le drapeau, annule, et lève `UnexpectedRollbackException` pour que l'annulation ne passe pas inaperçue.
C'est le piège annoncé en 2.6.

**L'observer.** Vérifié avec le vrai `UserService` : une méthode `@Transactional` appelle
`userService.findUserById(999L)`, attrape la `UserNotFoundException` et continue normalement.

```
Participating in existing transaction
Getting transaction for [com.personal.task_tracker.user.service.UserService.findUserById]
select au1_0.id,...,au1_0.username from app_user au1_0 where au1_0.id=?
Completing transaction for [...UserService.findUserById] after exception: com.personal.task_tracker.user.exception.UserNotFoundException: There is no user with id: 999
Participating transaction failed - marking existing transaction as rollback-only
Setting JPA transaction on EntityManager [SessionImpl(281586672<open>)] rollback-only
@@@ caught UserNotFoundException
@@@ end of catchAndContinue
Initiating transaction commit
Committing JPA transaction on EntityManager [SessionImpl(281586672<open>)]
@@@ catchAndContinue threw org.springframework.transaction.UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
```

Deux remarques. `findUserById` est une simple **lecture** (`readOnly` hérité de la classe), et pourtant elle
condamne toute la transaction. Et au niveau TRACE, l'intercepteur affiche aussi la pile complète de l'exception
(`MethodRollbackEvent`) : ce n'est pas une erreur de plus, c'est la même.

**La source.** [Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
(paragraphe "Understanding PROPAGATION_REQUIRED").

### 4.4 Ce que fait vraiment `readOnly = true`

**Le mécanisme.** `readOnly = true` agit à trois étages :

1. **Spring** note l'information dans le thread (`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`).
2. **Hibernate** reçoit deux réglages : le flush mode de la session passe à `MANUAL` (plus aucun flush automatique,
   donc plus de dirty checking au commit, voir [08. Persistance](08-persistance.md), section 4.5), et les entités
   chargées sont marquées en lecture seule.
3. **JDBC** : la connexion est passée en `setReadOnly(true)`. Le driver PostgreSQL (option `readOnlyMode`, qui vaut
   `transaction` par défaut) ouvre alors la transaction avec `BEGIN READ ONLY`, et c'est **PostgreSQL** qui refuse
   les écritures.

**L'observer.** Dans une transaction `readOnly` :

```
@@@ flushMode=MANUAL defaultReadOnly=true
@@@ jdbc readOnly=true
org.hibernate.SQL : show transaction_read_only
@@@ pg transaction_read_only=on
@@@ entity readOnly in session=true
```

(respectivement `session.getHibernateFlushMode()`, `session.isDefaultReadOnly()`, `connection.isReadOnly()`, la
commande PostgreSQL `show transaction_read_only`, et `session.isReadOnly(task)`).

Deux conséquences vérifiées, très différentes :

- modifier une entité chargée (`task.setTitle(...)`) : **aucun `UPDATE`, aucune erreur**, le titre en base reste
  `Original`. Hibernate n'a simplement rien envoyé ;
- enregistrer un **nouvel** utilisateur (`save`, donc `INSERT` immédiat à cause d'`IDENTITY`) : c'est PostgreSQL qui
  refuse.
  ```
  WARN org.hibernate.orm.jdbc.error : HHH000247: ErrorCode: 0, SQLState: 25006
  WARN org.hibernate.orm.jdbc.error : ERROR: cannot execute INSERT in a read-only transaction
  @@@ RO insert threw org.springframework.orm.jpa.JpaSystemException: could not execute statement [ERROR: cannot execute INSERT in a read-only transaction] [insert into app_user ...]
  ```

D'où la nuance de 2.4 : dans ce projet, un SQL d'écriture qui part réellement est bloqué par la base, mais une
modification d'entité est perdue en silence. Ce n'est pas une barrière, c'est une optimisation (pas de photo à
comparer, pas de flush) doublée d'un filet.

**La source.** [Using @Transactional, attribut readOnly](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html),
[Driver PostgreSQL : paramètre readOnlyMode](https://jdbc.postgresql.org/documentation/use/),
[Spring Data JPA : Transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).

### 4.5 Exception vérifiée ou non : la règle exacte

**Le mécanisme.** Dans son bloc `catch`, l'intercepteur demande à l'attribut de transaction
`rollbackOn(exception)`. La règle par défaut : `true` si l'exception est une `RuntimeException` ou une `Error`,
`false` sinon. Si c'est `false`, il **valide**, puis laisse l'exception remonter quand même. C'est une convention
héritée des EJB : une exception vérifiée est vue comme un résultat métier prévu, pas comme une panne.

**L'observer.** Deux méthodes qui enregistrent un utilisateur puis lèvent une exception :

```
Completing transaction for [...ProbeService.saveThenChecked] after exception: java.lang.Exception: checked
Initiating transaction commit
Committing JPA transaction on EntityManager [SessionImpl(1820021131<open>)]
...
Completing transaction for [...ProbeService.saveThenUnchecked] after exception: java.lang.IllegalStateException: unchecked
Initiating transaction rollback
Rolling back JPA transaction on EntityManager [SessionImpl(1470961753<open>)]
@@@ users after: [checked]
```

L'utilisateur `checked` est **en base**, alors que la méthode a échoué. Si `TaskNotFoundException` ou
`UserAlreadyExistsException` étaient des exceptions vérifiées, c'est ce qui arriverait aux écritures faites avant
elles. C'est la raison technique du choix de [07. Exceptions](07-exceptions.md).

**La source.** [Rolling Back a Declarative Transaction](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html),
[Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
("follows EJB convention").

### 4.6 Le flush a lieu **pendant** le commit

**Le mécanisme.** Au commit, `JpaTransactionManager` demande à Hibernate de valider. Hibernate fait alors le flush
(dirty checking, envoi des `UPDATE` en attente), **puis** le `COMMIT` sur la connexion JDBC. Tout cela se passe dans le
proxy, **après** le retour de ta méthode.

**L'observer.** Une entité chargée et modifiée, sans `save` ni `flush` :

```
select t1_0.id,...,t1_0.updated_date from task t1_0 where t1_0.id=?
@@@ end of callback
Initiating transaction commit
Committing JPA transaction on EntityManager [SessionImpl(1562081879<open>)]
update task set assignee_id=?,description=?,priority=?,status=?,title=?,type=?,updated_date=? where id=?
Closing JPA EntityManager after transaction
```

L'`UPDATE` apparaît **après** `Committing` : il fait partie du commit. Conséquence directe : si cet `UPDATE` viole une
contrainte, l'exception naît dans le proxy, hors de ton code, et aucun `try/catch` de ta méthode ne peut l'attraper.
C'est pour cela que `UserService.createUser` utilise `saveAndFlush` dans son `try` (3.4), et que
`TaskService.updateTask` appelle `repository.flush()` avant de construire la réponse
([08. Persistance](08-persistance.md), section 3.5).

**La source.** [Hibernate User Guide : Flushing](https://docs.hibernate.org/orm/7.4/userguide/html_single/#flushing),
[COMMIT flush mode](https://docs.hibernate.org/orm/7.4/userguide/html_single/#flushing-commit).

### 4.7 L'auto-invocation, démontrée

**Le mécanisme.** Rappel de 02 (section 4.4) : dans le vrai objet, `this` désigne l'objet, pas le proxy. Un appel
`this.methode()` ne passe donc jamais par `TransactionInterceptor`.

**L'observer.** Un bean de test (classe générée `ProbeService$$SpringCGLIB$$0`, donc bien proxifié) avec une méthode
`@Transactional(readOnly = true)` `outerReadOnly` qui appelle `this.innerWrite(id)`, elle-même `@Transactional`
(lecture-écriture) et qui modifie le titre d'une tâche :

```
Creating new transaction with name [...ProbeService.outerReadOnly]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,readOnly
Getting transaction for [...ProbeService.outerReadOnly]
select t1_0.id,... from task t1_0 where t1_0.id=?
@@@ innerWrite active=true name=...ProbeService.outerReadOnly readOnly=true ...
Completing transaction for [...ProbeService.outerReadOnly]
Committing JPA transaction on EntityManager [SessionImpl(1456338587<open>)]
@@@ title after self-invocation: Original
```

Aucune ligne `Getting transaction for [...innerWrite]` : l'intercepteur n'a jamais vu l'appel. `innerWrite` tourne
dans la transaction **readOnly** de `outerReadOnly` (le nom l'indique), et sa modification est perdue sans erreur
(4.4). Le même `innerWrite` appelé **depuis l'extérieur**, donc via le proxy :

```
Creating new transaction with name [...ProbeService.innerWrite]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
@@@ innerWrite active=true name=...ProbeService.innerWrite readOnly=false ...
Committing JPA transaction on EntityManager [SessionImpl(942009127<open>)]
update task set assignee_id=?,description=?,priority=?,status=?,title=?,type=?,updated_date=? where id=?
@@@ title after direct call: Written
```

C'est exactement la réponse de l'exercice 3, observée.

**La source.** [Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
(passage sur le mode proxy : "self-invocation").

### 4.8 `@Transactional` sur un test : ce qui est vraiment annulé

**Le mécanisme.** Sur une méthode de test, `@Transactional` est lu par `TransactionalTestExecutionListener` (pas par
un proxy) : il ouvre une transaction dans le **thread du test** avant la méthode (et avant les `@BeforeEach`), et
l'annule après (et après les `@AfterEach`). D'après 4.1, elle ne peut donc concerner que ce qui s'exécute dans ce
thread.

**L'observer.** Vérifié avec un test temporaire annoté `@Transactional` : il enregistre `direct` via le repository,
puis appelle `POST /api/auth/register` pour `viahttp`.

```
[main]            Began transaction (1) for test class [...TmpProbeTransactionTest]; test method [f_transactionalTest]; transaction manager [org.springframework.orm.jpa.JpaTransactionManager@b230f87]; rollback [true]
[main]            insert into app_user (...)
[o-auto-1-exec-1] Creating new transaction with name [com.personal.task_tracker.auth.service.AuthService.register]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
[o-auto-1-exec-1] insert into app_user (...)
[o-auto-1-exec-1] Committing JPA transaction on EntityManager [SessionImpl(150125881<open>)]
@@@ [main]        users seen in test tx: [direct, viahttp]
[main]            Rolling back JPA transaction on EntityManager [SessionImpl(84613866<open>)]
[main]            Rolled back transaction (1) for test class [...TmpProbeTransactionTest]; test method [f_transactionalTest]
@@@ [main]        users after test rollback: [viahttp]
```

`o-auto-1-exec-1` est un thread de Tomcat (`http-nio-auto-1-exec-1`, tronqué par le format de log). Il ne voit pas la
transaction du test, en ouvre une et la valide : `viahttp` survit au rollback, `direct` disparaît. Autre piège
visible dans le même log : le `@AfterEach` d'`IntegrationTest` s'exécute **dans** la transaction du test, donc son
`deleteAll()` est annulé lui aussi (ses `select` partent, aucun `delete`). Les deux raisons pour lesquelles le projet
nettoie la base sans `@Transactional` (§2.9 et 3.6).

**La source.** [Spring TestContext : Transaction Management](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tx.html),
[Spring Boot : Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
("the HTTP client and server run in separate threads and, thus, in separate transactions").

### 4.9 Le refaire toi-même : un `@Transactional` en 70 lignes

Proxy, `ThreadLocal`, propagation `REQUIRED`, règle de rollback, auto-invocation : tout tient dans un fichier. Écris-le
**hors du projet** et lance-le avec `java MiniTx.java`. Pas de base de données : la "transaction" est un simple nom.

```java
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

public class MiniTx {

	@Retention(RetentionPolicy.RUNTIME)
	@interface Tx {
	}

	// La transaction courante, rangée dans le thread (comme TransactionSynchronizationManager)
	static final ThreadLocal<String> current = new ThreadLocal<>();
	static int counter = 0;

	interface Service {
		@Tx
		void outer();

		@Tx
		void inner();
	}

	static class RealService implements Service {
		public void outer() {
			System.out.println("  outer() dans " + current.get());
			inner();                                                  // this.inner() : ne passe pas par le proxy
		}

		public void inner() {
			System.out.println("  inner() dans " + current.get());
		}
	}

	@SuppressWarnings("unchecked")
	static <T> T transactional(T target, Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
			if (!method.isAnnotationPresent(Tx.class) || current.get() != null) {
				if (current.get() != null) {
					System.out.println("  " + method.getName() + " rejoint " + current.get());   // REQUIRED
				}
				return method.invoke(target, args);
			}
			current.set("tx-" + (++counter));
			System.out.println("BEGIN " + current.get() + " pour " + method.getName());
			try {
				Object result = method.invoke(target, args);
				System.out.println("COMMIT " + current.get());
				return result;
			} catch (InvocationTargetException e) {
				boolean rollback = e.getCause() instanceof RuntimeException || e.getCause() instanceof Error;
				System.out.println((rollback ? "ROLLBACK " : "COMMIT malgré l'exception ") + current.get());
				throw e.getCause();
			} finally {
				current.remove();                                     // le thread est rendu propre
			}
		});
	}

	public static void main(String[] args) {
		Service service = transactional(new RealService(), Service.class);
		service.outer();                                              // un seul BEGIN : inner() est un appel interne
		service.inner();                                              // via le proxy : sa propre transaction
		System.out.println("hors proxy, transaction courante : " + current.get());
	}
}
```

Sortie :

```
BEGIN tx-1 pour outer
  outer() dans tx-1
  inner() dans tx-1
COMMIT tx-1
BEGIN tx-2 pour inner
  inner() dans tx-2
COMMIT tx-2
hors proxy, transaction courante : null
```

Compare avec Spring : le proxy est ici un proxy JDK (sur une interface) plutôt que CGLIB, mais le principe est celui
de 02 (section 4.4). `current` joue le rôle de `TransactionSynchronizationManager` (4.1), le test
`current.get() != null` celui de `REQUIRED` (4.2), et le calcul de `rollback` la règle de 4.5. Ce qui manque : une
vraie connexion, le flush (4.6), `readOnly` (4.4) et le drapeau rollback-only (4.3).

**Défis :**

1. Dans `main`, garde le proxy dans un champ statique `self`, et remplace dans `outer()` l'appel `inner()` par
   `self.inner()`. Que s'affiche-t-il ?
2. Remplace plutôt `inner()` par un appel `self.inner()` fait dans un **nouveau thread** (`new Thread(...)`, puis
   `join()`). Que s'affiche-t-il, et quelle sous-section cela illustre-t-il ?

<details><summary>Réponses</summary>

1. `inner rejoint tx-1` apparaît entre `outer() dans tx-1` et `inner() dans tx-1` : l'appel passe maintenant par le
   proxy, qui trouve une transaction dans le thread et la rejoint (`REQUIRED`). Un seul `BEGIN`. C'est la correction
   classique de l'auto-invocation, en version simplifiée.
2. `BEGIN tx-2 pour inner` à l'intérieur de `tx-1` : le nouveau thread a son propre `ThreadLocal`, vide, donc le proxy
   ouvre une deuxième transaction indépendante, validée avant `tx-1` (`COMMIT tx-2` puis `COMMIT tx-1`). C'est
   l'expérience du thread `worker` de 4.1.
</details>

---

## 5. À toi de jouer

### Exercice 1 : voir les transactions s'ouvrir

1. Dans ton `application-local.properties`, ajoute
   `logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG`.
2. Appelle `POST /api/auth/register`.
3. **À observer** : un message du type "Creating new transaction with name [...AuthService.register]", puis
   "Participating in existing transaction" pour `createUser`, puis "Committing JPA transaction". Une seule
   transaction, comme prévu par `REQUIRED`.
4. Inscris le même utilisateur une seconde fois et observe le "Rolling back JPA transaction".

### Exercice 2 : provoquer une `LazyInitializationException`

En local, sans commiter : fais renvoyer l'entité `Task` par une méthode de service (au lieu du DTO), et appelle
`TaskMapper.toTaskResponseDto` dans le contrôleur. Appelle `GET /api/tasks/{id}` sur une tâche **assignée**.

**À observer** : une erreur 500 et une `LazyInitializationException` dans les logs. Annule ta modification. Bonus :
remets temporairement `open-in-view=true` et constate que l'erreur disparaît (et pourquoi ce n'est pas une vraie
solution).

### Exercice 3 : le piège de l'auto-invocation, sur papier

Imagine dans `TaskService` :

```java
public void archiverTout() {          // readOnly = true (hérité de la classe)
	for (Long id : ids) {
		this.archiver(id);             // archiver est annotée @Transactional
	}
}
```

Question : dans quelle transaction s'exécute `archiver`, et ses modifications sont-elles écrites ?

<details><summary>Réponse</summary>

`this.archiver(id)` ne passe pas par le proxy : son `@Transactional` est ignoré. `archiver` s'exécute dans la
transaction **readOnly** de `archiverTout`. En lecture seule, Hibernate ne fait pas de flush : les modifications
risquent de ne jamais être écrites, sans aucune erreur. Solution : annoter `archiverTout` en `@Transactional`, ou
déplacer `archiver` dans un autre bean.
</details>

### Exercice 4 : repérer les transactions du projet

Liste toutes les méthodes publiques de `TaskService`, `UserService` et `AuthService`, et note pour chacune : lecture
seule ou écriture ? Ouvre-t-elle sa transaction ou en rejoint-elle une ? Vérifie que chaque méthode qui modifie des
données est bien en écriture.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `@Transactional` semble sans effet | Méthode appelée via `this` (auto-invocation), ou méthode `private` | Appeler depuis un autre bean ; annoter la méthode publique d'entrée |
| Des modifications ne sont pas écrites, sans erreur | Exécution dans une transaction `readOnly` | Annoter la méthode d'écriture avec `@Transactional` |
| Données à moitié écrites après une erreur | Exception vérifiée (commit par défaut), ou exception attrapée et avalée | Exceptions métier en `RuntimeException` ; ne pas avaler ; `rollbackFor` si besoin |
| `UnexpectedRollbackException` au commit | Une méthode interne a levé une exception (transaction marquée à annuler), la méthode externe l'a attrapée et a continué | Laisser l'exception remonter, ou isoler l'opération dans `REQUIRES_NEW` si c'est vraiment voulu |
| `LazyInitializationException: ... - no session` | Relation LAZY lue hors transaction (`open-in-view=false`) | Construire le DTO dans le service ; `@EntityGraph` si la relation est toujours nécessaire |
| Doublons malgré la vérification `existsBy...` | Check-then-act entre deux requêtes simultanées | Contrainte unique en base + traduction de `DataIntegrityViolationException` |
| Les tests se polluent entre eux | `@Transactional` sur un test `RANDOM_PORT` n'annule rien | Nettoyer la base dans un `@AfterEach` |
| Connexions épuisées, requêtes qui attendent | Transactions trop longues (appel HTTP externe, traitement lourd dans la méthode) | Garder les transactions courtes ; sortir les appels externes de la méthode transactionnelle |

---

## 7. Pour aller plus loin

- **`@Version` (verrouillage optimiste)** : protège contre la perte de mise à jour (deux personnes modifient la même
  tâche ; la seconde reçoit une erreur au lieu d'écraser). Complément naturel des transactions pour les modifications
  concurrentes.
- **Verrous pessimistes** (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) : bloquer une ligne le temps de la transaction.
  Plus lourd, à réserver aux cas critiques.
- **Niveaux d'isolation** : `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE` et les anomalies qu'ils évitent
  (lecture sale, lecture non répétable, lecture fantôme).
- **`TransactionTemplate`** : gérer une transaction par programmation, quand l'annotation ne convient pas.
- **Événements après commit** (`@TransactionalEventListener`) : envoyer un email seulement une fois la transaction
  réellement validée.
- **Audit** (`@CreatedBy`, `@LastModifiedBy`) : voir [08. Persistance](08-persistance.md).

Documentation officielle : les liens de la section 4 (chaque sous-section se termine par ses sources), en
particulier :
- [Spring Framework : Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
  et [Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring Data JPA : Transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)
- [Spring Boot : SQL Databases](https://docs.spring.io/spring-boot/reference/data/sql.html) (propriété
  `spring.jpa.open-in-view`)
- *PostgreSQL Documentation*, chapitre "Concurrency Control" > "Transaction Isolation"

---

## 8. Quiz

**1. Que fait Spring si une `TaskNotFoundException` sort de `updateTask` ? Et si c'était une `IOException` ?**

<details><summary>Réponse</summary>

`TaskNotFoundException` est une `RuntimeException` : rollback. Une `IOException` est une exception vérifiée : par
défaut, Spring ferait un **commit**, sauf configuration `rollbackFor`.
</details>

**2. Combien de transactions pendant `AuthService.register`, et pourquoi ?**

<details><summary>Réponse</summary>

Une seule. `register` l'ouvre ; `UserService.createUser`, appelée via son proxy, la rejoint grâce à la propagation
par défaut `REQUIRED`.
</details>

**3. Pourquoi `TaskService` est-il annoté `@Transactional(readOnly = true)` au niveau de la classe ?**

<details><summary>Réponse</summary>

Pour que toute méthode soit transactionnelle et en lecture seule par défaut (optimisation, intention claire), les
méthodes d'écriture étant explicitement annotées `@Transactional`, ce qui remplace l'annotation de la classe.
</details>

**4. Avec `open-in-view=false`, pourquoi faut-il construire le DTO dans le service et non dans le contrôleur ?**

<details><summary>Réponse</summary>

La session Hibernate se ferme à la fin de la méthode transactionnelle du service. Dans le contrôleur, accéder à une
relation LAZY non chargée (l'assigné d'une tâche) lève une `LazyInitializationException`.
</details>

**5. Une transaction suffit-elle à empêcher deux inscriptions simultanées avec le même email ?**

<details><summary>Réponse</summary>

Non. En `READ COMMITTED`, les deux transactions peuvent passer la vérification `existsByEmail` avant que l'autre ait
validé. C'est la contrainte `UNIQUE` en base qui fait échouer le second `INSERT` ; le service traduit alors l'erreur
en 409.
</details>

**6. Pourquoi les tests d'intégration du projet ne sont-ils pas annotés `@Transactional` ?**

<details><summary>Réponse</summary>

Avec `RANDOM_PORT`, la requête HTTP est traitée par le thread du serveur, dans sa propre transaction validée
normalement. Une transaction côté test n'annulerait rien. La base est donc vidée explicitement après chaque test.
</details>
