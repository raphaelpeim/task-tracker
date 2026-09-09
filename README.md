# task-tracker
Personal project of a task tracker

Mono-repo : `server/` (API REST Spring Boot 4, PostgreSQL, Flyway) et `client/` (React, Vite, TypeScript).
Les décisions et pièges rencontrés sont consignés dans [`JOURNAL.md`](JOURNAL.md).

## Prérequis

- JDK 25 (`JAVA_HOME` doit pointer dessus : Maven compile avec la version de `JAVA_HOME`, pas celle du `PATH`)
- Docker (base locale et tests d'intégration)
- Node.js et npm (client)

Sous macOS, pour fixer le JDK 25 dans le profil du shell :

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

## Base de données locale

1. Copier `.env.example` en `.env` à la racine et renseigner `POSTGRES_USER` et `POSTGRES_PASSWORD` (non commité).
2. Copier `docker-compose.override.example.yml` en `docker-compose.override.yml` pour exposer le port 5432 (non commité).
3. Démarrer PostgreSQL :

```sh
docker compose up -d
```

Si une migration Flyway déjà appliquée a été modifiée, la base locale refuse de démarrer (checksum différent) : la recréer avec `docker compose down -v` puis `docker compose up -d`.

## Serveur

1. Copier `server/src/main/resources/application-local.properties.example` en `application-local.properties` et le compléter (port, datasource). Ce fichier n'est pas commité.
2. Lancer le serveur avec le profil `local` :

```sh
cd server
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Les migrations Flyway s'appliquent au démarrage.

## Tests

```sh
cd server
./mvnw clean test
```

Les tests d'intégration démarrent un conteneur PostgreSQL via Testcontainers (Docker doit tourner). Le `clean` évite qu'une migration renommée reste dans `target/` sous son ancien nom.

## Client

```sh
cd client
npm install
npm run dev
```

## API

Erreurs au format RFC 9457 (`application/problem+json`).

| Méthode | Chemin | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Inscription |
| `GET` | `/api/users` | Liste des utilisateurs |
| `GET` | `/api/users/{id}` | Un utilisateur |
| `GET` | `/api/tasks` | Liste des tâches |
| `GET` | `/api/tasks/{id}` | Une tâche |
| `POST` | `/api/tasks` | Création |
| `PUT` | `/api/tasks/{id}` | Remplacement complet |
| `PATCH` | `/api/tasks/{id}` | Modification partielle |
| `DELETE` | `/api/tasks/{id}` | Suppression |

L'authentification n'est pas encore en place : tous les endpoints sont ouverts.