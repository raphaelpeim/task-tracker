# 09. Transactions

**Ce que tu vas comprendre**
- Ce qu'est une transaction et pourquoi "tout ou rien" protège tes données.
- Comment `@Transactional` fonctionne réellement (un proxy autour de ton service), et quand il est ignoré.
- Ce que changent `readOnly`, la propagation et les règles de rollback.
- Pourquoi le projet désactive l'Open Session In View, et ce que ça impose.
- Pourquoi une transaction ne suffit pas contre deux requêtes simultanées.

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
driver, la connexion peut aussi être passée en mode lecture seule. Ne compte pas dessus comme mécanisme de
sécurité ; c'est surtout une optimisation et une documentation de l'intention.

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
org.hibernate.LazyInitializationException: could not initialize proxy [...AppUser#1] - no Session
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

## 4. À toi de jouer

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

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `@Transactional` semble sans effet | Méthode appelée via `this` (auto-invocation), ou méthode `private` | Appeler depuis un autre bean ; annoter la méthode publique d'entrée |
| Des modifications ne sont pas écrites, sans erreur | Exécution dans une transaction `readOnly` | Annoter la méthode d'écriture avec `@Transactional` |
| Données à moitié écrites après une erreur | Exception vérifiée (commit par défaut), ou exception attrapée et avalée | Exceptions métier en `RuntimeException` ; ne pas avaler ; `rollbackFor` si besoin |
| `UnexpectedRollbackException` au commit | Une méthode interne a levé une exception (transaction marquée à annuler), la méthode externe l'a attrapée et a continué | Laisser l'exception remonter, ou isoler l'opération dans `REQUIRES_NEW` si c'est vraiment voulu |
| `LazyInitializationException: no Session` | Relation LAZY lue hors transaction (`open-in-view=false`) | Construire le DTO dans le service ; `@EntityGraph` si la relation est toujours nécessaire |
| Doublons malgré la vérification `existsBy...` | Check-then-act entre deux requêtes simultanées | Contrainte unique en base + traduction de `DataIntegrityViolationException` |
| Les tests se polluent entre eux | `@Transactional` sur un test `RANDOM_PORT` n'annule rien | Nettoyer la base dans un `@AfterEach` |
| Connexions épuisées, requêtes qui attendent | Transactions trop longues (appel HTTP externe, traitement lourd dans la méthode) | Garder les transactions courtes ; sortir les appels externes de la méthode transactionnelle |

---

## 6. Pour aller plus loin

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

Documentation officielle :
- *Spring Framework Reference*, section "Data Access" > "Transaction Management" (notamment "Declarative
  Transaction Management" et "Transaction Propagation")
- *Spring Boot Reference*, section "Data" > "SQL Databases" (propriété `spring.jpa.open-in-view`)
- *PostgreSQL Documentation*, chapitre "Concurrency Control" > "Transaction Isolation"

---

## 7. Quiz

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
