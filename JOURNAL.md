# Journal de bord

## [10/09/2026] — Setup initial
- Créé la structure mono-repo
- Backend : Spring Boot init (Web, JPA, Security, PostgreSQL)
- Frontend : Vite + React + TS + TanStack Query
- Décision : mono-repo plutôt que multi-repo pour simplifier la gestion solo

## [15/09/2026] — Infra locale & base de données
- Docker Compose pour PostgreSQL en local (service `postgres`, volume nommé `postgres_data` pour la persistance)
- Séparation `application.properties` (config commune) / `application-local.properties` (datasource local, activé via profil Spring `local`)
- Ajout d'un `application-local.properties.example` committé, pour documenter la structure attendue sans exposer de vrais secrets
- Décision : `ddl-auto=validate` plutôt que `update` — le schéma est géré exclusivement par Flyway, Hibernate ne fait que valider la correspondance

## [15/09/2026] — Migrations Flyway
- Ajout de Flyway pour versionner le schéma (`V1__create_task_table.sql`)
- Piège Spring Boot 4 : `flyway-core` seul ne suffit plus à activer l'auto-configuration — nécessite explicitement `spring-boot-starter-flyway` (changement par rapport à Spring Boot 3)
- Décision : `VARCHAR + CHECK` plutôt qu'ENUM natif Postgres pour `type`/`status`/`priority` — plus simple à faire évoluer (ALTER CONSTRAINT vs ALTER TYPE), plus uniforme côté mapping Hibernate

## [16/09/2026] — Première entité et API REST (CRUD Task)
- Entité `Task` (JPA) avec enums dédiés `TaskType`/`TaskStatus`/`TaskPriority` (préfixés pour éviter les collisions de nom avec de futures entités)
- Bug JPA classique rencontré : ajouter un constructeur avec arguments supprime le constructeur vide généré par défaut — obligatoire pour Hibernate (instanciation par réflexion). Corrigé avec un constructeur `protected Task()`
- Architecture en couches : `entity/`, `dto/`, `repository/`, `service/`, `controller/`, `mapper/`, `exception/`
- Décision d'architecture : le service ne manipule/retourne que `Task` (jamais de DTO), le mapping DTO ↔ entité est isolé dans `TaskMapper`, pas dans le controller ni dans l'entité elle-même. *Revue le 02/10 : le service reçoit et retourne des DTO (voir l'entrée du 02/10).*
- `TaskRepository extends JpaRepository<Task, Long>` — CRUD de base, pas encore de méthode de recherche custom
- `TaskController` avec `GET /tasks`, `GET /tasks/{id}`, `POST /tasks`, validation via Bean Validation (`@NotBlank`, `@NotNull`...) sur `TaskRequestDto`
- Gestion d'erreurs centralisée via `GlobalExceptionHandler` (`@RestControllerAdvice`) : `TaskNotFoundException` → 404, erreurs de validation → 400 avec détail champ par champ
- Décision métier : `status` reste modifiable à la création (cas d'usage board Kanban — création directe dans n'importe quelle colonne), pas de statut par défaut forcé

## [18-21/09/2026] — Réorganisation par domaine & sécurité temporaire
- Réorganisation de l'arborescence : de "couches techniques à plat" vers "package par domaine avec sous-dossiers techniques à l'intérieur" (`task/controller`, `task/dto`, `task/entity`...), plus lisible et prêt à accueillir de futurs domaines (`project`, `user`...)
- `commons/` créé pour ce qui est réellement transverse : `config/` (Security, Jackson), `exception/GlobalExceptionHandler`
- Règle actée : un fichier isolé garde son propre sous-dossier même seul, pour la cohérence entre domaines présents et futurs
- Sécurité désactivée volontairement et temporairement (`SecurityConfig` avec `permitAll()` sur tous les endpoints, CSRF désactivé) — à réactiver proprement à l'étape JWT prévue dans la roadmap
- `GlobalExceptionHandler` enrichi : `IllegalArgumentException` (validation métier custom) et `HttpMessageNotReadableException` (JSON malformé / valeur d'enum invalide) en plus de `TaskNotFoundException` et `MethodArgumentNotValidException`. *Revu en octobre : le handler `IllegalArgumentException` est supprimé, remplacé par `InvalidTaskUpdateException` (voir l'entrée du 05-10/10).*

## [21-23/09/2026] — CRUD complet (PUT, PATCH, DELETE) & PATCH partiel
- `PUT /tasks/{id}` (remplacement complet) et `DELETE /tasks/{id}` (204 No Content) ajoutés
- `PATCH /tasks/{id}` pour mise à jour partielle, avec `TaskRequestPartialDto` utilisant `JsonNullable<T>` (lib `org.openapitools:jackson-databind-nullable`) pour distinguer champ absent / présent à null / présent avec valeur
- `TaskUpdatePartialValidator` : interdit un `null` explicite sur les champs non-nullables (title, type, status, priority), autorise le `null` explicite sur `assignee` (désassignation volontaire)
- Piège Spring Boot 4 : passage à Jackson 3 (`tools.jackson`, plus `com.fasterxml.jackson`) — la version 0.2.6 de `jackson-databind-nullable` (Jackson 2 uniquement) ne fonctionnait pas ; upgrade vers 0.2.11 qui fournit `JsonNullableJackson3Module`, compatible Jackson 3
- Découverte : Spring Boot auto-détecte les modules Jackson via `ServiceLoader` — le `@Bean JsonNullableModule` manuel dans `JacksonConfig` était devenu redondant et a été supprimé
- Décision métier tranchée : `status` reste éditable librement à la création et via PATCH (cas d'usage board Kanban, création directe dans n'importe quelle colonne) — les transitions contrôlées de statut, si besoin un jour, se géreraient uniquement en update, jamais en création

## [23/09/2026] — Tests d'intégration avec Testcontainers
- Ajout de `org.testcontainers:testcontainers-junit-jupiter` et `testcontainers-postgresql` (sans version, gérées par le BOM Spring Boot) + `spring-boot-testcontainers` pour `@ServiceConnection`
- Piège Spring Boot 4 : Testcontainers 2.0 a renommé tous ses artefacts avec le préfixe `testcontainers-` (`org.testcontainers:junit-jupiter` → `testcontainers-junit-jupiter`) et relocalisé les classes de conteneurs dans des packages dédiés (`org.testcontainers.postgresql.PostgreSQLContainer` au lieu de `org.testcontainers.containers.PostgreSQLContainer`) ; `PostgreSQLContainer` n'est aussi plus une classe générique (retrait du `<?>`)
- `@ServiceConnection` préféré à `@DynamicPropertySource` : configuration automatique du datasource vers le conteneur, sans propriétés à mapper à la main
- Flyway s'exécute nativement contre le conteneur de test au démarrage du contexte — chaque run de test part d'un schéma vierge et migré, fidèle à la prod
- Suppression de `TaskTrackerApplicationTests` (généré par défaut par Spring Initializr) — devenu obsolète car sans profil actif ni datasource configuré, il échouait dès que Flyway/JPA sont devenus obligatoires au démarrage
- `TaskControllerTest` créé (package `task/`), conteneur PostgreSQL déclaré et fonctionnel — build vert, premier test métier (POST + GET) à écrire

## [28/09-02/10/2026] Utilisateurs et inscription
- Entité `AppUser` et migration `V1__create_app_user_table.sql` (la table `task` passe en V2) ; rôle en `VARCHAR + CHECK` (`ADMIN`, `USER`, `GUEST`), comme les enums de `Task`
- `POST /api/auth/register` : inscription avec rôle `USER` imposé, mot de passe haché en BCrypt (`PasswordEncoder` déclaré dans `SecurityConfig`), jamais renvoyé dans la réponse ni dans `toString()` du DTO
- Décision d'architecture (remplace celle du 16/09) : le mapping DTO ↔ entité et la validation métier passent dans le service. Le service reçoit et retourne des DTO, le controller ne fait plus que de l'HTTP. Les mappers restent des classes statiques
- Transactions revues : `@Transactional(readOnly = true)` au niveau de la classe de service, `@Transactional` sur les méthodes d'écriture

## [05-10/10/2026] Assignee relié aux utilisateurs, format des erreurs
- `task.assignee` (texte libre) devient `task.assignee_id`, clé étrangère vers `app_user`. En entrée, `assigneeId` (nullable, `null` explicite en PATCH = désassigner) ; en sortie, l'objet `assignee` complet (`assignee.id`, nom, prénom, username, email)
- Décision : un `assigneeId` inexistant renvoie 404 (`UserNotFoundException`), pas 400, par cohérence avec les autres ressources introuvables
- Décision : suppression d'un utilisateur assigné = `ON DELETE SET NULL` (ses tâches deviennent non assignées), porté par la migration et par `@OnDelete(action = OnDeleteAction.SET_NULL)` sur `Task.assignee`. Pas de collection `tasks` sur `AppUser` : les tâches d'un utilisateur se récupèrent par une requête sur `TaskRepository` si besoin
- `GET /api/users` (trié par nom, prénom, username) et `GET /api/users/{id}`, sans mot de passe
- Normalisation à l'inscription : email en minuscules (`Locale.ROOT`) pour le contrôle comme pour l'enregistrement ; username stocké tel que saisi (après `trim()`) mais unique sans tenir compte de la casse, via l'index `app_user_username_upper` sur `upper(username)`
- 409 réservé au doublon d'inscription : contrôle préalable dans `UserService`, puis interception de la `DataIntegrityViolationException` autour de `saveAndFlush` pour le cas concurrent ; le nom de la contrainte violée (lu dans l'exception) choisit le message. Toute autre violation de contrainte remonte en 500
- Toutes les erreurs suivent le RFC 9457 (`ProblemDetail`, `application/problem+json`) : `GlobalExceptionHandler` hérite de `ResponseEntityExceptionHandler` (erreurs Spring MVC, id non numérique...) ; validation : `detail` = "Validation failed" et détail par champ dans `errors`. Les exceptions non gérées passent par un handler `Exception` qui journalise et renvoie un 500 sans `detail`, pour ne rien exposer de l'interne
- `spring.jpa.open-in-view=false` : pas de chargement paresseux pendant la sérialisation, tout ce qui est renvoyé est chargé dans le service
- Tests : un seul conteneur PostgreSQL déclaré en bean (`TestcontainersConfiguration`, importé par chaque classe de test) ; les classes de test de même configuration partagent le contexte Spring et donc le conteneur. `AuthControllerTest` garde son propre contexte à cause de `@MockitoSpyBean`
- Exception à la règle Flyway : V1 et V2 ont été modifiées après coup (index sur `upper(username)`, `ON DELETE SET NULL`, commentaires de colonnes), ce qui est acceptable ici car aucune base n'existe hors du poste de dev. Conséquence : une base locale déjà migrée refuse de démarrer (checksum différent), il faut la recréer avec `docker compose down -v`

## Points de vigilance identifiés
- Toujours ajouter un constructeur vide (`protected`) dès qu'une entité JPA a un constructeur avec arguments
- Ne jamais modifier une migration Flyway déjà appliquée : créer une nouvelle migration corrective. Seule exception tolérée : tant qu'aucune base n'existe hors du poste de dev (voir 05-10/10)
- Bien vérifier la cohérence des types/longueurs entre migration SQL et entité JPA (`ddl-auto=validate` est strict là-dessus)
- Après un renommage de migration, toujours lancer `./mvnw clean test` : `target/` garde l'ancien fichier et Flyway trouve deux migrations de même version
- Spring Boot 4 casse plusieurs habitudes Spring Boot 3 : starters explicites requis (Flyway), renommages d'artefacts (webmvc, Testcontainers 2.0), passage à Jackson 3 — toujours vérifier la compatibilité d'une lib tierce avant de chercher l'erreur ailleurs
