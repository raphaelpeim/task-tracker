# Inventaire des notions pour comprendre un projet Spring Boot

Ce document recense les notions à connaître pour lire et faire évoluer le task-tracker, et plus largement n'importe
quel projet Spring Boot. Pour chaque notion : ce que c'est, **où la voir dans le projet**, et ce qu'il faut retenir.

Les notions marquées ⏳ ne sont pas encore dans le projet mais arrivent vite dans un vrai projet.

Chemins abrégés : `main/` = `server/src/main/java/com/personal/task_tracker/`,
`test/` = `server/src/test/java/com/personal/task_tracker/`, `resources/` = `server/src/main/resources/`.

---

## Sommaire

1. [Suivre une requête de bout en bout](#1-suivre-une-requête-de-bout-en-bout)
2. [Build et dépendances : Maven, pom.xml](#2-build-et-dépendances--maven-pomxml)
3. [Le cœur de Spring : beans et injection](#3-le-cœur-de-spring--beans-et-injection)
4. [Configuration](#4-configuration)
5. [Architecture en couches](#5-architecture-en-couches)
6. [Web et REST](#6-web-et-rest)
7. [Validation](#7-validation)
8. [Exceptions et format d'erreur](#8-exceptions-et-format-derreur)
9. [Persistance : JPA, Hibernate, Spring Data, Flyway](#9-persistance--jpa-hibernate-spring-data-flyway)
10. [Transactions](#10-transactions)
11. [Sécurité](#11-sécurité)
12. [Tests](#12-tests)
13. [Java moderne et qualité de code](#13-java-moderne-et-qualité-de-code)
14. [Environnement, outillage, infra](#14-environnement-outillage-infra)
15. [Notions à venir](#15-notions-à-venir)
16. [Au-delà de Spring](#16-au-delà-de-spring)
17. [Grille d'auto-évaluation](#17-grille-dauto-évaluation)

---

## 1. Suivre une requête de bout en bout

Avant le détail, la vue d'ensemble. Voici le trajet de `PATCH /api/tasks/1` :

```
Client HTTP
   │  PATCH /api/tasks/1  {"status":"DONE"}
   ▼
Tomcat (serveur embarqué)
   ▼
Chaîne de filtres Spring Security        ← commons/config/SecurityConfig.java
   │  (401/403 écrits ici par SecurityProblemHandler, avant tout contrôleur)
   ▼
DispatcherServlet (le "routeur" de Spring MVC)
   ▼
Jackson : JSON → TaskRequestPartialDto  ← erreur de format → 400
   ▼
Bean Validation (@Valid)                 ← contrainte violée → 400
   ▼
TaskController.updatePartialTask         ← task/controller/
   ▼
TaskService.updatePartialTask            ← task/service/  (ouvre la transaction)
   ▼
TaskRepository.findById                  ← task/repository/ (SQL généré par Hibernate)
   ▼
TaskMapper.applyPartialUpdate            ← modifie l'entité
   ▼
Fin de transaction : Hibernate détecte le changement et fait l'UPDATE
   ▼
TaskMapper.toTaskResponseDto → Jackson : DTO → JSON
   ▼
Client : 200 + corps JSON
   (exception à n'importe quelle étape → GlobalExceptionHandler → ProblemDetail)
```

Savoir refaire ce schéma de tête, c'est déjà comprendre 80 % d'un projet Spring Boot.

---

## 2. Build et dépendances : Maven, pom.xml

| Notion | Où | À retenir |
|---|---|---|
| **Maven** | `server/pom.xml` | Outil de build : télécharge les dépendances, compile, lance les tests, produit le jar. Commandes clés : `./mvnw test`, `./mvnw spring-boot:run`, `./mvnw package` |
| **Coordonnées GAV** | `groupId` / `artifactId` / `version` | Identifient de façon unique une bibliothèque (et ton projet) |
| **Parent `spring-boot-starter-parent`** | haut du pom | Fournit les versions de centaines de libs compatibles entre elles. C'est pour ça que la plupart des dépendances n'ont **pas** de `<version>` |
| **BOM** (Bill Of Materials) | `jackson-bom.version`, `postgresql.version` dans `<properties>` | Liste de versions cohérentes. Surcharger une propriété change la version gérée par le parent, à faire en connaissance de cause |
| **Starters** | `spring-boot-starter-webmvc`, `-data-jpa`, `-security`... | Un starter = un paquet de dépendances + l'auto-configuration associée. Ajouter un starter "active" une fonctionnalité |
| **Scopes** | `test`, `runtime`, `optional` | `test` : seulement pour les tests. `runtime` : pas nécessaire pour compiler (driver PostgreSQL). `optional` : non transmis (devtools) |
| **Dépendance transitive** | invisible dans le pom | Une dépendance en amène d'autres. `./mvnw dependency:tree` les affiche |
| **Plugin** | `spring-boot-maven-plugin` | Ajoute des commandes au build ; celui-ci produit un jar exécutable (`java -jar`) |
| **SNAPSHOT** | `0.0.1-SNAPSHOT` (ton projet) | Version "en cours". Acceptable pour ton propre projet, interdit pour une dépendance |
| **Maven Wrapper** | `mvnw` | Garantit la même version de Maven pour tout le monde |

---

## 3. Le cœur de Spring : beans et injection

C'est **la** notion fondamentale ; tout le reste repose dessus.

| Notion | Où | À retenir |
|---|---|---|
| **Inversion de contrôle (IoC)** | partout | Tu n'écris jamais `new TaskService(...)`. Spring crée les objets et les relie |
| **Bean** | toute classe annotée, ou méthode `@Bean` | Un objet géré par Spring. Par défaut **singleton** : une seule instance partagée, donc **sans état mutable** |
| **Contexte applicatif** | démarré par `TaskTrackerApplication.main` | Le "conteneur" qui contient tous les beans |
| **Stéréotypes** | `@RestController`, `@Service`, `@Repository`, `@Component`, `@Configuration` | Marquent une classe comme bean. Fonctionnellement proches, mais indiquent le rôle de la classe |
| **`@Bean`** | `SecurityConfig.encoder()` | Déclarer un bean à partir d'une méthode, utile pour une classe tierce (`BCryptPasswordEncoder`) |
| **Injection par constructeur** | `TaskService(UserService, TaskRepository)` | La bonne pratique : champs `final`, dépendances visibles, testable. Pas besoin de `@Autowired` s'il n'y a qu'un constructeur |
| **Injection par interface** | `PasswordEncoder` injecté dans `UserService` | On dépend du contrat, pas de l'implémentation (`BCryptPasswordEncoder`) |
| **`@SpringBootApplication`** | `TaskTrackerApplication.java` | = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`. Scanne le package et ses sous-packages |
| **Component scan** | package `com.personal.task_tracker` | Une classe hors de ce package n'est pas trouvée |
| **Auto-configuration** | invisible | Spring Boot crée des beans tout seul selon le classpath et la config (DataSource, Jackson, Flyway...). Si tu déclares ton propre bean, le sien s'efface en général |
| **Proxy / AOP** | `@Transactional`, `@PreAuthorize` | Spring entoure ton bean d'un proxy qui ajoute un comportement. Conséquence : une méthode qui en appelle une autre **de la même classe** contourne le proxy (piège de l'auto-invocation) |
| **Dépendance circulaire** | à éviter | A injecte B qui injecte A : Spring refuse de démarrer. Signe d'un mauvais découpage |

---

## 4. Configuration

| Notion | Où | À retenir |
|---|---|---|
| **`application.properties`** | `resources/` | Configuration commune à tous les environnements |
| **Profils** | `application-local.properties` | Activé par `spring.profiles.active=local`. Le fichier de profil **surcharge** le fichier commun |
| **Fichier `.example`** | `application-local.properties.example` | Documente les clés attendues, sans valeur. Le vrai fichier est ignoré par git |
| **Placeholders `${...}`** | (guide JWT : `${JWT_SECRET}`) | Lit une variable d'environnement ou une autre propriété. `${VAR:defaut}` fournit une valeur par défaut |
| **Ordre de priorité** | | Arguments de ligne de commande > variables d'environnement > fichier de profil > `application.properties`. Permet de changer la config sans recompiler |
| **Relaxed binding** | | `SPRING_DATASOURCE_URL` (env) = `spring.datasource.url` (properties) |
| **`@ConfigurationProperties`** | `auth/config/JwtProperties.java` (à remplir) | Regroupe des propriétés dans un objet typé et validable, mieux que des `@Value` éparpillés |
| **`@Configuration`** | `commons/config/SecurityConfig.java` | Classe qui déclare des beans |
| **Propriétés clés du projet** | `application.properties` | `ddl-auto=validate` (Hibernate vérifie le schéma sans le modifier), `open-in-view=false` (voir §10) |
| **Secrets** | | Jamais dans git. Variables d'environnement ou coffre de secrets |

---

## 5. Architecture en couches

| Notion | Où | À retenir |
|---|---|---|
| **Package par fonctionnalité** | `task/`, `user/`, `auth/`, `commons/` | Tout ce qui concerne les tâches est au même endroit. Alternative : package par couche (`controllers/`, `services/`), moins lisible quand le projet grossit |
| **Controller** | `task/controller/TaskController.java` | Couche HTTP **uniquement** : lit la requête, appelle le service, construit la réponse. Aucune règle métier |
| **Service** | `task/service/TaskService.java` | Règles métier et transactions. C'est la couche qu'on teste le plus |
| **Repository** | `task/repository/TaskRepository.java` | Accès aux données. Ne contient pas de logique |
| **Sens des dépendances** | | Controller → Service → Repository. Jamais l'inverse. Un service peut appeler un autre service (`TaskService` → `UserService`) |
| **Entity** | `task/entity/Task.java` | Reflet d'une table. Ne sort **pas** de la couche service |
| **DTO** (Data Transfer Object) | `task/dto/` | Forme des données échangées avec le client. Découple l'API de la base : on peut renommer une colonne sans casser le front, et on ne fuit pas de champ sensible (`password`) |
| **DTO de requête / de réponse** | `TaskRequestDto`, `TaskResponseDto` | Séparés : le client n'envoie pas `id` ni `createdDate` |
| **Mapper** | `task/mapper/TaskMapper.java` | Conversion entity ↔ DTO. Ici en méthodes statiques écrites à la main (alternative : MapStruct) |
| **Enums** | `task/enums/` | Valeurs fermées (`TaskStatus`...). Stockées en texte en base |
| **`commons`** | `commons/` | Ce qui est transverse : sécurité, gestion d'erreurs |

---

## 6. Web et REST

| Notion | Où | À retenir |
|---|---|---|
| **REST** | `/api/tasks` | Ressources nommées au pluriel, actions portées par le verbe HTTP |
| **Verbes HTTP** | `TaskController` | `GET` lire, `POST` créer, `PUT` remplacer entièrement, `PATCH` modifier partiellement, `DELETE` supprimer |
| **Idempotence** | | `GET`, `PUT`, `DELETE` : rejouer donne le même résultat. `POST` non |
| **Codes de statut** | | 200 OK, 201 Created, 204 No Content, 400 requête invalide, 401 non authentifié, 403 interdit, 404 introuvable, 409 conflit, 500 erreur serveur |
| **`@RestController`** | | `@Controller` + le retour est écrit en JSON |
| **`@RequestMapping` / `@GetMapping`...** | | Associent une URL et un verbe à une méthode |
| **`@PathVariable`** | `/{taskId}` | Partie variable de l'URL |
| **`@RequestBody`** | `createTask` | Le corps JSON converti en objet |
| **`@RequestParam`** ⏳ | | Paramètre d'URL `?status=DONE`, pour les filtres et la pagination |
| **`ResponseEntity`** | tous les contrôleurs | Permet de choisir le code de statut et les en-têtes |
| **Jackson** | `tools.jackson.*` (Jackson 3) | Convertit JSON ↔ objets Java. Configuré automatiquement par Spring Boot |
| **PUT vs PATCH et `JsonNullable`** | `TaskRequestPartialDto` | En PATCH il faut distinguer "champ absent" (ne pas toucher) de "champ à `null`" (effacer). `JsonNullable` le permet, un `String` simple non |
| **Tomcat embarqué** | | Le serveur web est dans le jar : pas de serveur à installer |
| **DispatcherServlet** | | Point d'entrée unique de Spring MVC, aiguille vers le bon contrôleur |

---

## 7. Validation

| Notion | Où | À retenir |
|---|---|---|
| **Bean Validation (Jakarta)** | `RegisterRequestDto`, `TaskRequestDto` | Contraintes déclaratives : `@NotBlank`, `@NotNull`, `@Size`, `@Email`... |
| **`@Valid`** | paramètres `@RequestBody @Valid` | Sans lui, **aucune** contrainte n'est vérifiée |
| **`@NotNull` / `@NotEmpty` / `@NotBlank`** | | Pas null / pas vide / pas uniquement des espaces (pour les chaînes) |
| **Messages externalisés** | `resources/ValidationMessages.properties` + `message = "{task.title.required}"` | La clé sert de code pour le front (traduction), la valeur de phrase anglaise |
| **Contrainte personnalisée** | `auth/validator/MaxBytes.java` + `MaxBytesValidator.java` | Une annotation + une classe `ConstraintValidator`. Ici : BCrypt n'accepte que 72 **octets**, alors que `@Size` compte des **caractères** |
| **Validation ≠ nullabilité** | `@NonNull` jspecify dans `LoginRequestDto` | Les annotations jspecify servent à l'analyse statique, **pas** à la validation à l'exécution |
| **Validation à plusieurs niveaux** | DTO + contraintes SQL (`CHECK`, `NOT NULL`, `UNIQUE`) | Le DTO protège l'API, la base garantit l'intégrité quoi qu'il arrive |

---

## 8. Exceptions et format d'erreur

| Notion | Où | À retenir |
|---|---|---|
| **Exception métier** | `TaskNotFoundException`, `UserAlreadyExistsException` | Hérite de `RuntimeException` (non vérifiée) : pas besoin de `throws` partout |
| **Méthodes de fabrique** | `UserAlreadyExistsException.username()` / `.email()` | Constructeur privé + méthodes nommées : lisible, et le `code` ne peut pas être faux |
| **Cause chaînée** | `username(exception)` | Garder l'exception d'origine pour le débogage |
| **`@RestControllerAdvice`** | `commons/exception/GlobalExceptionHandler.java` | Intercepte les exceptions de **tous** les contrôleurs et les traduit en réponse HTTP |
| **`@ExceptionHandler`** | méthodes du handler | Une méthode par type d'exception |
| **`ResponseEntityExceptionHandler`** | classe mère du handler | Gère déjà les exceptions standard de Spring MVC ; on surcharge celles qu'on veut personnaliser (`handleMethodArgumentNotValid`) |
| **`ProblemDetail` (RFC 9457)** | tout le handler | Format d'erreur standard (`type`, `title`, `status`, `detail`, `instance`) + propriété `code` ajoutée |
| **Filtres vs contrôleurs** | `SecurityProblemHandler.java` | Une erreur levée dans un **filtre** (sécurité) se produit avant le contrôleur : `@RestControllerAdvice` ne la voit pas. D'où un handler séparé qui écrit le même format |
| **Traduire une exception technique** | `UserService.createUser` | `DataIntegrityViolationException` (contrainte unique en base) convertie en `UserAlreadyExistsException` : le client reçoit un 409 clair, pas un 500 |
| **Ne pas fuiter d'informations** | | Jamais de stack trace ni de message SQL dans la réponse |

---

## 9. Persistance : JPA, Hibernate, Spring Data, Flyway

### JPA et Hibernate

| Notion | Où | À retenir |
|---|---|---|
| **ORM** | | Correspondance objet ↔ table. JPA est la spécification, Hibernate l'implémentation |
| **`@Entity`** | `Task.java`, `AppUser.java` | Classe liée à une table |
| **`@Id` + `@GeneratedValue(IDENTITY)`** | | Clé primaire générée par la base (`BIGSERIAL`) |
| **`@Column`** | | Nom, longueur, nullabilité. Doit correspondre à la migration SQL, vérifié par `ddl-auto=validate` |
| **`@Enumerated(EnumType.STRING)`** | | Stocke `"DONE"` et non `3`. Toujours STRING : l'ordinal casse si on réordonne l'enum |
| **Constructeur protégé sans argument** | `protected Task()` | Exigé par JPA, protégé pour que le code métier ne l'utilise pas |
| **Relation `@ManyToOne`** | `Task.assignee` | Plusieurs tâches pour un utilisateur. `@JoinColumn` nomme la clé étrangère |
| **Chargement `LAZY` / `EAGER`** | `fetch = FetchType.LAZY` | LAZY : la relation n'est chargée que si on y accède. Défaut recommandé |
| **Problème N+1** | `TaskRepository.findAll` + `@EntityGraph` | 1 requête pour les tâches + N requêtes pour leurs assignés. `@EntityGraph` charge tout en une jointure. Les tests comptent les requêtes grâce aux statistiques Hibernate |
| **Callbacks de cycle de vie** | `@PreUpdate`, `@CreationTimestamp` | Code exécuté automatiquement avant un INSERT/UPDATE |
| **`@OnDelete(SET_NULL)`** | `Task.assignee` | Reflète le `ON DELETE SET NULL` de la base |
| **Contexte de persistance / dirty checking** | `TaskService.updateTask` | Une entité chargée dans une transaction est "suivie" : la modifier suffit, Hibernate fait l'UPDATE à la fin. Pas besoin d'appeler `save` |
| **`save` vs `saveAndFlush`** | `UserService.createUser` | `flush` envoie le SQL immédiatement, ce qui fait remonter l'erreur de contrainte unique **dans** le `try/catch` |

### Spring Data JPA

| Notion | Où | À retenir |
|---|---|---|
| **`JpaRepository<Entity, Id>`** | `TaskRepository`, `UserRepository` | Interface sans implémentation : Spring la génère (`findAll`, `findById`, `save`, `delete`...) |
| **Méthodes dérivées** | `existsByUsernameIgnoreCase`, `existsByEmail` | Le nom de la méthode **est** la requête |
| **`@Query`** ⏳ | | Requête JPQL ou SQL écrite à la main quand le nom deviendrait illisible |
| **`Optional`** | `findById(...).orElseThrow(...)` | Force à traiter le cas "introuvable" |
| **`Sort`** | `Sort.by("lastname", "firstname")` | Tri sans écrire de SQL. Toujours trier une liste renvoyée au client, sinon l'ordre est imprévisible |
| **`Pageable`** ⏳ | | Pagination : indispensable dès que la table grossit |

### Base de données et migrations

| Notion | Où | À retenir |
|---|---|---|
| **DataSource / pool de connexions** | `spring.datasource.*` | Spring Boot configure HikariCP : un ensemble de connexions réutilisées |
| **Flyway** | `resources/db/migration/V1__...sql`, `V2__...sql` | Le schéma évolue par scripts versionnés, appliqués dans l'ordre au démarrage. **Ne jamais modifier une migration déjà appliquée** : en créer une nouvelle |
| **Table d'historique** | `flyway_schema_history` | Flyway y note ce qui a été appliqué (avec un checksum) |
| **`ddl-auto=validate`** | `application.properties` | Hibernate vérifie mais ne touche jamais au schéma : c'est Flyway qui décide |
| **Contraintes SQL** | `CHECK`, `REFERENCES`, `UNIQUE`, index sur `upper(username)` | Dernière ligne de défense, même contre un bug applicatif |
| **`COMMENT ON COLUMN`** | `V2__create_task_table.sql` | Documentation dans la base elle-même |

---

## 10. Transactions

| Notion | Où | À retenir |
|---|---|---|
| **Transaction** | | Un ensemble d'opérations qui réussissent toutes ou échouent toutes (ACID) |
| **`@Transactional`** | `TaskService`, `UserService`, `AuthService` | Ouvre la transaction en entrée de méthode, valide en sortie, annule si une `RuntimeException` sort |
| **`readOnly = true` sur la classe** | `@Transactional(readOnly = true)` sur `TaskService` | Par défaut lecture seule (optimisation), et `@Transactional` simple sur les méthodes qui écrivent |
| **Frontière de transaction = service** | | Ni le contrôleur ni le repository ne la décident |
| **Propagation** | `AuthService.register` → `UserService.createUser` | La méthode appelée rejoint la transaction existante (défaut `REQUIRED`) |
| **Auto-invocation** | | `this.autreMethode()` ne passe pas par le proxy : son `@Transactional` est ignoré |
| **Open Session In View** | `spring.jpa.open-in-view=false` | Désactivé : la session Hibernate est fermée après le service. Un accès LAZY dans le contrôleur échoue (`LazyInitializationException`), ce qui force à tout charger dans le service. Plus prévisible |

---

## 11. Sécurité

| Notion | Où | À retenir |
|---|---|---|
| **Chaîne de filtres** | `SecurityConfig.filterChain` | Spring Security est une série de filtres exécutés **avant** les contrôleurs |
| **Authentification vs autorisation** | | Qui es-tu ? (401 si inconnu) / As-tu le droit ? (403 si refusé) |
| **`authorizeHttpRequests`** | aujourd'hui `permitAll()` partout | Règles d'accès par URL. La première règle qui correspond s'applique |
| **Hash de mot de passe** | `BCryptPasswordEncoder` | On ne stocke jamais un mot de passe, seulement son hash salé. On vérifie avec `matches`, jamais en comparant des hash |
| **Limite BCrypt de 72 octets** | `@MaxBytes(72)` | Au-delà, BCrypt tronque ou refuse : à valider en entrée |
| **CSRF** | `csrf.disable()` | Protection utile avec des cookies de session ; inutile avec un token Bearer |
| **Stateless / JWT** | voir `.claude/fiches/14-jwt.md` | Aucune session serveur, l'identité voyage dans un token signé |
| **Rôles** | `user/enums/UserRole.java` | `hasRole("ADMIN")` cherche l'autorité `ROLE_ADMIN` |
| **Données sensibles** | `RegisterRequestDto.toString()` masque le mot de passe ; `UserResponseDto` n'a pas de `password` | Ne jamais logguer ni renvoyer un secret |
| **Mass assignment** | test `shouldIgnoreRoleSentInBody` | Le client ne doit pas pouvoir s'attribuer un rôle en ajoutant un champ au JSON : le DTO ne l'expose pas |
| **Énumération d'utilisateurs** | | Même message d'erreur pour "email inconnu" et "mauvais mot de passe" |
| **CORS** ⏳ | | Le dossier `client/` (front séparé) appellera l'API depuis une autre origine : le navigateur bloquera tant que CORS n'est pas configuré |

---

## 12. Tests

| Notion | Où | À retenir |
|---|---|---|
| **Pyramide des tests** | | Beaucoup de tests unitaires rapides, moins de tests d'intégration, peu de tests de bout en bout |
| **Test d'intégration** | `test/IntegrationTest.java` | `@SpringBootTest(RANDOM_PORT)` démarre toute l'application sur un port libre |
| **Testcontainers** | `test/TestcontainersConfiguration.java` | Lance un vrai PostgreSQL dans Docker : on teste contre la même base qu'en production, pas contre H2 |
| **`@ServiceConnection`** | | Spring Boot lit l'URL et les identifiants du conteneur tout seul |
| **Cache du contexte Spring** | Javadoc de `IntegrationTest` | Même configuration = contexte réutilisé = tests rapides. Toute annotation qui modifie le contexte va dans la classe mère |
| **RestAssured** | `AuthControllerTest` | Appels HTTP réels lisibles : `given().when().then()` |
| **Mockito, spy** | `@MockitoSpyBean(types = UserRepository.class)` | Un spy se comporte comme le vrai bean sauf quand on le "stubbe" (`doThrow`) : utile pour simuler une erreur rare |
| **Isolation** | `@AfterEach cleanDatabase` | Chaque test part d'une base vide : aucun test ne dépend d'un autre ni de l'ordre |
| **Tests paramétrés** | `@ParameterizedTest @ValueSource` | Un test, plusieurs jeux de données |
| **Compter les requêtes SQL** | `hibernate.generate_statistics=true` | Détecter un N+1 par un test |
| **Contrôleur de test** | `test/.../SecurityErrorsTestController.java` | Un contrôleur présent seulement dans les tests, pour provoquer une erreur précise |
| **Assertions** | AssertJ (`assertThat`), Hamcrest (`equalTo` dans RestAssured) | Deux styles, même but |
| **Tests "slice"** ⏳ | `@WebMvcTest`, `@DataJpaTest` | Ne démarrent qu'une couche : plus rapides que `@SpringBootTest` |
| **Tests unitaires purs** ⏳ | | Un service testé avec des mocks, sans Spring : millisecondes |
| **Pas de dépendance au temps** | | Pas de `sleep`, pas de "maintenant" non maîtrisé. Injecter un `Clock` si une règle dépend de la date |

---

## 13. Java moderne et qualité de code

| Notion | Où | À retenir |
|---|---|---|
| **Records** | tous les DTO | Classe immuable concise : constructeur, accesseurs, `equals`, `hashCode`, `toString` générés. Idéal pour un DTO, **pas** pour une entité JPA |
| **Pattern matching `instanceof`** | `exception.getCause() instanceof ConstraintViolationException cve` | Test + cast en une fois |
| **Streams** | `.stream().map(...).toList()` | Transformer une collection sans boucle |
| **Références de méthode** | `TaskMapper::toTaskResponseDto`, `task::setTitle` | Lambda raccourcie |
| **`Optional`** | repositories, `JsonNullable` | Rendre l'absence explicite |
| **Nullabilité jspecify** | `@NonNull`, `@Nullable` | Documente et permet à l'IDE de détecter les `NullPointerException` |
| **`Locale.ROOT`** | `toLowerCase(Locale.ROOT)` | Sans locale, `"I".toLowerCase()` diffère en turc. Toujours préciser pour du texte technique |
| **Normalisation** | `trim()` + minuscules sur l'email | Comparer des données sous une forme unique |
| **Javadoc** | services | Décrit le contrat : paramètres, retour, exceptions levées |
| **Classe utilitaire** | `TaskMapper` (constructeur privé, méthodes statiques) | Non instanciable |
| **Immutabilité** | champs `final` | Plus simple à raisonner, sûr en multithread (les beans sont partagés) |

---

## 14. Environnement, outillage, infra

| Notion | Où | À retenir |
|---|---|---|
| **Docker / docker-compose** | `docker-compose.yml` | Lance PostgreSQL en local sans l'installer. `docker compose up -d` |
| **Override compose** | `docker-compose.override.yml` (+ `.example`) | Réglages locaux (ports...) fusionnés automatiquement, non commités |
| **`.env`** | racine | Variables lues par compose. Jamais commité, documenté par `.env.example` |
| **Volume** | `postgres_data` | Les données survivent à l'arrêt du conteneur |
| **Spring DevTools** | `spring-boot-devtools` | Redémarrage automatique à la recompilation, en dev seulement |
| **`.gitignore`** | racine | Ce qui ne doit pas être versionné (secrets, `target/`, `node_modules/`) |
| **Conventional commits** | `feat(auth): ...`, `chore(server): ...` | Type + périmètre + description : historique lisible, changelog automatisable |
| **README / JOURNAL** | racine | Comment démarrer / ce qui a été décidé et pourquoi |
| **Monorepo** | `server/` + `client/` | Back et front dans le même dépôt |

---

## 15. Notions à venir

Absentes du projet aujourd'hui, mais présentes dans presque tous les projets Spring Boot professionnels.

| Notion | Pourquoi |
|---|---|
| **Logging (SLF4J + Logback)** | Comprendre ce qui se passe en production. Niveaux `ERROR` / `WARN` / `INFO` / `DEBUG`, configurables par package |
| **Spring Boot Actuator** | Endpoints `/actuator/health`, métriques : la supervision et l'orchestrateur savent si l'application va bien |
| **Documentation OpenAPI** (springdoc) | Contrat d'API généré, page Swagger pour tester |
| **Pagination et filtres** | `Pageable`, `@RequestParam` |
| **CORS** | Pour que le front `client/` puisse appeler l'API |
| **Profils multiples** | `local`, `test`, `prod` avec des configurations distinctes |
| **Verrouillage optimiste** (`@Version`) | Deux utilisateurs modifient la même tâche : le second reçoit un conflit au lieu d'écraser |
| **Audit** (`@CreatedBy`, `@LastModifiedBy`) | Savoir qui a créé ou modifié une ligne, une fois l'utilisateur connecté connu |
| **Cache** (`@Cacheable`) | Éviter de recalculer ou relire une donnée stable |
| **Tâches asynchrones et planifiées** (`@Async`, `@Scheduled`) | Envoi d'email, purge nocturne |
| **Événements Spring** (`ApplicationEventPublisher`) | Découpler deux modules : "une tâche est terminée" sans que le service tâche connaisse le service notification |
| **Internationalisation** (`MessageSource`) | Messages selon la langue du client (ici déjà préparé via les codes `task.title.required`) |
| **Image Docker de l'application** | `./mvnw spring-boot:build-image`, déploiement |
| **Intégration continue** | Tests lancés à chaque commit |
| **Observabilité** | Traces, métriques, logs corrélés (Micrometer) |
| **GraalVM native image** | Démarrage en millisecondes, contraintes sur la réflexion |

---

## 16. Au-delà de Spring

Notions générales que ce projet mobilise, utiles quel que soit le langage ou le framework.

| Domaine | Notions |
|---|---|
| **HTTP** | Méthodes, codes de statut, en-têtes (`Content-Type`, `Authorization`), corps, sans état |
| **API REST** | Ressources, idempotence, versionnement, contrat stable avec le client |
| **SQL et bases relationnelles** | Clés primaires et étrangères, contraintes, index, jointures, transactions ACID, niveaux d'isolation |
| **Conception** | Séparation des responsabilités, principes SOLID (surtout responsabilité unique et inversion de dépendances), couplage faible |
| **Architecture** | En couches (ce projet), hexagonale / clean architecture (le métier ne dépend pas du framework), monolithe vs microservices |
| **Sécurité applicative** | OWASP Top 10 : injection, authentification cassée, exposition de données, contrôle d'accès |
| **Cryptographie appliquée** | Hash vs chiffrement vs signature, sel, clé symétrique vs asymétrique |
| **Tests** | Unitaire / intégration / bout en bout, doublures (mock, stub, spy), isolation, déterminisme |
| **Configuration** | Principes "12-factor" : config dans l'environnement, une seule base de code pour tous les environnements |
| **Conteneurs** | Image, conteneur, volume, réseau, orchestration |
| **Git** | Branches, commits atomiques, messages explicites, revue de code |

---

## 17. Grille d'auto-évaluation

Pour savoir où tu en es. Coche quand tu sais **expliquer** la notion à quelqu'un, pas seulement l'utiliser.

**Fondations**
- [ ] Je sais redessiner le trajet d'une requête (§1)
- [ ] Je sais ce qu'est un bean et pourquoi on injecte par constructeur
- [ ] Je sais pourquoi la plupart des dépendances du pom n'ont pas de version
- [ ] Je sais quelle source de configuration l'emporte sur une autre

**Couches et API**
- [ ] Je sais dire ce qui va dans un controller, un service, un repository
- [ ] Je sais pourquoi on ne renvoie pas une entité au client
- [ ] Je sais expliquer la différence PUT / PATCH et le rôle de `JsonNullable`
- [ ] Je sais choisir le bon code de statut HTTP

**Données**
- [ ] Je sais ce qu'est le problème N+1 et comment `@EntityGraph` le règle
- [ ] Je sais pourquoi `updateTask` n'appelle pas `save`
- [ ] Je sais pourquoi on ne modifie jamais une migration Flyway appliquée
- [ ] Je sais ce que change `open-in-view=false`

**Erreurs, sécurité, tests**
- [ ] Je sais pourquoi une erreur de sécurité n'arrive pas dans `GlobalExceptionHandler`
- [ ] Je sais la différence entre 401 et 403
- [ ] Je sais pourquoi les tests partagent le même contexte Spring et le même conteneur
- [ ] Je sais écrire un test qui ne dépend ni de l'ordre ni du temps
