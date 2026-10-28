# 02. Beans et injection de dépendances

**Ce que tu vas comprendre**
- Pourquoi tu n'écris jamais `new TaskService(...)` dans ce projet
- Ce qu'est un bean, le contexte applicatif, et comment Spring les trouve
- Comment fonctionne l'injection par constructeur, et pourquoi c'est la bonne pratique
- Ce que fait réellement `@SpringBootApplication` et l'auto-configuration
- Ce qu'est un proxy, et le piège qui en découle

**Prérequis** : [01. Maven et le pom.xml](01-maven-pom.md) (pour la notion de starter).

---

## 1. Le problème que ça résout

Regarde ce dont `TaskService` a besoin : un `UserService` et un `TaskRepository`. `UserService` a lui-même besoin
d'un `UserRepository` et d'un `PasswordEncoder`. Sans framework, quelque part dans `main`, il faudrait écrire :

```java
DataSource dataSource = new HikariDataSource(config);
EntityManagerFactory emf = ...;                       // des dizaines de lignes
UserRepository userRepository = new UserRepositoryImpl(emf);
PasswordEncoder encoder = new BCryptPasswordEncoder();
UserService userService = new UserService(userRepository, encoder);
TaskRepository taskRepository = new TaskRepositoryImpl(emf);
TaskService taskService = new TaskService(userService, taskRepository);
TaskController taskController = new TaskController(taskService);
// ... et enregistrer chaque contrôleur auprès du serveur web
```

Problèmes :

- il faut connaître l'ordre de création de **tout** le graphe ;
- ajouter une dépendance à une classe oblige à modifier cet assemblage ;
- dans un test, remplacer une pièce par une fausse est pénible ;
- les objets techniques (transactions, sécurité) seraient à câbler à la main partout.

**L'inversion de contrôle (IoC)** renverse la logique : chaque classe **déclare** ce dont elle a besoin, et un
conteneur (Spring) crée les objets dans le bon ordre et les relie. Ce n'est plus ton code qui contrôle
l'assemblage, c'est le framework.

Analogie : au lieu d'aller chercher toi-même chaque ingrédient, tu écris sur ta fiche de poste "j'ai besoin d'un
four et d'un batteur", et le chef d'atelier (Spring) te les apporte avant que tu commences.

---

## 2. Les concepts

### 2.1 Bean et contexte applicatif

- Un **bean** est un objet créé et géré par Spring.
- Le **contexte applicatif** (`ApplicationContext`) est le conteneur qui contient tous les beans. Il est démarré
  par `SpringApplication.run(...)`.

Par défaut, un bean est un **singleton** : une seule instance pour toute l'application, partagée par toutes les
requêtes HTTP, qui arrivent en parallèle sur plusieurs threads. Règle qui en découle : **un bean ne garde pas
d'état propre à une requête** dans ses champs. Ses champs sont ses dépendances, marquées `final`.

### 2.2 Déclarer un bean : deux manières

**a) Annoter la classe (stéréotypes)**

| Annotation | Rôle indiqué | Particularité |
|---|---|---|
| `@Component` | bean générique | la base de toutes les autres |
| `@Service` | logique métier | purement indicatif |
| `@Repository` | accès aux données | Spring traduit certaines exceptions techniques en `DataAccessException` |
| `@RestController` | point d'entrée HTTP | les retours sont écrits en JSON |
| `@Configuration` | classe qui déclare des beans | ses méthodes `@Bean` sont traitées spécialement |

**b) Une méthode `@Bean` dans une classe `@Configuration`**

Indispensable quand la classe ne t'appartient pas (tu ne peux pas annoter `BCryptPasswordEncoder`, elle vient de
Spring Security) ou quand sa création demande de la logique :

```java
@Configuration
public class MaConfig {
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
```

### 2.3 Trouver les beans : le component scan

Spring parcourt le package de la classe annotée `@SpringBootApplication` **et tous ses sous-packages**, et
enregistre chaque classe annotée d'un stéréotype. Une classe annotée mais placée hors de cette arborescence est
ignorée.

### 2.4 L'injection de dépendances

Une fois le bean trouvé, Spring regarde son constructeur et fournit un bean pour chaque paramètre :

```java
@Service
public class TaskService {
	private final TaskRepository repository;

	public TaskService(TaskRepository repository) {   // Spring passe le bean TaskRepository
		this.repository = repository;
	}
}
```

Trois styles existent :

| Style | Exemple | Verdict |
|---|---|---|
| **Constructeur** | ci-dessus | Recommandé : champs `final`, dépendances visibles, objet utilisable sans Spring (dans un test : `new TaskService(fauxRepo)`) |
| Champ | `@Autowired private TaskRepository repository;` | À éviter : dépendances cachées, impossible à mettre `final`, difficile à tester |
| Setter | `@Autowired void setRepository(...)` | Rare, pour une dépendance optionnelle |

Avec un **seul** constructeur, l'annotation `@Autowired` est inutile.

### 2.5 Injecter une interface

On injecte de préférence un **contrat** (interface) plutôt qu'une implémentation. Spring cherche un bean
compatible avec le type demandé. Si **aucun** bean ne correspond : l'application ne démarre pas. S'il y en a
**plusieurs** : elle ne démarre pas non plus, sauf à désigner le bon (`@Primary` sur l'un d'eux, ou `@Qualifier`
au point d'injection).

### 2.6 `@SpringBootApplication` et l'auto-configuration

`@SpringBootApplication` combine trois annotations :

- `@Configuration` : la classe peut déclarer des beans ;
- `@ComponentScan` : active le scan du paragraphe 2.3 ;
- `@EnableAutoConfiguration` : active **l'auto-configuration**.

L'auto-configuration est le cœur de Spring Boot. Au démarrage, Boot évalue des centaines de classes de
configuration conditionnelles, du type :

> "si la classe `DataSource` est sur le classpath **et** qu'une URL de base est configurée **et** que tu n'as pas
> déjà déclaré de `DataSource`, alors je crée un pool de connexions."

C'est pour ça qu'ajouter un starter suffit à "activer" une fonctionnalité, et que déclarer **ton propre** bean du
même type remplace en général celui de Boot (la condition "tu n'as pas déjà déclaré" n'est plus remplie).

### 2.7 Les proxies

Pour certaines annotations (`@Transactional`, `@PreAuthorize`, `@Cacheable`...), Spring n'injecte pas ton objet
directement mais un **proxy** : un objet intermédiaire qui ajoute un comportement avant et après l'appel réel.

```
TaskController ──appelle──▶ [proxy de TaskService] ──▶ TaskService.createTask()
                              │ ouvre la transaction
                              │ ... appel réel ...
                              │ valide ou annule
```

Conséquence importante, le **piège de l'auto-invocation** : si une méthode de `TaskService` appelle une autre
méthode de `TaskService` via `this`, l'appel ne passe pas par le proxy. Les annotations de la méthode appelée
sont alors ignorées. Détails dans [09. Transactions](09-transactions.md).

### 2.8 Le cycle de vie, en bref

Démarrage : scan → création des beans dans l'ordre des dépendances → injection → éventuelles méthodes
d'initialisation → application prête. Arrêt : destruction propre (fermeture du pool de connexions par exemple).

Si deux beans dépendent l'un de l'autre par constructeur (**dépendance circulaire**), aucun ordre de création
n'est possible : Spring refuse de démarrer. C'est presque toujours le signe d'un mauvais découpage.

---

## 3. Dans le projet

### Le point de départ

`server/src/main/java/com/personal/task_tracker/TaskTrackerApplication.java:6-11`

```java
@SpringBootApplication                       // scan de com.personal.task_tracker et de ses sous-packages
public class TaskTrackerApplication {
	public static void main(String[] args) {
		SpringApplication.run(TaskTrackerApplication.class, args);   // démarre le contexte et Tomcat
	}
}
```

Tous les packages (`task`, `user`, `auth`, `commons`) sont sous `com.personal.task_tracker` : ils sont scannés.

### Injection par constructeur

`server/src/main/java/com/personal/task_tracker/task/service/TaskService.java:19-28`

```java
@Service                                     // bean détecté par le scan
@Transactional(readOnly = true)              // Spring injectera un proxy de cette classe
public class TaskService {
	private final UserService userService;   // final : fixé une fois pour toutes
	private final TaskRepository repository;

	public TaskService(UserService userService, TaskRepository repository) {   // pas de @Autowired : constructeur unique
		this.userService = userService;
		this.repository = repository;
	}
```

### Injection d'une interface

`server/src/main/java/com/personal/task_tracker/user/service/UserService.java:28-34`

```java
private final UserRepository repository;
private final PasswordEncoder passwordEncoder;        // interface : UserService ignore qu'il s'agit de BCrypt

public UserService(UserRepository repository, PasswordEncoder passwordEncoder) { ... }
```

`UserRepository` est une interface que tu n'as **jamais implémentée** : Spring Data génère l'implémentation et
l'enregistre comme bean (voir [08. Persistance](08-persistance.md)).

### Une méthode `@Bean` pour une classe tierce

`server/src/main/java/com/personal/task_tracker/commons/config/SecurityConfig.java:12-28`

```java
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandler problemHandler) {
		// les PARAMÈTRES d'une méthode @Bean sont injectés aussi :
		// HttpSecurity est fourni par Spring Security, SecurityProblemHandler est ton @Component
		...
	}

	@Bean
	public PasswordEncoder encoder() {
		return new BCryptPasswordEncoder();   // le seul "new" d'un service dans le projet, et il est dans une config
	}
}
```

C'est ce bean `PasswordEncoder` qui est injecté dans `UserService`.

### Un `@Component` générique

`server/src/main/java/com/personal/task_tracker/commons/exception/SecurityProblemHandler.java:23-24` : ni service,
ni repository, ni contrôleur, d'où `@Component`.

### Le graphe des beans du projet (simplifié)

```
TaskController ──▶ TaskService ──▶ TaskRepository (généré par Spring Data)
                        │
                        ▼
UserController ──▶ UserService ──▶ UserRepository (généré)
                        ▲     └──▶ PasswordEncoder (SecurityConfig.encoder)
AuthController ──▶ AuthService

SecurityFilterChain (SecurityConfig.filterChain) ──▶ SecurityProblemHandler
```

Les flèches vont toujours dans le même sens : pas de cycle.

### Remplacer un bean dans les tests

`server/src/test/java/com/personal/task_tracker/IntegrationTest.java:30`

```java
@MockitoSpyBean(types = UserRepository.class)
```

Comme les classes reçoivent leurs dépendances au lieu de les créer, le test peut demander à Spring d'envelopper le
vrai `UserRepository` dans un espion Mockito. `UserService` reçoit l'espion sans le savoir. C'est l'un des grands
bénéfices de l'injection.

---

## 4. À toi de jouer

**Exercice 1 : retirer un stéréotype.**
Retire temporairement `@Service` de `TaskService` et lance l'application.
*À observer* : le démarrage échoue avec un message du type "Parameter 0 of constructor in TaskController required
a bean of type TaskService that could not be found". Remets l'annotation.

**Exercice 2 : deux candidats pour une interface.**
Dans `SecurityConfig`, ajoute temporairement une deuxième méthode `@Bean` qui renvoie un autre `PasswordEncoder`
(par exemple `new BCryptPasswordEncoder(12)`).
*À observer* : échec au démarrage, Spring trouve deux beans pour un seul point d'injection. Essaie d'ajouter
`@Primary` sur l'un des deux : l'application redémarre. Retire ensuite le bean ajouté.

**Exercice 3 : créer un cycle.**
Fais injecter `TaskService` dans le constructeur de `UserService`.
*À observer* : Spring refuse de démarrer et affiche le cycle (`taskService` → `userService` → `taskService`).
Annule la modification.

**Exercice 4 : lire le rapport d'auto-configuration.**
Lance l'application avec `--debug` :
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--debug`
*À observer* : la section "CONDITIONS EVALUATION REPORT" liste les configurations appliquées ("Positive matches")
et écartées ("Negative matches"), avec la raison. Cherche par exemple ce qui concerne `DataSource` et Flyway.

---

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `required a bean of type X that could not be found` | Classe non annotée, hors du package scanné, ou starter manquant | Vérifier l'annotation, le package, le pom |
| `expected single matching bean but found 2` | Deux beans du même type | `@Primary`, `@Qualifier`, ou supprimer le doublon |
| `The dependencies of some of the beans form a cycle` | Dépendance circulaire | Redécouper : extraire la partie commune dans un troisième bean |
| `NullPointerException` sur un champ `@Autowired` | Objet créé avec `new` au lieu d'être injecté : Spring ne le connaît pas | Ne jamais faire `new` d'un bean, le recevoir par constructeur |
| Données d'une requête visibles dans une autre | Champ mutable dans un singleton | Pas d'état dans les beans, utiliser des variables locales |
| `@Transactional` sans effet | Appel interne via `this` qui contourne le proxy, ou méthode `private` | Appeler depuis un autre bean, méthode publique |
| Ma configuration est ignorée | Mon bean a un autre type que celui attendu par Boot, ou n'est pas scanné | Lancer avec `--debug`, lire le rapport des conditions |

---

## 6. Pour aller plus loin

- **Scopes autres que singleton** : `prototype` (nouvelle instance à chaque injection), `request`, `session`.
- **`@Profile`** : un bean qui n'existe que dans un profil donné (voir [03. Configuration](03-configuration.md)).
- **`@Conditional...`** : écrire tes propres conditions, comme le fait l'auto-configuration.
- **Événements Spring** ⏳ (`ApplicationEventPublisher`) : découpler deux beans sans qu'ils se connaissent.
- **`@Async` / `@Scheduled`** ⏳ : reposent aussi sur des proxies.
- **AOP** : écrire ses propres aspects (journalisation, mesure de temps) avec `@Aspect`.
- **Docs officielles** : Spring Framework Reference, chapitre "The IoC Container" ; Spring Boot Reference,
  sections "Spring Beans and Dependency Injection" et "Auto-configuration".

---

## 7. Quiz

**1. Pourquoi `TaskService` n'a-t-il pas besoin de `@Autowired` sur son constructeur ?**
<details><summary>Réponse</summary>
Il n'a qu'un seul constructeur : Spring l'utilise automatiquement pour l'injection.
</details>

**2. Pourquoi `BCryptPasswordEncoder` est-il déclaré par une méthode `@Bean` plutôt qu'annoté `@Component` ?**
<details><summary>Réponse</summary>
C'est une classe de Spring Security, tu ne peux pas modifier son code pour l'annoter. Une méthode
<code>@Bean</code> dans une classe <code>@Configuration</code> permet d'en faire un bean.
</details>

**3. Les beans sont des singletons partagés par toutes les requêtes. Quelle règle de code en découle ?**
<details><summary>Réponse</summary>
Ne pas stocker d'état lié à une requête dans les champs d'un bean. Les champs sont les dépendances, en
<code>final</code> ; les données de la requête restent dans des variables locales et des paramètres.
</details>

**4. Que se passe-t-il si tu crées une classe `@Service` dans le package `com.personal.autre` ?**
<details><summary>Réponse</summary>
Elle n'est pas sous <code>com.personal.task_tracker</code>, donc pas scannée : elle n'existe pas pour Spring, et
toute injection de cette classe fera échouer le démarrage.
</details>

**5. Comment un test arrive-t-il à remplacer `UserRepository` par un espion sans toucher à `UserService` ?**
<details><summary>Réponse</summary>
<code>UserService</code> reçoit son repository par injection, il ne le crée pas. Le test demande à Spring de
fournir un espion (<code>@MockitoSpyBean</code>) à la place, et c'est lui qui est injecté.
</details>

**6. Qu'est-ce qui permet à `@Transactional` de fonctionner, et quelle limite en découle ?**
<details><summary>Réponse</summary>
Spring injecte un proxy qui ouvre et ferme la transaction autour de l'appel. Un appel interne via
<code>this</code> ne passe pas par le proxy, donc l'annotation de la méthode appelée est ignorée.
</details>
