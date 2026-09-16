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
- Décision d'architecture : le service ne manipule/retourne que `Task` (jamais de DTO) — le mapping DTO ↔ entité est isolé dans `TaskMapper`, pas dans le controller ni dans l'entité elle-même
- `TaskRepository extends JpaRepository<Task, Long>` — CRUD de base, pas encore de méthode de recherche custom
- `TaskController` avec `GET /tasks`, `GET /tasks/{id}`, `POST /tasks`, validation via Bean Validation (`@NotBlank`, `@NotNull`...) sur `CreateTaskDto`
- Gestion d'erreurs centralisée via `GlobalExceptionHandler` (`@RestControllerAdvice`) : `TaskNotFoundException` → 404, erreurs de validation → 400 avec détail champ par champ
- Décision métier : `status` reste modifiable à la création (cas d'usage board Kanban — création directe dans n'importe quelle colonne), pas de statut par défaut forcé

## Points de vigilance identifiés
- Toujours ajouter un constructeur vide (`protected`) dès qu'une entité JPA a un constructeur avec arguments
- Ne jamais modifier une migration Flyway déjà appliquée — créer une nouvelle migration corrective
- Bien vérifier la cohérence des types/longueurs entre migration SQL et entité JPA (`ddl-auto=validate` est strict là-dessus)
