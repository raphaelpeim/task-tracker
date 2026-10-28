# 11. Tests

**Ce que tu vas comprendre**

- Les différents niveaux de tests et ce que chacun vérifie
- Comment le projet démarre une vraie application et un vrai PostgreSQL pour ses tests d'intégration
- Pourquoi tous les tests partagent le même contexte Spring, et ce que ça impose
- Comment rendre un test isolé et reproductible (ni ordre, ni temps)
- Les outils du projet : JUnit 5, RestAssured, AssertJ, Mockito, Testcontainers

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

### 2.2 Anatomie d'un test JUnit 5

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
   commentaire des lignes 14 à 20 l'explique.
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

## 4. À toi de jouer

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

## 5. Pièges fréquents

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

## 6. Pour aller plus loin

- **Tests "slice"** ⏳ : `@WebMvcTest` (uniquement la couche web, services mockés), `@DataJpaTest` (uniquement JPA).
  Plus rapides que `@SpringBootTest`.
- **Tests unitaires purs** ⏳ de services, avec Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`,
  `@InjectMocks`).
- **Tests avec authentification** : une fois le JWT en place, les tests devront fournir un token (voir
  [Guide JWT](14-jwt.md), section 8).
- **Couverture de code** (JaCoCo) : mesure les lignes exécutées par les tests. Un indicateur, pas un objectif.
- **Tests de mutation** (PIT) : modifient le code pour vérifier que les tests détectent le changement.
- Documentation officielle : *JUnit 5 User Guide*, *Spring Boot Reference > Testing*, *Testcontainers
  documentation*, *RestAssured Usage*, *AssertJ documentation*.

---

## 7. Quiz

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
