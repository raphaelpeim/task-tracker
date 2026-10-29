# 02. Beans et injection de dépendances

**Ce que tu vas comprendre**
- Pourquoi tu n'écris jamais `new TaskService(...)` dans ce projet
- Ce qu'est un bean, le contexte applicatif, et comment Spring les trouve
- Comment fonctionne l'injection par constructeur, et pourquoi c'est la bonne pratique
- Ce que fait réellement `@SpringBootApplication` et l'auto-configuration
- Ce qu'est un proxy, et le piège qui en découle
- Comment observer tout cela dans le projet : logs, classes générées, point d'arrêt, et un mini-conteneur à écrire

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

L'auto-configuration est le cœur de Spring Boot. Au démarrage, Boot évalue plusieurs dizaines de classes
de configuration conditionnelles (70 dans ce projet, voir 4.6), du type :

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

`server/src/main/java/com/personal/task_tracker/TaskTrackerApplication.java:7-14`

```java
@SpringBootApplication                       // scan de com.personal.task_tracker et de ses sous-packages
@ConfigurationPropertiesScan                 // enregistre les records @ConfigurationProperties (voir 03. Configuration)
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

## 4. Sous le capot

Les sections précédentes disent **ce que** fait Spring. Celle-ci montre **comment** il le fait, et surtout comment
le **voir** dans le projet. Rien de ce qui suit n'est caché : tout s'observe avec un log, un point d'arrêt ou un
fichier dans un `.jar`. Chaque sous-section suit le même plan : le mécanisme réel, comment l'observer, la source.

Pour activer un log sans toucher aux fichiers de configuration, passe-le en argument, comme dans l'exercice 4 :
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--logging.level.<package>=DEBUG`

### 4.1 Le démarrage : d'abord les recettes, ensuite les objets

**Le mécanisme.** `SpringApplication.run(...)` crée un `ApplicationContext` puis appelle sa méthode `refresh()`,
qui travaille en deux temps :

1. **Les recettes.** Le scan et l'auto-configuration produisent des `BeanDefinition` : pour chaque bean, son nom,
   sa classe, son scope. Aucun objet n'est encore créé. Le scanner lit directement le bytecode des fichiers
   `.class` pour y repérer les annotations, sans même charger les classes.
2. **La fabrication.** Pour chaque singleton, Spring appelle `getBean(nom)`. Si le bean existe déjà, il est
   renvoyé. Sinon Spring regarde les paramètres du constructeur, appelle `getBean` pour chacun (c'est récursif),
   instancie l'objet, le fait passer par les `BeanPostProcessor` (voir 4.4), puis le range dans le registre des
   singletons.

Le nom d'un bean est le nom de sa classe avec une minuscule (`TaskService` → `taskService`), ou le nom de la
méthode pour un `@Bean` (`SecurityConfig.encoder()` → `encoder`).

**L'observer.** Active `logging.level.org.springframework.beans.factory.support.DefaultListableBeanFactory=DEBUG`.
Extrait réel du projet, filtré sur quelques beans :

```
Creating shared instance of singleton bean 'userService'
Creating shared instance of singleton bean 'userRepository'
Creating shared instance of singleton bean 'encoder'
Autowiring by type from bean name 'userService' via constructor to bean named 'userRepository'
Autowiring by type from bean name 'userService' via constructor to bean named 'encoder'
Creating shared instance of singleton bean 'taskController'
Creating shared instance of singleton bean 'taskService'
Creating shared instance of singleton bean 'taskRepository'
Autowiring by type from bean name 'taskService' via constructor to bean named 'userService'
Autowiring by type from bean name 'taskService' via constructor to bean named 'taskRepository'
Autowiring by type from bean name 'taskController' via constructor to bean named 'taskService'
```

Lis-le comme une pile d'appels :

- `userService` est créé en premier, parce que le package `auth` est scanné avant `task`, et qu'`AuthService` en a
  besoin ;
- Spring commence `taskController`, découvre qu'il lui faut `taskService`, le crée **à ce moment-là**, qui
  lui-même réclame `taskRepository`. Le contrôleur n'est terminé qu'en dernier ;
- `userService` n'est pas recréé pour `taskService` : il est déjà dans le registre, c'est le même objet.

**La source.** [The IoC Container](https://docs.spring.io/spring-framework/reference/core/beans.html),
[Classpath Scanning](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html).

### 4.2 L'injection : comment Spring choisit le bon bean

**Le mécanisme.** Pour chaque paramètre de constructeur, Spring :

1. liste tous les beans compatibles avec le **type** demandé ;
2. s'il y en a un seul, l'injecte ;
3. s'il y en a plusieurs, essaie de départager, dans l'ordre : `@Qualifier` au point d'injection, puis `@Primary`,
   puis **le nom du paramètre comparé au nom des beans** ;
4. s'il n'en reste pas exactement un, refuse de démarrer.

L'étape "nom du paramètre" n'est possible que parce que le `spring-boot-starter-parent` compile avec l'option
`-parameters`, qui conserve le nom des paramètres dans le bytecode.

**L'observer.** Dans le log de 4.1, la ligne `via constructor to bean named 'encoder'` montre le résultat de la
résolution. Vérifié sur le projet : si tu ajoutes dans `SecurityConfig` un second `PasswordEncoder` dont la
méthode s'appelle `passwordEncoder`, l'application **démarre sans erreur**, et le log devient
`... to bean named 'passwordEncoder'`. Le paramètre de `UserService` porte ce nom, Spring a départagé seul.
Avec un autre nom de méthode, tu obtiens bien l'erreur de l'exercice 2. Retiens le piège : renommer un paramètre
peut changer silencieusement le bean injecté. Quand il y a plusieurs candidats, sois explicite avec `@Qualifier`.

**La source.** [Dependencies and Configuration](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html).

### 4.3 Les classes `@Configuration` sont des sous-classes générées

**Le mécanisme.** Spring ne garde pas ta classe `SecurityConfig` telle quelle : il en génère une sous-classe au
démarrage, avec la bibliothèque CGLIB intégrée à Spring. Cette sous-classe redéfinit chaque méthode `@Bean` :
si une méthode `@Bean` en appelle une autre, l'appel est intercepté et renvoie le singleton existant au lieu d'en
fabriquer un second. C'est ce qui rend les méthodes `@Bean` "spéciales" (tableau de 2.2).

**L'observer.** Vérifié dans un test du projet, `getClass().getName()` sur le bean renvoie :

```
com.personal.task_tracker.commons.config.SecurityConfig$$SpringCGLIB$$0
```

Le `$$SpringCGLIB$$0` signale une classe qui n'existe dans aucun fichier source : elle a été écrite en mémoire au
démarrage. `@Configuration(proxyBeanMethods = false)` désactive ce mécanisme quand les méthodes `@Bean` ne
s'appellent pas entre elles.

**La source.** [Using the @Configuration annotation](https://docs.spring.io/spring-framework/reference/core/beans/java/configuration-annotation.html).

### 4.4 Le proxy de `@Transactional`, concrètement

**Le mécanisme.** Après avoir créé chaque bean, Spring le passe à une série de `BeanPostProcessor` : des objets
qui ont le droit d'inspecter un bean, et même de le **remplacer**. L'un d'eux repère les classes portant
`@Transactional`. Pour `TaskService`, il génère une sous-classe CGLIB dont chaque méthode publique fait :

```java
// pseudo-code de TaskService$$SpringCGLIB$$0.getAllTasks()
ouvrir la transaction;
try {
	resultat = vraiTaskService.getAllTasks();   // l'objet que tu as écrit
	valider la transaction;
	return resultat;
} catch (RuntimeException e) {
	annuler la transaction;
	throw e;
}
```

C'est **ce proxy** qui est rangé dans le registre et injecté dans `TaskController`. Les limites de 2.7 en
découlent mécaniquement :

- un proxy est une **sous-classe** : une méthode `private` ou `final` ne peut pas être redéfinie, donc pas
  interceptée ;
- dans le vrai `TaskService`, `this` désigne l'objet réel, pas le proxy : un appel `this.autreMethode()` ne
  repasse pas par la sous-classe.

**L'observer.** Trois façons, de la plus simple à la plus complète :

1. `getClass().getName()` sur le bean injecté donne `com.personal.task_tracker.task.service.TaskService$$SpringCGLIB$$0`.
2. Les logs, avec `logging.level.org.springframework.transaction.interceptor=TRACE` et
   `logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG`. Extrait réel pour `getAllTasks()` :
   ```
   Creating new transaction with name [com.personal.task_tracker.task.service.TaskService.getAllTasks]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,readOnly
   Getting transaction for [com.personal.task_tracker.task.service.TaskService.getAllTasks]
   Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll]
   Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll]
   ```
   Le `readOnly` vient du `@Transactional(readOnly = true)` posé sur la classe. Le repository a lui aussi ses
   propres `@Transactional` : il rejoint la transaction ouverte par le service au lieu d'en créer une.
3. Un point d'arrêt dans `TaskService.getAllTasks()`, puis un `GET /api/tasks`. Dans la pile d'appels, entre
   `TaskController.getAllTasks` et ta méthode, tu verras s'intercaler `TaskService$$SpringCGLIB$$0`,
   `CglibAopProxy`, `TransactionInterceptor` et `TransactionAspectSupport.invokeWithinTransaction`. C'est le
   pseudo-code ci-dessus, en vrai.

**La source.** [Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html),
[Understanding the Spring Framework's Declarative Transaction Implementation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html),
[Customizing Beans by Using a BeanPostProcessor](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html).

### 4.5 Les repositories que tu n'as pas implémentés

**Le mécanisme.** Pour chaque interface qui étend `JpaRepository`, Spring Data enregistre un bean dont l'objet est
un **proxy dynamique JDK** : une classe générée qui implémente `UserRepository`. Quand tu appelles `findAll()` ou
`save()`, le proxy délègue à `SimpleJpaRepository`, une vraie classe de Spring Data (tu l'as vue passer dans le
log de 4.4). Pour `existsByEmail`, Spring Data a analysé le **nom** de la méthode au démarrage et en a tiré la
requête correspondante.

**L'observer.** En lançant l'application, `getClass().getName()` sur `userRepository` donne un nom de la forme
`jdk.proxy2.$Proxy` suivi d'un numéro. Dans les tests, le nom est différent
(`UserRepository$MockitoMock$...$$SpringCGLIB$$0`) : le `@MockitoSpyBean` d'`IntegrationTest` enveloppe le
repository dans un espion, lui-même proxifié. Détails dans [08. Persistance](08-persistance.md).

### 4.6 L'auto-configuration, fichier par fichier

**Le mécanisme.** Un starter n'apporte pas de magie, il apporte des `.jar`. Depuis Spring Boot 4, chaque domaine a
son module : le starter JPA tire par exemple `spring-boot-jdbc`. Chacun de ces modules contient un simple fichier
texte, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, qui liste ses classes
d'auto-configuration. Celui de `spring-boot-jdbc` commence ainsi :

```
org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration
org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
...
```

Au démarrage, Boot lit tous ces fichiers présents sur le classpath, puis évalue les conditions
(`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`...) de chaque classe listée. Celles
qui passent deviennent des classes `@Configuration` comme la tienne, avec leurs méthodes `@Bean`.

**L'observer.**

- Compter les candidates du projet (70 au moment de l'écriture de cette fiche) :
  ```bash
  cd server
  ./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tr ':' '\n' \
    | while read j; do unzip -p "$j" META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 2>/dev/null; done \
    | grep -v '^#' | grep -c .
  ```
- Ouvrir `DataSourceAutoConfiguration` dans l'IDE (recherche de classe, puis télécharger les sources) : c'est du
  Java ordinaire, des méthodes `@Bean` protégées par des conditions. Tu pourrais l'écrire.
- Croiser avec le rapport de l'exercice 4 (`--debug`), qui dit pour chacune pourquoi elle a été retenue ou écartée.

**La source.** [Auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html),
[Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html).

### 4.7 Le refaire toi-même : un conteneur en 40 lignes

Le meilleur moyen de démystifier l'injection est d'en écrire une. Crée ce fichier **hors du projet** (par exemple
dans un dossier `~/mini-spring/`) et lance-le avec `java MiniSpring.java`, sans Maven ni compilation séparée :

```java
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public class MiniSpring {

	// Trois classes ordinaires, sans aucune annotation
	static class Repository {
	}

	static class Service {
		final Repository repository;

		Service(Repository repository) {
			this.repository = repository;
		}
	}

	static class Controller {
		final Service service;

		Controller(Service service) {
			this.service = service;
		}
	}

	// Le "conteneur" : un type -> son unique instance
	static final Map<Class<?>, Object> singletons = new HashMap<>();

	static Object getBean(Class<?> type) throws Exception {
		Object existing = singletons.get(type);
		if (existing != null) {
			return existing;                                                    // singleton : créé une seule fois
		}
		Constructor<?> constructor = type.getDeclaredConstructors()[0];        // le constructeur unique
		Object[] args = new Object[constructor.getParameterCount()];
		for (int i = 0; i < args.length; i++) {
			args[i] = getBean(constructor.getParameterTypes()[i]);              // résolution récursive, par type
		}
		Object bean = constructor.newInstance(args);
		singletons.put(type, bean);
		return bean;
	}

	public static void main(String[] args) throws Exception {
		Controller controller = (Controller) getBean(Controller.class);
		System.out.println(controller.service.repository);
		System.out.println(getBean(Service.class) == controller.service);      // true : même instance
	}
}
```

Compare avec 4.1 : `getBean` récursif, registre des singletons, résolution par le type des paramètres du
constructeur. C'est le cœur de Spring. Ce qui lui manque, et que tu as vu dans cette section : le scan des
annotations, le choix entre plusieurs candidats (4.2), les `BeanPostProcessor` et les proxies (4.4).

**Défis :**

1. Fais dépendre `Repository` de `Controller`. Que se passe-t-il, et pourquoi ?
2. Corrige `getBean` pour afficher un message clair à la place, comme le fait Spring.

<details><summary>Réponses</summary>

1. Une `StackOverflowError` : `getBean` s'appelle à l'infini, Controller → Service → Repository → Controller...
2. Garde un `Set<Class<?>>` des types "en cours de création". Ajoute le type au début de `getBean`, retire-le une
   fois l'objet créé. Si le type demandé est déjà dans l'ensemble, c'est un cycle : lève une exception qui
   l'affiche. Spring fait exactement cela (`BeanCurrentlyInCreationException`), d'où le message de l'exercice 3.
</details>

---

## 5. À toi de jouer

**Exercice 1 : retirer un stéréotype.**
Retire temporairement `@Service` de `TaskService` et lance l'application.
*À observer* : le démarrage échoue avec un message du type "Parameter 0 of constructor in TaskController required
a bean of type TaskService that could not be found". Remets l'annotation.

**Exercice 2 : deux candidats pour une interface.**
Dans `SecurityConfig`, ajoute temporairement une deuxième méthode `@Bean` qui renvoie un autre `PasswordEncoder`
(par exemple `new BCryptPasswordEncoder(12)`). Nomme la méthode `strongEncoder`, **pas** `passwordEncoder` (4.2
explique pourquoi).
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

## 6. Pièges fréquents

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

## 7. Pour aller plus loin

- **Scopes autres que singleton** : `prototype` (nouvelle instance à chaque injection), `request`, `session`.
- **`@Profile`** : un bean qui n'existe que dans un profil donné (voir [03. Configuration](03-configuration.md)).
- **`@Conditional...`** : écrire tes propres conditions, comme le fait l'auto-configuration.
- **Événements Spring** ⏳ (`ApplicationEventPublisher`) : découpler deux beans sans qu'ils se connaissent.
- **`@Async` / `@Scheduled`** ⏳ : reposent aussi sur des proxies.
- **AOP** : écrire ses propres aspects (journalisation, mesure de temps) avec `@Aspect`.
- **Docs officielles** : en plus des liens de la section 4,
  [Spring Beans and Dependency Injection](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html)
  côté Spring Boot.

---

## 8. Quiz

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
