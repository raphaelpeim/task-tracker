# 11. Tests

**Ce que tu vas comprendre**

- Les différents niveaux de tests et ce que chacun vérifie
- Comment le projet démarre une vraie application et un vrai PostgreSQL pour ses tests d'intégration
- Pourquoi tous les tests partagent le même contexte Spring, et ce que ça impose
- Comment rendre un test isolé et reproductible (ni ordre, ni temps)
- Les outils du projet : JUnit 6 (Jupiter), RestAssured, AssertJ, Mockito, Testcontainers
- Ce qui se passe vraiment entre `./mvnw test` et ta méthode : Surefire et JUnit Platform, l'extension Spring,
  le cache de contexte observé dans les logs, le démarrage du conteneur, `@ServiceConnection`, le port aléatoire,
  et la fabrication des mocks par Mockito, avec ce qui casse quand on retire chaque pièce

**Prérequis** : [02. Beans et injection](02-beans-injection.md), [05. Web et REST](05-web-rest.md),
[08. Persistance](08-persistance.md)

---

## 1. Le problème que ça résout

Tu modifies `UserService` pour ajouter le login. Comment savoir que l'inscription marche toujours ? Que les tâches
se chargent toujours en une seule requête SQL ? Que le format d'erreur n'a pas changé pour le front ?

Sans tests automatisés, il faudrait tout revérifier à la main avec curl après chaque modification. On ne le fait
pas, ou mal, et les régressions passent. Un test automatisé est une **vérification écrite une fois et rejouée à
chaque build** : `./mvnw test` répond en quelques dizaines de secondes à la question "ai-je cassé quelque chose ?".

Les tests servent aussi de **documentation exécutable** : un nom comme
`shouldReturn409WhenEmailDiffersOnlyByCase` dit précisément une règle métier, et elle est garantie vraie.

---

## 2. Les concepts

### 2.1 La pyramide des tests

```
            ▲   lent, coûteux, proche de l'utilisateur
           ╱ ╲
          ╱E2E╲          Bout en bout : front + back + base, via un navigateur
         ╱─────╲
        ╱ Inté- ╲        Intégration : l'application démarrée, vraie base, appels HTTP
       ╱ gration ╲
      ╱───────────╲
     ╱  Unitaires  ╲     Unitaire : une classe seule, sans Spring ni base
    ╱───────────────╲
            ▼   rapide, ciblé, nombreux
```

| Niveau | Vérifie | Vitesse | Exemple |
|---|---|---|---|
| Unitaire | Une classe isolée, ses dépendances remplacées par des doublures | Millisecondes | Tester `MaxBytesValidator.isValid` avec différentes chaînes |
| Intégration | Plusieurs couches ensemble, avec la vraie base | Secondes | `POST /api/auth/register` puis lecture en base |
| Bout en bout | Le parcours complet d'un utilisateur | Dizaines de secondes | Inscription depuis le formulaire React |

Le projet repose aujourd'hui surtout sur des **tests d'intégration**. C'est un choix défendable pour une API
CRUD : la plupart des bugs se situent dans la jonction entre couches (validation, mapping JSON, SQL), que des tests
unitaires ne verraient pas.

### 2.2 Anatomie d'un test JUnit 6 (Jupiter)

```java
class CalculTest {

	@Test                                    // une méthode = un test
	void shouldAddTwoNumbers() {
		// Arrange : préparer les données
		int a = 2;
		int b = 3;

		// Act : exécuter ce qu'on teste
		int result = a + b;

		// Assert : vérifier le résultat
		assertThat(result).isEqualTo(5);
	}
}
```

- `@BeforeEach` / `@AfterEach` : code exécuté avant / après **chaque** test.
- `@ParameterizedTest` : un même test rejoué avec plusieurs jeux de données (`@ValueSource`, `@MethodSource`).
- Convention de nommage du projet : `should<RésultatAttendu>When<Condition>`.

### 2.3 Démarrer l'application dans un test

`@SpringBootTest` démarre **tout** le contexte Spring, comme `main`. Avec
`webEnvironment = RANDOM_PORT`, un vrai serveur Tomcat écoute sur un port libre choisi au hasard (pour ne pas
entrer en conflit avec une application déjà lancée). `@LocalServerPort` récupère ce port.

### 2.4 Une vraie base avec Testcontainers

Plutôt qu'une base en mémoire (H2) qui se comporte différemment de PostgreSQL (index fonctionnels, contraintes
`CHECK`, messages d'erreur...), Testcontainers démarre un **vrai PostgreSQL dans un conteneur Docker** le temps
des tests. `@ServiceConnection` dit à Spring Boot de lire l'URL, l'utilisateur et le mot de passe directement dans
ce conteneur : aucune configuration de datasource à écrire pour les tests.

Conséquence : **Docker doit tourner** pour lancer les tests.

### 2.5 Le cache du contexte Spring

Démarrer le contexte et le conteneur prend plusieurs secondes. Spring met donc le contexte en **cache** : deux
classes de test qui demandent exactement la même configuration réutilisent le même contexte (et donc le même
conteneur).

Le moindre écart de configuration (une propriété en plus, un `@MockitoBean` déclaré dans une seule classe) crée un
**nouveau** contexte, avec un nouveau démarrage. D'où la règle du projet : tout ce qui modifie le contexte est
déclaré dans la classe mère commune.

### 2.6 Les doublures : mock et spy

Une **doublure** remplace une vraie dépendance pour contrôler son comportement :

| Type | Comportement par défaut | Usage |
|---|---|---|
| **Mock** | Ne fait rien, renvoie `null` / `false` / vide | Isoler totalement une classe |
| **Spy** | Appelle le vrai code | Garder le vrai comportement, sauf sur une méthode précise |

```java
// "Quand on appelle existsByEmail, renvoie false", même si l'email existe
doReturn(false).when(repository).existsByEmail(anyString());

// "Quand on appelle saveAndFlush, lève cette exception"
doThrow(new DataIntegrityViolationException("...")).when(repository).saveAndFlush(any());
```

Utile pour provoquer un cas rare et difficile à reproduire (une erreur de base, une inscription concurrente).

### 2.7 Isolation et déterminisme

Un bon test donne **toujours** le même résultat, quel que soit :

- **l'ordre** d'exécution : chaque test prépare ses propres données et nettoie après lui ;
- **le moment** où il tourne : pas de `Thread.sleep`, pas de comparaison avec "maintenant" non maîtrisée. Si une
  règle dépend de la date, on injecte une horloge (`java.time.Clock`) qu'on fixe dans le test ;
- **la machine** : pas de dépendance à un fichier local ou à un service externe.

Un test qui échoue "parfois" (test instable, *flaky*) est pire qu'absent : on finit par ignorer ses échecs.

### 2.8 Les assertions

Le projet utilise deux bibliothèques d'assertions :

```java
// AssertJ : lisible, se lit comme une phrase
assertThat(repository.count()).isEqualTo(1);

// Hamcrest : utilisé par RestAssured pour vérifier le JSON de la réponse
.body("code", equalTo("user.email.alreadyExists"))
```

---

## 3. Dans le projet

### 3.1 La classe mère commune

`server/src/test/java/com/personal/task_tracker/IntegrationTest.java`, lignes 22 à 52 :

```java
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,                       // (1)
		properties = {
				"spring.jpa.properties.hibernate.generate_statistics=true",              // (2)
				"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
		}
)
@Import(TestcontainersConfiguration.class)                                               // (3)
@MockitoSpyBean(types = UserRepository.class)                                            // (4)
public abstract class IntegrationTest {

	@LocalServerPort
	private int port;
	// ...

	@BeforeEach
	void setUpRestAssured() {
		RestAssured.port = port;                                                         // (5)
	}

	/**
	 * Tasks first: they reference the users
	 */
	@AfterEach
	void cleanDatabase() {
		taskRepository.deleteAll();                                                      // (6)
		userRepository.deleteAll();
	}
}
```

1. Application complète sur un port aléatoire.
2. Statistiques Hibernate activées pour **compter les requêtes SQL** (voir 3.4). La ligne suivante coupe le log
   que ces statistiques produiraient à chaque session.
3. Importe le conteneur PostgreSQL.
4. Le `UserRepository` est remplacé par un **spy** : vrai comportement partout, sauf quand un test le modifie.
   Déclaré ici et non dans `AuthControllerTest`, pour que toutes les classes partagent le même contexte. Le
   commentaire des lignes 14 à 21 l'explique.
5. RestAssured envoie ses requêtes sur le bon port.
6. Base vidée après **chaque** test : isolation garantie. Les tâches d'abord, car elles référencent les
   utilisateurs (clé étrangère).

Toutes les classes de test d'API héritent de cette classe : `class AuthControllerTest extends IntegrationTest`
(`auth/AuthControllerTest.java`, ligne 27).

### 3.2 Le conteneur PostgreSQL

`TestcontainersConfiguration.java`, lignes 13 à 21 :

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:16-alpine");
	}
}
```

Le conteneur est un **bean** : il vit aussi longtemps que le contexte Spring, donc il est partagé par toutes les
classes de test. L'image `postgres:16-alpine` correspond à la version majeure utilisée en local
(`postgres:16` dans `docker-compose.yml`) : on teste contre le même moteur.

### 3.3 Un test d'API avec RestAssured

`auth/AuthControllerTest.java`, lignes 101 à 117 (extrait) :

```java
@Test
void shouldReturn409WhenUsernameAlreadyExists() {
	saveUser("jdoe", "jdoe@mail.com");                                   // Arrange : un utilisateur existe

	given()
			.contentType(ContentType.JSON)
			.body(registerRequest("jdoe", "other@mail.com", <mot-de-passe>))
			.when()
			.post("/api/auth/register")                                   // Act : vraie requête HTTP
			.then()
			.statusCode(409)                                              // Assert sur la réponse
			.contentType("application/problem+json")
			.body("detail", equalTo("Username already exists"))
			.body("code", equalTo("user.username.alreadyExists"));

	assertThat(repository.count()).isEqualTo(1);                          // Assert sur la base
}
```

On vérifie **deux** choses : ce que voit le client, et l'état de la base (aucun doublon créé).

### 3.4 Compter les requêtes SQL

`task/TaskControllerTest.java`, lignes 426 à 442 :

```java
@Test
void shouldLoadTasksAndTheirAssigneesInASingleQuery() {
	// ... deux tâches avec deux assignés différents
	Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	statistics.clear();

	given().when().get("/api/tasks").then().statusCode(200) /* ... */;

	assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
}
```

Ce test protège contre le problème N+1 (fiche 08) : si quelqu'un retire le `@EntityGraph` de `TaskRepository`, le
compteur passe à 3 et le test échoue.

### 3.5 Provoquer un cas rare avec le spy

`auth/AuthControllerTest.java`, lignes 217 à 235 :

```java
@Test
void shouldReturn409WhenConcurrentRegistrationHitsUsernameIndex() {
	saveUser("JDoe", "jdoe@mail.com");
	// Simulates a concurrent registration: the check passes, the insert hits the unique index
	doReturn(false).when(repository).existsByUsernameIgnoreCase(anyString());
	// ... POST /api/auth/register avec "jdoe" → 409 attendu
}
```

Deux inscriptions simultanées peuvent passer toutes les deux la vérification `existsBy...` avant que l'une
n'insère. Impossible à reproduire de façon fiable avec deux vraies requêtes. Le spy simule ce cas : la
vérification "ment", et c'est l'index unique de la base qui doit bloquer. Le test vérifie que
`UserService` traduit bien cette erreur de base en 409.

### 3.6 Tests paramétrés

`auth/AuthControllerTest.java`, lignes 336 à 346 :

```java
@ParameterizedTest
@ValueSource(ints = {8, 72})
void shouldRegisterWhenPasswordLengthIsAtBounds(int length) {
	// ... mot de passe de "length" caractères → 201 attendu
}
```

On teste les **bornes** (8 et 72) : c'est là que se cachent les erreurs de `<` au lieu de `<=`.

Pour des jeux de données plus riches, `@MethodSource` pointe vers une méthode qui fournit les arguments :
`TaskControllerTest.java`, lignes 47 à 58 (`invalidPatchBodies`), utilisée ligne 800.

### 3.7 Un contrôleur qui n'existe que dans les tests

`commons/exception/SecurityErrorsTestController.java`, lignes 12 à 24 : un `@RestController` placé dans
`src/test`, qui lève volontairement des exceptions de sécurité. Il permet de tester `GlobalExceptionHandler`
alors qu'aucun vrai contrôleur ne lève encore ces exceptions. Le commentaire ligne 10 explique pourquoi c'est une
classe à part entière : Spring Boot ignore les classes imbriquées dans une classe de test lors du scan.

### 3.8 Des tests sans Spring

Tout n'a pas besoin de passer par une vraie requête HTTP :

- dans `SecurityErrorsTest`, les tests des lignes 53 et 63 appellent directement
  `new SecurityProblemHandler().commence(...)` avec une fausse requête (`MockHttpServletRequest`). Ces deux
  méthodes n'utilisent pas Spring ; la classe, elle, hérite de `IntegrationTest` (ligne 20) parce que ses deux
  autres tests passent par le contrôleur de test, donc le contexte est tout de même démarré ;
- `commons/exception/ValidationMessagesTest.java` (ligne 27) n'hérite pas de `IntegrationTest` : il parcourt les
  classes par réflexion pour vérifier que chaque champ validé a sa clé de traduction. C'est un test unitaire,
  instantané.

---

## 4. Sous le capot

Tu tapes `./mvnw test`, et quelques secondes plus tard un PostgreSQL tourne dans Docker, un Tomcat écoute sur un
port que tu n'as jamais choisi, et `repository` est un espion que tu n'as jamais créé. Cette section démonte la
chaîne, maillon par maillon. Même plan que dans [02. Beans et injection](02-beans-injection.md), section 4 : le
mécanisme réel, comment l'observer, la source, et ce qui casse si on retire la pièce.

Toutes les sorties ci-dessous viennent du projet (Spring Boot 4.1.1, Java 25), relevées avec de petites classes
de test jetables qui héritaient d'`IntegrationTest`. Les numéros de port et les suffixes aléatoires changent à
chaque exécution.

Pour activer un log pendant les tests sans toucher aux fichiers, passe-le à la JVM des tests :
`./mvnw test -Dtest=UserControllerTest "-DargLine=-Dlogging.level.<logger>=DEBUG"`.

### 4.1 De `./mvnw test` à ta méthode : Surefire, JUnit Platform, Jupiter

**Le mécanisme.** Trois étages, chacun ignorant le détail de l'autre :

| Étage | Rôle | Dans le projet |
|---|---|---|
| **Surefire** (plugin Maven) | Liste les fichiers de test, lance une JVM séparée (*fork*), lui passe la liste | `maven-surefire-plugin` 3.5.6, hérité du `spring-boot-starter-parent` : il n'apparaît pas dans ton `pom.xml` |
| **JUnit Platform** (le *launcher*) | Cherche les **moteurs** de test présents sur le classpath et leur délègue | `junit-platform-launcher`, absent des dépendances du projet : Surefire l'ajoute lui-même à la JVM de test |
| **Moteur Jupiter** | Comprend `@Test`, `@BeforeEach`, `@ParameterizedTest`, les extensions... | `junit-jupiter-engine` **6.0.3** (JUnit 6), tiré par les starters `*-test` |

Les deux premiers étages ne connaissent pas `@Test`. Les découvertes se font par convention, sans configuration :

- **Quels fichiers ?** Sans configuration, Surefire prend les classes dont le nom correspond à `**/Test*.java`,
  `**/*Test.java`, `**/*Tests.java` ou `**/*TestCase.java`. C'est pour cela que la convention de nommage
  `...Test` n'est pas qu'esthétique : une classe nommée `AuthControllerVerif` ne serait jamais lancée par
  `./mvnw test`, sans erreur ni avertissement.
- **Quel moteur ?** Surefire voit JUnit Platform sur le classpath et choisit son *provider* JUnit Platform.
  Le launcher trouve ensuite les moteurs par le mécanisme standard `ServiceLoader` de Java : le jar
  `junit-jupiter-engine-6.0.3.jar` contient un fichier texte
  `META-INF/services/org.junit.platform.engine.TestEngine` dont l'unique ligne est
  `org.junit.jupiter.engine.JupiterTestEngine`. Même principe que le fichier `.imports` de l'auto-configuration
  (fiche 02, section 4.6) : un fichier texte dans un jar, lu au démarrage.
- **Quelles méthodes ?** Le moteur Jupiter parcourt chaque classe par **réflexion**, garde les méthodes annotées
  `@Test` (ou `@ParameterizedTest`...), crée **une nouvelle instance de la classe pour chaque méthode de test**
  (cycle de vie par défaut), puis appelle la méthode.

**L'observer.**

- Dans la sortie de `./mvnw test` (sans `-q`), la ligne qui trahit le choix de Surefire :
  ```
  [INFO] --- surefire:3.5.6:test (default-test) @ task-tracker ---
  [INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
  ```
- La pile d'appels, lue au moment où une méthode `@Test` s'exécute (relevée avec
  `Thread.currentThread().getStackTrace()`, filtrée et lue de bas en haut) :
  ```
  org.apache.maven.surefire.booter.ForkedBooter.main                          ← la JVM forkée par Surefire
  org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invoke
  org.junit.platform.launcher.core.DefaultLauncher.execute                    ← JUnit Platform
  org.junit.platform.launcher.core.EngineExecutionOrchestrator.executeEngine
  org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine.execute
  org.junit.platform.engine.support.hierarchical.NodeTestTask.execute         ← moteur, puis classe, puis méthode
  org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor.invokeTestMethod
  org.junit.jupiter.engine.execution.InvocationInterceptorChain.invoke        ← les extensions peuvent s'intercaler
  org.junit.jupiter.engine.extension.TimeoutExtension.interceptTestMethod
  org.junit.platform.commons.util.ReflectionUtils.invokeMethod                ← appel par réflexion de ta méthode
  ```
  Avec un point d'arrêt dans n'importe quel test, ton IDE affiche la même pile. Tu n'y verras pas de `main` à
  toi : c'est Surefire qui possède le `main`.

**La source.** [Surefire : Using JUnit Platform](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html),
[Surefire : Inclusions and Exclusions of Tests](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html),
[JUnit : Test Engines](https://docs.junit.org/6.0.3/advanced-topics/engines.html),
[JUnit : Build Support](https://docs.junit.org/6.0.3/running-tests/build-support.html).

### 4.2 `@SpringBootTest` : une méta-annotation qui branche Spring dans JUnit

**Le mécanisme.** JUnit ne connaît pas Spring. Il sait seulement appeler des **extensions** à des moments précis
(avant la classe, après création de l'instance, avant chaque test...). Ouvre le code source de
`@SpringBootTest` (spring-boot-test 4.1.1), il porte lui-même ces annotations :

```java
@Inherited
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
public @interface SpringBootTest {
```

- `@ExtendWith(SpringExtension.class)` : JUnit lit les annotations **des annotations** (méta-annotations). Poser
  `@SpringBootTest` revient donc à enregistrer `SpringExtension`. C'est la seule porte d'entrée de Spring.
- `@Inherited` : l'annotation est héritée par les sous-classes. C'est ce qui permet à `AuthControllerTest` de
  n'avoir que `extends IntegrationTest`.
- `@BootstrapWith(SpringBootTestContextBootstrapper.class)` : la partie Spring Boot. Elle cherche la
  configuration à charger en **remontant les packages** depuis celui du test jusqu'à trouver une classe
  `@SpringBootConfiguration`. Les tests sont dans `com.personal.task_tracker...`, ils trouvent donc
  `TaskTrackerApplication`. Un test placé dans un autre package racine ne la trouverait pas.

`SpringExtension` implémente les callbacks JUnit (`beforeAll`, `postProcessTestInstance`, `beforeEach`,
`afterEach`...) et les relaie à une liste d'écouteurs Spring, les `TestExecutionListener`, déclarés dans le
fichier `META-INF/spring.factories` de `spring-test` (12 dans la version 7.0.9 utilisée par le projet). Les plus parlants :

| Écouteur | Ce qu'il fait pour toi |
|---|---|
| `DependencyInjectionTestExecutionListener` | Remplit les champs `@Autowired` de **l'instance de test**. La classe de test n'est pas un bean : JUnit l'a créée avec `new`, Spring l'injecte après coup |
| `BeanOverrideTestExecutionListener` | Gère `@MockitoBean` / `@MockitoSpyBean` |
| `MockitoResetTestExecutionListener` | Réinitialise les mocks et spies après chaque test (d'où la ligne des pièges fréquents) |
| `TransactionalTestExecutionListener` | Gère `@Transactional` sur un test (rollback automatique). Le projet ne l'utilise pas, voir [09. Transactions](09-transactions.md) |

**Pourquoi c'est indispensable.** Sans `SpringExtension` (une classe de test sans `@SpringBootTest` ni
`extends IntegrationTest`), JUnit crée l'instance et appelle la méthode, point. Aucun contexte n'est démarré et
personne ne remplit les champs `@Autowired`, qui restent `null`. C'est exactement ce qu'il faut pour
`ValidationMessagesTest` (3.8), et c'est pourquoi il s'exécute en quelques millisecondes.

**L'observer.**

- Les méta-annotations, relevées par réflexion sur `IntegrationTest` (`MergedAnnotations` de Spring, distance 0 =
  posée directement, 1 = portée par une annotation posée) :
  ```
  org.springframework.boot.test.context.SpringBootTest depth=0
  org.springframework.context.annotation.Import depth=0
  org.springframework.test.context.bean.override.mockito.MockitoSpyBean depth=0
  org.springframework.test.context.BootstrapWith depth=1 {value=class org.springframework.boot.test.context.SpringBootTestContextBootstrapper}
  org.junit.jupiter.api.extension.ExtendWith depth=1
  ```
- La recherche de configuration, dans les premières lignes de chaque classe de test :
  ```
  INFO ... SpringBootTestContextBootstrapper -- Found @SpringBootConfiguration com.personal.task_tracker.TaskTrackerApplication for test class com.personal.task_tracker.user.UserControllerTest
  ```
- Un point d'arrêt dans `SpringExtension.postProcessTestInstance` (classe de `spring-test`, à ouvrir dans l'IDE)
  : tu y passes une fois par méthode de test, juste après que JUnit a créé l'instance.

**La source.** [JUnit : Registering Extensions](https://docs.junit.org/6.0.3/extensions/registering-extensions.html),
[SpringExtension for JUnit Jupiter](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html),
[TestExecutionListener Configuration](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tel-config.html),
[Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html).

### 4.3 Le cache de contexte, vu dans les logs

**Le mécanisme.** Avant de démarrer un contexte, Spring calcule une **clé** à partir de la configuration
demandée par la classe de test : classes de configuration, initialiseurs, profils, propriétés, et les
*context customizers*, qui incluent notamment les **bean overrides** (`@MockitoBean`, `@MockitoSpyBean`) et le
support Testcontainers de Spring Boot. Le cache est une simple map statique clé → contexte, qui vit aussi
longtemps que la JVM de test. Même clé : contexte réutilisé tel quel, avec ses beans, son Tomcat et son conteneur.
Clé différente : nouveau démarrage complet. Taille maximale par défaut : 32 contextes, au-delà le moins
récemment utilisé est fermé.

C'est la raison d'être d'`IntegrationTest` : en concentrant **toute** la configuration dans une classe mère,
toutes les classes filles produisent exactement la même clé.

**L'observer.** Active `logging.level.org.springframework.test.context.cache=DEBUG`. Spring affiche des
statistiques à chaque accès au cache. Avec la vraie suite (sans le log DEBUG), le constat est déjà visible : pour
`AuthControllerTest`, `SecurityErrorsTest`, `UserControllerTest` et `TaskControllerTest`, **une seule** ligne
`Started AuthControllerTest in 4.712 seconds` et un seul `Creating container for image: postgres:16-alpine`.
Les trois classes suivantes n'ont aucune ligne `Started` : elles reprennent le contexte déjà prêt.

L'expérience de rupture, vérifiée : trois classes jetables héritant d'`IntegrationTest`, dont la troisième
déclare en plus `@MockitoBean TaskService taskService;`. Extraits :

```
Running ...TmpProbeTestsAlphaTest
Started TmpProbeTestsAlphaTest in 4.302 seconds
... cache statistics: [... size = 1, maxSize = 32, ... hitCount = 0, missCount = 1, ...]
Tomcat started on port 61042 (http)

Running ...TmpProbeTestsBetaTest
... cache statistics: [... size = 1, ... missCount = 1, ...]      ← aucun démarrage : même contexte, même port 61042

Running ...TmpProbeTestsGammaTest
Started TmpProbeTestsGammaTest in 1.758 seconds                         ← nouveau contexte complet
tc.postgres:16-alpine : Container is started (JDBC URL: jdbc:postgresql://localhost:61044/test?loggerLevel=OFF)
HikariPool-2 - Starting...
Successfully applied 2 migrations to schema "public", now at version v2
Tomcat started on port 61064 (http)
... cache statistics: [... size = 2, ... missCount = 2, ...]
```

Un seul `@MockitoBean` en plus a coûté : un deuxième conteneur PostgreSQL, un deuxième pool de connexions, une
deuxième exécution des migrations Flyway, un deuxième Tomcat sur un autre port. Et le premier contexte n'est pas
fermé pour autant (`size = 2`) : les deux conteneurs tournent en même temps jusqu'à la fin de la JVM. Le
deuxième démarrage est plus rapide que le premier uniquement parce que la JVM est déjà chaude et l'image Docker
déjà présente.

Le `hitCount` grimpe de plusieurs unités par test : chaque écouteur de 4.2 redemande le contexte au cache, ce
n'est pas un redémarrage.

**La source.** [Context Caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
(la liste exacte des éléments de la clé y figure).

### 4.4 Testcontainers : qui démarre PostgreSQL, et quand

**Le mécanisme.** `TestcontainersConfiguration` ne fait que **déclarer** un bean `PostgreSQLContainer` : le
constructeur `new PostgreSQLContainer("postgres:16-alpine")` ne lance rien. Le démarrage vient du module
`spring-boot-testcontainers`, branché lui aussi par un fichier texte : son `META-INF/spring.factories` enregistre
`TestcontainersLifecycleApplicationContextInitializer`, qui ajoute au contexte un `BeanPostProcessor`
(`TestcontainersLifecycleBeanPostProcessor`). Comme tout `BeanPostProcessor` (fiche 02, section 4.4), il voit
passer chaque bean après sa création ; dès qu'il voit un objet `Startable` (c'est le cas de tout conteneur
Testcontainers), il appelle `start()`. Le conteneur est donc démarré **pendant** la création du contexte, avant
les beans qui en ont besoin.

`start()` déroule alors, côté Testcontainers :

1. trouver Docker (socket Unix, variable `DOCKER_HOST`, fichier `~/.testcontainers.properties`...) ;
2. démarrer **Ryuk**, un petit conteneur "ramasse-miettes" qui supprimera les conteneurs de la session quand la
   JVM de test s'arrêtera. C'est pour cela que le projet n'a aucun code d'arrêt du conteneur ;
3. créer le conteneur PostgreSQL, en publiant son port 5432 sur un **port libre aléatoire** de ta machine ;
4. attendre que PostgreSQL accepte les connexions, puis rendre la main.

**L'observer.** Les logs Testcontainers sont au niveau INFO, ils sont déjà dans la sortie de `./mvnw test` :

```
org.testcontainers.DockerClientFactory   : Testcontainers version: 2.0.5
o.t.d.DockerClientProviderStrategy       : Found Docker environment with local Unix socket (unix:///var/run/docker.sock)
tc.testcontainers/ryuk:0.14.0            : Container testcontainers/ryuk:0.14.0 started in PT0.300419S
o.t.utility.RyukResourceReaper           : Ryuk started - will monitor and terminate Testcontainers containers on JVM exit
tc.postgres:16-alpine                    : Creating container for image: postgres:16-alpine
tc.postgres:16-alpine                    : Container postgres:16-alpine started in PT0.857666S
tc.postgres:16-alpine                    : Container is started (JDBC URL: jdbc:postgresql://localhost:61022/test?loggerLevel=OFF)
org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql://localhost:61022/test?loggerLevel=OFF (PostgreSQL 16.15)
```

L'ordre dit tout : `Tomcat initialized with port 0` apparaît **avant** ces lignes, Flyway **après**. Le conteneur
démarre au milieu de la construction du contexte, juste à temps pour la base. Le port (61022 ici) et la base
`test` sont choisis par Testcontainers, pas par toi.

**La source.** [Testcontainers : PostgreSQL Module](https://java.testcontainers.org/modules/databases/postgres/),
[Testcontainers : Supported Docker environment](https://java.testcontainers.org/supported_docker_environment/),
[Testcontainers : Custom configuration](https://java.testcontainers.org/features/configuration/),
[Spring Boot : Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).

### 4.5 `@ServiceConnection` : une URL qu'aucune propriété ne contient

**Le mécanisme.** Le projet n'a **aucune** propriété `spring.datasource.url` (ni dans `application.properties`, ni
dans les tests). La datasource ne lit d'ailleurs pas directement ces propriétés : elle lit un bean de type
`JdbcConnectionDetails`. Dans `DataSourceAutoConfiguration` :

```java
@Bean
@ConditionalOnMissingBean(JdbcConnectionDetails.class)
PropertiesJdbcConnectionDetails jdbcConnectionDetails(DataSourceProperties properties) {
	return new PropertiesJdbcConnectionDetails(properties);    // lit spring.datasource.*
}
```

En application normale, personne d'autre ne fournit de `JdbcConnectionDetails`, donc c'est cette version
"propriétés" qui est créée. En test, `@ServiceConnection` sur le bean conteneur déclenche
`ServiceConnectionAutoConfiguration` (module `spring-boot-testcontainers`), qui enregistre un autre
`JdbcConnectionDetails` construit **à partir du conteneur** : `getJdbcUrl()`, `getUsername()`, `getPassword()`
sont demandés au conteneur démarré. Le `@ConditionalOnMissingBean` écarte alors la version "propriétés". Le lien
entre "conteneur PostgreSQL" et "détails JDBC" est fait par une fabrique, `JdbcContainerConnectionDetailsFactory`,
déclarée dans le `spring.factories` de `spring-boot-jdbc`.

**L'observer.** Relevé dans un test du projet :

```
environment.getProperty("spring.datasource.url") = null
bean jdbcContainerConnectionDetailsForPostgresContainer
  -> org.springframework.boot.jdbc.testcontainers.JdbcContainerConnectionDetailsFactory$JdbcContainerConnectionDetails
     url=jdbc:postgresql://localhost:61022/test?loggerLevel=OFF
dataSource.getConnection().getMetaData().getURL() = jdbc:postgresql://localhost:61022/test?loggerLevel=OFF
```

La propriété n'existe pas, et pourtant la `HikariDataSource` est connectée au port du conteneur.

**Ce qui se passe sans.** Vérifié en retirant temporairement `@ServiceConnection` de
`TestcontainersConfiguration` : le contexte ne démarre pas.

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.

Reason: Failed to determine a suitable driver class
```

Le conteneur, lui, n'a même pas été démarré (aucune ligne `tc.postgres` dans la sortie) : sans
`@ServiceConnection`, aucun bean n'en dépend, et l'échec survient avant qu'il soit créé. Avant Spring Boot 3.1,
on écrivait ce lien à la main avec `@DynamicPropertySource` et `registry.add("spring.datasource.url", postgres::getJdbcUrl)`.

**La source.** [Spring Boot : Service Connections](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).

### 4.6 Le port aléatoire, et comment RestAssured le trouve

**Le mécanisme.** `RANDOM_PORT` n'invente pas de port : il pose la propriété `server.port=0`. Le chiffre 0 dit au
système d'exploitation "donne-moi n'importe quel port libre". Une fois Tomcat démarré, Spring Boot lit le port
réellement obtenu et le publie dans la propriété `local.server.port` (initialiseur
`ServerPortInfoApplicationContextInitializer`). `@LocalServerPort` n'est qu'un raccourci : son code source est
`@Value("${local.server.port}")`.

RestAssured, lui, ne sait rien de Spring. Sa configuration est faite de **champs statiques**, avec des valeurs
par défaut :

```
RestAssured.DEFAULT_PORT = 8080
RestAssured.DEFAULT_URI  = http://localhost
```

D'où la ligne `RestAssured.port = port;` dans le `@BeforeEach` d'`IntegrationTest`. Sans elle, toutes les requêtes
partiraient vers `localhost:8080`, là où il n'y a rien, ou pire : là où tourne peut-être ton application lancée
en local avec sa vraie base.

**Pourquoi dans `@BeforeEach` et pas une seule fois ?** Parce que le port dépend du contexte. Dans l'expérience de
4.3, la troisième classe, avec son propre contexte, avait le port 61064 alors que les deux premières avaient
61042. Un champ statique réglé une fois pour toutes viserait le Tomcat d'une autre classe.

**L'observer.**

```
o.s.boot.tomcat.TomcatWebServer : Tomcat initialized with port 0 (http)
o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 61042 (http) with context path '/'
```

Le 0 de la première ligne est la demande, le 61042 de la seconde est la réponse de l'OS.

**La source.** [Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
(section sur `webEnvironment`), [RestAssured : Usage](https://github.com/rest-assured/rest-assured/wiki/Usage)
(section *Default values*).

### 4.7 Mockito : comment un mock ou un espion est fabriqué

La fiche 02 (section 4.5) a montré le nom de classe du repository en test,
`UserRepository$MockitoMock$...$$SpringCGLIB$$0`. Voici ce que chaque morceau signifie.

**Le mécanisme.** Pour intercepter les appels, Mockito doit fabriquer du bytecode. Il utilise la bibliothèque
**ByteBuddy**, avec deux stratégies selon ce qu'il imite :

- **une interface** (`UserRepository`) : ByteBuddy écrit en mémoire une **nouvelle classe** qui l'implémente,
  nommée `UserRepository$MockitoMock$` suivi d'un suffixe aléatoire. Chaque méthode y renvoie vers le moteur de
  Mockito, qui consulte les `doReturn(...)` enregistrés ;
- **une classe concrète** : depuis Mockito 5, le *mock maker* par défaut est `InlineByteBuddyMockMaker`. Il ne
  crée pas de sous-classe : il **réécrit le bytecode de la classe elle-même**, déjà chargée, pour y insérer ses
  points d'interception. C'est ce qui permet de mocker des classes et méthodes `final`.

Réécrire une classe déjà chargée exige l'API `java.lang.instrument`, accessible seulement à un **agent Java**.
Faute d'agent déclaré au lancement, Mockito s'attache lui-même à la JVM en cours (*self-attach*) avec
`byte-buddy-agent`. Depuis Java 21, le JDK avertit de ce chargement dynamique, qu'il compte interdire par défaut
dans une version future (JEP 451).

Côté Spring, `@MockitoSpyBean` a un cas particulier : le vrai `UserRepository` est un proxy dynamique JDK (fiche 02,
section 4.5), qu'on ne peut pas espionner directement. Spring crée alors un **mock** de l'interface dont la réponse
par défaut **délègue au vrai repository** (`AdditionalAnswers.delegatesTo`, dans
`MockitoSpyBeanOverrideHandler`). Le résultat se comporte comme un espion, mais Mockito ne le considère pas comme
tel. Enfin Spring enveloppe ce mock dans un proxy CGLIB, dont le seul advisor relevé est
`PersistenceExceptionTranslationInterceptor` : la traduction des exceptions JPA en exceptions Spring
(`DataIntegrityViolationException`...) reste donc active sur le repository espionné.

**L'observer.** Relevé dans un test du projet :

```
classe :        com.personal.task_tracker.user.repository.UserRepository$MockitoMock$hc6ePzO5$$SpringCGLIB$$0
                    interfaces=[SpringProxy, Advised, Factory]                     ← proxy CGLIB de Spring
superclasse :   com.personal.task_tracker.user.repository.UserRepository$MockitoMock$hc6ePzO5
                    interfaces=[UserRepository, MockAccess]                        ← classe écrite par ByteBuddy
advisor :       org.springframework.dao.support.PersistenceExceptionTranslationInterceptor
mockingDetails: isMock=true isSpy=false
mock maker :    org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker
```

Le suffixe (`hc6ePzO5`) change à chaque exécution. Et pour une classe concrète, avec `@MockitoBean TaskService`
dans l'expérience de 4.3 : `taskService.getClass().getName()` renvoie `com.personal.task_tracker.task.service.TaskService`,
**sans suffixe**, alors que `mockingDetails(taskService).isMock()` vaut `true`. C'est la réécriture "inline" : la
classe est la vraie, son bytecode a été modifié.

Au premier mock créé, la sortie de `./mvnw test` contient :

```
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
WARNING: A Java agent has been loaded dynamically (/Users/<toi>/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

Ce n'est pas une erreur, les tests passent. Vérifié : en déclarant Mockito comme agent au lancement de la JVM de
test, ces lignes disparaissent :

```bash
./mvnw test -Dtest=UserControllerTest \
  "-DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar"
```

La solution durable, décrite dans la documentation Mockito (lien du message), consiste à faire la même chose dans
le `pom.xml`, via le `maven-dependency-plugin` (but `properties`) et l'`argLine` de Surefire. Elle n'est pas
appliquée au projet aujourd'hui.

**La source.** [Mockito : javadoc (section 0.3, configuration de l'agent)](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html),
[JEP 451 : Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451),
[Spring : Bean Overriding in Tests](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/bean-overriding.html),
[`@MockitoBean` et `@MockitoSpyBean`](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-mockitobean.html).

### 4.8 Récapitulatif : retire une pièce, regarde ce qui casse

| Tu retires... | Ce qui se passe | Vérifié ? |
|---|---|---|
| `@ServiceConnection` | `APPLICATION FAILED TO START` : `'url' attribute is not specified` (4.5) | Oui |
| La ligne `RestAssured.port = port` | Les requêtes partent vers `localhost:8080` (4.6) | Valeur par défaut vérifiée, échec non exécuté |
| `extends IntegrationTest` sur une classe d'API | Pas de `SpringExtension` : champs `@Autowired` à `null`, aucun serveur (4.2) | Déduit du mécanisme, non exécuté |
| Le suffixe `Test` du nom de classe | Surefire ignore la classe, en silence (4.1) | D'après la documentation Surefire |
| Rien, mais tu **ajoutes** un `@MockitoBean` dans une seule classe | Deuxième contexte, deuxième conteneur, migrations rejouées (4.3) | Oui |
| Rien, mais tu ajoutes `-javaagent:...mockito-core...jar` | Plus d'avertissement d'agent dynamique (4.7) | Oui |

Pour reproduire une ligne "Oui" : crée une classe jetable qui hérite d'`IntegrationTest`, lance-la seule avec
`./mvnw test -Dtest=NomDeLaClasse`, lis la sortie, puis supprime-la.

---

## 5. À toi de jouer

1. **Lancer la suite.** Docker démarré, exécute `cd server && ./mvnw clean test`.
   *À observer* : dans les logs, un seul démarrage de conteneur PostgreSQL et un seul démarrage de Spring pour toutes
   les classes héritant de `IntegrationTest`. C'est le cache de contexte.
2. **Casser le cache volontairement.** Sur une branche jetable, ajoute `@TestPropertySource(properties = "x=y")` sur
   une seule classe de test, relance.
   *À observer* : un deuxième démarrage du contexte, et une suite plus lente. Retire l'annotation.
3. **Faire échouer le test N+1.** Commente le `@EntityGraph` de `TaskRepository` et lance uniquement
   `./mvnw test -Dtest=TaskControllerTest`.
   *À observer* : `shouldLoadTasksAndTheirAssigneesInASingleQuery` échoue avec un nombre de requêtes supérieur à 1.
   Remets l'annotation.
4. **Écrire un test unitaire pur** pour `MaxBytesValidator` (sans `@SpringBootTest`) : une chaîne de 72 octets
   valide, une de 73 invalide, `null` valide.
   *À observer* : le test s'exécute en quelques millisecondes.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Could not find a valid Docker environment` | Docker n'est pas démarré | Démarrer Docker avant `./mvnw test` |
| Un test passe seul mais échoue avec les autres | Données laissées par un autre test, ou dépendance à l'ordre | Nettoyer en `@AfterEach`, chaque test crée ses données |
| La suite devient beaucoup plus lente | Un nouveau contexte Spring créé à cause d'une configuration différente dans une classe | Déclarer bean overrides et propriétés dans `IntegrationTest` |
| Test instable (passe, puis échoue) | Dépendance au temps (`sleep`, `now()`) ou à l'ordre des résultats | Horloge injectée, toujours trier les listes vérifiées |
| `deleteAll` lève une erreur de clé étrangère | Ordre de suppression incorrect | Supprimer d'abord les tables qui référencent les autres |
| Un stub Mockito "fuit" sur le test suivant | Spy non réinitialisé | `@MockitoSpyBean` le réinitialise après chaque test par défaut ; ne pas créer de spy à la main |
| Le test vérifie le code de statut mais un bug passe | Assertion trop faible | Vérifier aussi le corps et l'état de la base |

---

## 7. Pour aller plus loin

- **Tests "slice"** ⏳ : `@WebMvcTest` (uniquement la couche web, services mockés), `@DataJpaTest` (uniquement JPA).
  Plus rapides que `@SpringBootTest`.
- **Tests unitaires purs** ⏳ de services, avec Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`,
  `@InjectMocks`).
- **Tests avec authentification** : une fois le JWT en place, les tests devront fournir un token (voir
  [Guide JWT](14-jwt.md), section 8).
- **Couverture de code** (JaCoCo) : mesure les lignes exécutées par les tests. Un indicateur, pas un objectif.
- **Tests de mutation** (PIT) : modifient le code pour vérifier que les tests détectent le changement.
- **Mockito comme agent déclaré** dans le `pom.xml`, pour supprimer l'avertissement de chargement dynamique
  (section 4.7).
- Documentation officielle : *JUnit User Guide*, *Spring Boot Reference > Testing*, *Testcontainers
  documentation*, *RestAssured Usage*, *AssertJ documentation*. Les liens précis vers chaque mécanisme sont à la
  fin de chaque sous-section de la section 4 : [JUnit 6 User Guide](https://docs.junit.org/6.0.3/overview.html),
  [Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html),
  [Context Caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html),
  [Spring Boot : Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html),
  [RestAssured : Usage](https://github.com/rest-assured/rest-assured/wiki/Usage),
  [Mockito javadoc](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html).

---

## 8. Quiz

**1. Pourquoi le projet utilise-t-il un vrai PostgreSQL plutôt que H2 en mémoire pour ses tests ?**

<details><summary>Réponse</summary>

Pour tester contre le même moteur qu'en réel. L'index unique sur `upper(username)`, les contraintes `CHECK` ou les
noms de contraintes lus par `UserService` peuvent se comporter différemment, voire ne pas exister, avec H2.
</details>

**2. Pourquoi `@MockitoSpyBean` est-il déclaré dans `IntegrationTest` et pas dans `AuthControllerTest`, qui est le seul à s'en servir ?**

<details><summary>Réponse</summary>

Pour que toutes les classes de test aient exactement la même configuration et partagent un seul contexte Spring
en cache. Le déclarer dans une seule classe créerait un deuxième contexte, avec un nouveau démarrage.
</details>

**3. Quelle est la différence entre un mock et un spy ?**

<details><summary>Réponse</summary>

Un mock ne fait rien par défaut (renvoie des valeurs vides). Un spy appelle le vrai code, sauf pour les méthodes
qu'on a explicitement redéfinies avec `doReturn` / `doThrow`.
</details>

**4. Que protège le test `shouldLoadTasksAndTheirAssigneesInASingleQuery` ?**

<details><summary>Réponse</summary>

Il garantit que la liste des tâches et leurs assignés sont chargés en une seule requête SQL, donc que le problème
N+1 ne réapparaît pas si quelqu'un retire le `@EntityGraph`.
</details>

**5. Pourquoi tester les valeurs 8 et 72 pour la longueur du mot de passe, plutôt que 20 ?**

<details><summary>Réponse</summary>

Ce sont les bornes de la règle. Les erreurs (`<` au lieu de `<=`) se situent aux limites, une valeur au milieu ne
les détecterait pas.
</details>

**6. Pourquoi un test ne doit-il jamais utiliser `Thread.sleep` pour attendre qu'un token expire ?**

<details><summary>Réponse</summary>

Le test serait lent et dépendrait du temps et de la charge de la machine, donc instable. On construit plutôt un
token dont la date d'expiration est déjà passée, ou on injecte une horloge fixe.
</details>
