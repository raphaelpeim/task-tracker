# task-tracker : contexte, objectifs et avancement

*Document de référence pour un agent (IA ou humain) qui reprend le projet. État au 18 novembre 2026 ; code de référence : commit `81b3820` (JWT itération 1), suivi des fiches (`2d3e261`) et de ce document. Il complète `JOURNAL.md` (décisions datées), `README.md` (lancement) et les fiches pédagogiques de `.claude/fiches/` (section 14). `00-STRUCTURE.md` (carte du code), cité dans les versions précédentes, n'est pas présent dans le dépôt.*

## 1. En bref

- **Quoi** : un task tracker (board Kanban) avec API REST Spring Boot 4 / PostgreSQL et front React, déployé à la main sur un VPS.
- **Pourquoi** : c'est un projet d'apprentissage délibéré. Son auteur est développeur full-stack (5 ans) et veut progresser vers un rôle de tech lead, en restant maître de ses choix à l'ère des agents IA.
- **Où on en est** : backend avec CRUD des tâches, utilisateurs, inscription, format d'erreur RFC 9457 et login JWT. L'API est protégée : seules les routes `POST /api/auth/register` et `POST /api/auth/login` sont publiques.
- **Terminé le 18/11** : itération 1 de l'authentification JWT (support natif de Spring Security, HS256), commitée. Prochaine étape à choisir avec l'auteur (section 12).
- **Tests** : 108 tests, tous verts (vérifié le 18/11, section 10).
- **Pas commencé** : front React, temps réel (WebSocket), conteneurisation de production, VPS, CI/CD.
- **Règle d'or pour un agent** : l'auteur code, l'agent guide et relit. Expliquer le pourquoi, laisser l'auteur proposer d'abord, et vérifier toute affirmation sur Spring Boot 4 avant de la donner (sections 4 et 9).

## 2. Point de départ

L'auteur est développeur full-stack depuis 5 ans (React, Spring Boot, Symfony). Constat de départ :

- Il progresse moins : après 5 ans, le plafond n'est plus la syntaxe mais le jugement (architecture, compromis, dette technique).
- Les agents IA écrivent une part croissante du code ; il code moins et risque de perdre le réflexe de comprendre en profondeur.
- Il a du mal à justifier certains choix techniques, surtout ceux proposés par l'IA.
- On lui a reproché de mal connaître des notions système comme SSH : ses missions ne l'y amènent pas (manque d'exposition, pas d'incapacité).

Il veut devenir tech lead à terme, mais surtout se sentir capable : savoir faire, avoir de l'intuition, travailler efficacement, anticiper, être fiable et rigoureux. Hypothèse de travail retenue : l'intuition vient des erreurs comprises et corrigées, donc il faut garder la friction utile là où elle forme (décisions structurantes, sujets nouveaux, infra) et ne pas la déléguer à l'IA.

Outils de diagnostic évoqués : roadmap.sh, entretien technique inversé mené par l'IA, retour de l'équipe, journal de friction (notes brutes dans Keep, tri ensuite dans Notion).

## 3. Objectifs

**Professionnels** : monter en jugement technique (architecture, revue de code, compromis), élargir la vision transverse (infra, réseau, sécurité, données), savoir intégrer l'IA dans un workflow sans en dépendre.

**Apprentissage ciblé** (les trous identifiés) : SSH, Linux, réseau (pare-feu, reverse proxy, HTTPS), conteneurs, CI/CD, observabilité, ainsi que la sécurité applicative et les tests d'intégration.

**Produit** : un task tracker où l'on crée une carte directement dans n'importe quelle colonne, avec mise à jour en direct entre utilisateurs, authentification et rôles.

**Choix de stack, avec leur raison** : garder Spring Boot et React (déjà maîtrisés) pour creuser en profondeur plutôt que changer de langage ; mettre l'effort sur l'infra faite à la main (VPS nu, pas de PaaS en un clic) ; privilégier des technologies recherchées (WebSocket, Testcontainers, TypeScript strict, TanStack Query, CI/CD).

## 4. Comment un agent doit collaborer

1. **L'auteur écrit le code.** Cycle habituel : l'agent donne une consigne et des questions de conception, l'auteur propose sa version, l'agent relit (bloquants, points à trancher, détails). Ne pas livrer une solution complète sauf demande explicite.
2. **Toujours le pourquoi** : alternatives, compromis, ce qui est une règle objective et ce qui est une préférence. Signaler les erreurs sans complaisance, y compris sur du code qui fonctionne.
3. **Vérifier avant d'affirmer.** Spring Boot 4, Spring Security 7, Jackson 3 et Testcontainers 2 sont récents et cassent des habitudes de Spring Boot 3. Plusieurs erreurs passées de l'assistant venaient d'un réflexe ancien (section 9). Rechercher la documentation, les notes de version, mvnrepository ; dire quand une information n'a pas pu être vérifiée.
4. **Zones sans IA** : sur l'infra (SSH, ufw, Nginx), l'auteur lit la documentation officielle et essaie d'abord ; l'IA n'intervient qu'après 20 à 30 minutes de blocage, en expliquant plutôt qu'en faisant.
5. **Comprendre les outils** : l'auteur demande à comprendre les fichiers générés et les chaînes d'outils (Maven, JVM, npm, Vite, tsconfig). Répondre de façon exhaustive mais concise, avec le rôle de chaque pièce.
6. **Journal** : chaque décision ou piège notable va dans `JOURNAL.md`.
7. **Préférences d'architecture** : controllers limités à HTTP ; mapping délégué à des mappers ; chaque repository n'est utilisé que par son service ; les services peuvent s'appeler entre eux tant qu'il n'y a pas de cycle ; indentation par tabulations ; code et commentaires en anglais ; documentation et échanges en français (tutoiement).

## 5. Roadmap et avancement

| Brique | Statut |
| --- | --- |
| Mono-repo `client/` + `server/`, Vite/React/TS, Spring Boot | Fait |
| Backend : PostgreSQL (Docker), Flyway, entité Task, API CRUD | Fait |
| Utilisateurs, inscription, format d'erreur RFC 9457, relation tâche-utilisateur (ajoutés en cours de route) | Fait |
| Tests d'intégration (Testcontainers, REST Assured) | Fait côté backend (108 tests, tous verts au 18/11) |
| Authentification JWT (ajoutée en cours de route) | Itération 1 faite (18/11) ; itération 2 (refresh token) à faire |
| Fiches pédagogiques Spring Boot (`.claude/fiches/`) | Fait (15 fiches et un inventaire des notions) |
| Frontend (login, board Kanban, TanStack Query) | À faire |
| Temps réel (WebSocket/STOMP) | À faire |
| Conteneurisation, VPS (SSH par clé, ufw, fail2ban) | À faire |
| Nginx, HTTPS, CI/CD GitHub Actions, monitoring | À faire |

## 6. Stack et décisions techniques

| Sujet | Choix | Raison |
| --- | --- | --- |
| Langage / framework | Java 25, Spring Boot 4.1.1, Maven (wrapper) | LTS récente ; Maven : cycle de vie fixe, très répandu |
| Base de données | PostgreSQL 16 (Docker en local) | Robuste, standard |
| Schéma | Flyway, `ddl-auto=validate` | Schéma versionné ; Hibernate vérifie seulement la correspondance |
| Enums en base | `VARCHAR` + `CHECK`, valeurs en majuscules (= noms des enums Java), `EnumType.STRING` | ENUM natif Postgres pénible à faire évoluer |
| Identifiants | `BIGSERIAL` côté SQL, `Long` + `IDENTITY` côté JPA | Types cohérents, sinon la validation du schéma échoue |
| Tables | `task` et `app_user` (singulier) | `user` est un mot réservé PostgreSQL ; `User` entrerait en collision avec la classe de Spring Security |
| JSON | Jackson 3 (`tools.jackson`) | Imposé par Spring Boot 4 |
| PATCH partiel | `JsonNullable` (`jackson-databind-nullable` 0.2.11) | Distingue champ absent, champ à null et champ avec valeur |
| Sessions JPA | `open-in-view=false`, `@Transactional` sur les services | Évite les requêtes cachées pendant la sérialisation |
| Erreurs | RFC 9457 (`ProblemDetail`) + propriété `code` | Standard, extensible ; `code` = clé de traduction pour le front |
| Tests | Testcontainers + REST Assured, un contexte partagé | Détecter les bugs qui n'existent qu'avec une vraie base |
| JWT | `spring-boot-starter-security-oauth2-resource-server` (Nimbus), HS256, propriétés `app.jwt.*` (`JwtProperties`, record validé) | Support natif, pas de dépendance Jackson 2 (contrairement à `jjwt`) |
| Secrets | `.env` et `application-local.properties` jamais commités ; fichiers `.example` commités | Tout ce qui touche à un environnement exposé vient de variables d'environnement |

## 7. Architecture du code et conventions

Organisation **par domaine** sous `com.personal.task_tracker`, chaque domaine ayant les mêmes sous-packages quand ils sont utiles (`controller`, `dto`, `entity`, `enums`, `exception`, `mapper`, `repository`, `service`, `validator`). Un sous-package est gardé même avec un seul fichier, pour la cohérence entre domaines.

- `commons/` : ce qui est vraiment transverse (`SecurityConfig`, `GlobalExceptionHandler`, `SecurityProblemHandler`, `ValidationMessages.properties`).
- `user/` : entité `AppUser`, `GET /api/users`, `GET /api/users/{id}`, création d'utilisateur (`UserService.createUser`).
- `task/` : entité `Task` (clé étrangère `assignee_id` vers `app_user`, `ON DELETE SET NULL`), CRUD complet sur `/api/tasks`.
- `auth/` : `POST /api/auth/register`, `POST /api/auth/login` ; `auth/config` (`JwtProperties`, `JwtConfig`), `auth/service` (`AuthService`, `TokenService`), `auth/exception` (`InvalidCredentialsException`), `auth/validator` (`MaxBytes`).

Règles :

- Controllers : HTTP uniquement. Services : reçoivent et renvoient des DTO, possèdent les transactions (`readOnly = true` sur la classe, `@Transactional` sur les écritures). Mappers : classes statiques à constructeur privé. DTO : records.
- Les erreurs portent `code` (clé de traduction) et `detail` (phrase anglaise). Les erreurs de validation listent chaque champ dans `errors` (`pointer`, `code`, `detail`). Les messages de contrainte sont des clés entre accolades résolues dans `ValidationMessages.properties`.
- Tout DTO reçu en corps de requête doit être déclaré dans `GlobalExceptionHandler.DOMAINS`, ce que `ValidationMessagesTest` vérifie.
- Les exceptions de domaine (`TaskNotFoundException`, `UserNotFoundException`, `UserAlreadyExistsException`) sont traduites en statut et code dans `GlobalExceptionHandler` ; toute autre exception donne un 500 générique.
- Tests : tous héritent de `IntegrationTest` (un contexte Spring et un conteneur PostgreSQL partagés ; port, nettoyage et statistiques Hibernate centralisés). Chaque requête porte par défaut un token valide ; `sendRequestsWithoutToken()` le retire pour les tests d'authentification.

Évolution notable : au départ, le service ne manipulait que des entités et le mapping DTO/entité se faisait au niveau du controller via `TaskMapper`. La règle actuelle est que les services reçoivent et renvoient des DTO (sauf `UserService.createUser` et `findUserById`, qui renvoient l'entité, car d'autres services en ont besoin).

## 8. Évolution des fonctionnalités

### Phase 1 : cadrage et infra locale (début septembre 2026)

- Mono-repo, Vite + React + TS, Spring Initializr (Maven, Java 25) ; Gradle supprimé pour n'avoir qu'un outil de build.
- Docker Compose pour PostgreSQL (volume nommé, `docker-compose.override.yml` non commité pour le port), profils Spring (`local`), fichiers `.example`.
- Flyway et `ddl-auto=validate`. Premier piège Spring Boot 4 : `flyway-core` seul n'active plus rien, il faut le starter.

### Phase 2 : première API (mi-septembre)

- Entité `Task`, enums préfixés (`TaskStatus`...), migration, DTO en records, Bean Validation, `GlobalExceptionHandler`.
- Décision métier : le statut est choisi à la création (board Kanban). Si des transitions contrôlées existent un jour, elles ne concerneront que les mises à jour.
- PUT = remplacement complet ; PATCH = partiel avec `JsonNullable`. Ce choix a révélé l'incompatibilité Jackson 2 / Jackson 3 (version 0.2.6 de la bibliothèque), résolue en passant à 0.2.11.
- Bugs JPA rencontrés : constructeur sans argument obligatoire, contraintes CHECK en minuscules alors qu'Hibernate envoie les noms en majuscules.

### Phase 3 : réorganisation par domaine (fin septembre)

- Passage de couches techniques à plat vers `task/...` et `commons/...`. Raison : cohérence par domaine, préparation de `user/` et `auth/`.
- Sécurité volontairement désactivée (`permitAll()`) le temps de finir le CRUD, documentée comme dette temporaire.

### Phase 4 : tests d'intégration (fin septembre)

- Testcontainers 2.0 avec `@ServiceConnection`, REST Assured, `@LocalServerPort`. Scénarios : création/lecture, 404, 400, PATCH (trois états), DELETE.
- Leçons : ne jamais coder un id en dur dans un test, nettoyer la base entre les tests, ne pas dépendre de la locale de la machine.

### Phase 5 : utilisateurs et inscription (octobre)

- `AppUser` / `app_user` ; e-mail unique et normalisé (minuscules) ; username unique sans tenir compte de la casse (index sur `upper(username)`) ; rôles `ADMIN`, `USER`, `GUEST`.
- Le rôle n'est jamais fourni par le client : forcé à `USER` à l'inscription ; un test envoie `role: ADMIN` en JSON brut pour verrouiller ce point.
- Mot de passe : haché côté serveur uniquement (BCrypt), jamais côté front (le hash deviendrait le mot de passe ; seul HTTPS protège le transport). Limite de 72 octets de BCrypt gérée par une contrainte `@MaxBytes` (elle compte les octets, pas les caractères). Le `toString()` des DTO masque le mot de passe.
- Doublons : pré-contrôle lisible, puis rattrapage de la violation de contrainte en base (inscriptions concurrentes) : 409.

### Phase 6 : format d'erreur standard

- RFC 9457 avec `code` pour le front ; validation : une erreur par champ, avec priorité entre contraintes ; valeurs JSON non convertibles : `<domaine>.<champ>.invalidFormat`.
- Messages externalisés dans `ValidationMessages.properties`, gardés par `ValidationMessagesTest`. La validation du PATCH passe par Bean Validation (le validateur maison a été supprimé).

### Phase 7 : relation tâche-utilisateur

- `assignee` devient une vraie relation (`assignee_id`, `ON DELETE SET NULL`) ; DTO avec `assigneeId`.
- `GET /api/tasks` trié par id et chargé avec un `@EntityGraph` (évite une requête par assigné) ; tri des utilisateurs délégué à la base. Tests pour ces deux comportements.

### Phase 8 : durcissement

- Erreurs de sécurité : `AuthenticationException` donne 401 et `AccessDeniedException` donne 403 dans le handler global ; `SecurityProblemHandler` couvre ceux produits par les filtres (qui n'atteignent jamais le handler global), avec le même format.
- Refactor des tests (`IntegrationTest` centralise port et nettoyage ; suppression de `Locale.setDefault`), nettoyage du `pom.xml`, `.env.example`, README.
- Surcharges de versions pour des vulnérabilités : `jackson-bom.version` 3.1.7 et `postgresql.version` 42.7.14 (à retirer quand Spring Boot les embarquera). Logback laissé en 1.5.38 par décision.

### Phase 9 : fiches pédagogiques (28 octobre au 2 novembre)

- Rédaction de `.claude/fiches/` : 14 fiches (Maven, beans, configuration, couches, REST, validation, exceptions, persistance, transactions, sécurité, tests, Java moderne, outillage, JWT) et `notions-spring-boot.md` (inventaire et grille d'auto-évaluation). Chaque fiche part du code réel du projet.
- 18/11 : fiche 15, le trajet de l'authentification (qui est appelé, quand et pourquoi : démarrage, login, requête protégée, erreurs).

### Phase 10 : JWT, itération 1 (28 octobre au 18 novembre)

- 28/10 : starter resource server, `LoginRequestDto`, ébauche de `AuthService.login` et de `UserService`.
- 29/10 : `JwtProperties` (`app.jwt.secret`, `issuer`, `expiration`), `@ConfigurationPropertiesScan`, `JwtConfig` (encoder et decoder HS256, issuer validé), clés dans `application.properties` et `application-local.properties.example`.
- 02/11 : `LoginResponseDto` (`accessToken`, `tokenType`, `expiresIn`).
- 03/11 : `TokenService.generateToken`, `AuthController.login`, `UserService.login` et `UserRepository.findByEmail`. Le renommage `getTaskById` en `getTask` et `getUserById` en `getUser` et le réordonnancement des méthodes (création en premier) ont été intégrés le 18/11 aux commits qui ont créé ces fichiers.
- 12/11 : `SecurityConfig` (routes d'auth publiques, reste authentifié, stateless, resource server branché sur `SecurityProblemHandler`) ; `LoginRequestDto` en `@NotBlank` + `@MaxBytes(72)`, clés `auth.*`, déclaré dans `DOMAINS` ; `InvalidCredentialsException` et son handler ; hachage déplacé dans `AuthService.register` (`createUser` reçoit un `passwordHash`) ; `IntegrationTest` (token par défaut, clé aléatoire), `JwtAuthenticationTest`, tests de login. Décisions du jour consignées dans `JOURNAL.md`.
- 18/11 : décisions du 12/11 appliquées (plus de rôle dans le token ni de converter, comparaison des mots de passe dans `AuthService` avec hash factice, `InvalidCredentialsException` levée) ; questions ouvertes tranchées (longueur du secret dans `JwtConfig`, `WWW-Authenticate`, handlers déclarés une fois, commentaire CSRF) ; 5 tests ajoutés (`JwtConfigTest`, challenge `WWW-Authenticate`) ; historique réécrit (section 10).

Détail : section 11.

## 9. Pièges de Spring Boot 4 et leçons

| Symptôme | Cause | Correction |
| --- | --- | --- |
| Flyway ne migre rien | Plus d'auto-configuration avec la seule bibliothèque | `spring-boot-starter-flyway` |
| `starter-web` introuvable | Renommé | `spring-boot-starter-webmvc` et starters `-test` par module |
| `JsonNullable` non désérialisé | Spring Boot 4 utilise Jackson 3 ; l'ancienne version de la bibliothèque n'avait que le module Jackson 2 | `jackson-databind-nullable` 0.2.11 (module `JsonNullableJackson3Module`, découvert automatiquement) |
| Version de Testcontainers introuvable | Testcontainers 2.0 : artefacts préfixés `testcontainers-...`, classes déplacées, `PostgreSQLContainer` non générique | `testcontainers-junit-jupiter`, `testcontainers-postgresql`, import `org.testcontainers.postgresql` |
| `@ServiceConnection` introuvable | Il vient du module Spring Boot, pas de Testcontainers | `spring-boot-testcontainers` |
| Starter OAuth2 resource server | Ancien nom déprécié | `spring-boot-starter-security-oauth2-resource-server` |
| `jjwt` non retenu | Sa couche JSON repose sur Jackson 2 | Support natif Spring Security (Nimbus) |
| `rest-assured.version` absente du BOM | Non gérée par Spring Boot | Version explicite dans le `pom.xml` |
| Erreur de schéma au démarrage | Types ou longueurs différents entre SQL et entité | Les aligner ; ne jamais modifier une migration déjà appliquée, en créer une nouvelle |
| Test qui échoue selon la machine | Messages dépendants de la locale | Messages par clés ; ne pas modifier l'état global de la JVM |

Leçon générale : la présence d'une bibliothèque sur le classpath ne veut pas dire activation, et inversement ; chaque mécanisme d'auto-configuration a ses règles. En cas de doute, lire les logs de démarrage et le rapport de conditions.

## 10. État actuel

- **API** : `POST /api/auth/register`, `POST /api/auth/login` (publics) ; `GET /api/users`, `GET /api/users/{id}` ; `GET`, `POST`, `PUT`, `PATCH`, `DELETE` sur `/api/tasks`. Tout ce qui n'est pas une route d'auth exige un token valide.
- **Tests** : 108 tests, **tous verts** (vérifié le 18/11). Docker doit tourner ; commande : `./mvnw clean test` depuis `server/`, avec `JAVA_HOME` sur le JDK 25.
- **Secret de test** : clé de 32 octets tirée par `SecureRandom` une fois par exécution, injectée par `@DynamicPropertySource` dans `IntegrationTest` (décision du 12/11, `docs/user_story_status.md`). Aucune préparation nécessaire pour lancer les tests.
- **Dépôt** : 11 commits, un par fonctionnalité. Le 18/11, l'historique a été réécrit à partir de `e185e40` : les retouches cosmétiques (ordre des méthodes, `getTask`/`getUser`, `@PreUpdate` déplacés, `static void main`) ont été intégrées aux commits qui ont créé les fichiers, les 4 commits WIP du JWT et le travail non commité forment `81b3820`, les 3 commits de fiches forment `2d3e261`. `main` local diverge donc d'`origin/main` : la publication demande un push forcé, à faire par l'auteur. Sauvegardes locales : branches `sauvegarde/main-avant-reecriture-2026-11-18` et `sauvegarde/travail-non-commite-2026-11-18`.
- **À jour** : `README.md` ; `JOURNAL.md` pour le JWT (entrée du 28/10-12/11). **En retard** : `JOURNAL.md` ne mentionne ni les surcharges de versions ni les fiches. `00-STRUCTURE.md` absent.

## 11. JWT itération 1 (terminée le 18/11)

**Décisions prises** (détail et raisons dans `JOURNAL.md`, entrée du 28/10-12/11)

- Support natif de Spring Security : `spring-boot-starter-security-oauth2-resource-server` (Nimbus), sans `jjwt`.
- HS256 avec un secret en variable d'environnement (`APP_JWT_SECRET`, 32 octets minimum, jamais commité ; l'appli doit refuser de démarrer avec un secret trop court).
- Claims : `sub` = id de l'utilisateur (en chaîne), `iss`, `iat`, `exp`. **Pas de rôle dans le token** (12/11) : il sera relu en base quand une règle d'autorisation en aura besoin.
- **Durée du token : 1 heure, sans refresh token** (12/11, remplace les 15 minutes prévues). À réduire quand le refresh token existera.
- L'issuer est validé par le decoder (`JwtValidators.createDefaultWithIssuer`), sinon le claim ne protège rien.
- **Pas de `Clock` injectée** (12/11) : la durée se vérifie par `exp - iat`, le token expiré est fabriqué avec des dates passées. À reconsidérer quand une règle métier dépendra de l'heure.
- **Mots de passe gérés par `auth` seulement** (12/11) : `AuthService` hache à l'inscription et compare au login après `userService.findUserByEmail` ; `UserService` ne connaît plus le `PasswordEncoder`.
- Login : e-mail normalisé, même réponse 401 avec le code `auth.invalidCredentials` (`InvalidCredentialsException`, qui n'étend pas `AuthenticationException`) que l'e-mail soit inconnu ou le mot de passe faux ; temps égalisé en comparant à un hash factice calculé une fois avec le même `PasswordEncoder`, sans `sleep`. `LoginRequestDto` : `@NotBlank` et `@MaxBytes(72)`.
- Inscription : le 409 « e-mail déjà utilisé » reste (limite acceptée).
- Itération 2 : refresh token opaque, haché en base (migration V3 à créer), avec rotation ; cookie `HttpOnly` ou corps JSON à trancher avec le front.

**Décisions du 18/11** (détail dans `JOURNAL.md`)

- Longueur du secret vérifiée dans `JwtConfig.secretKey()` après décodage Base64 : moins de 32 octets arrête le démarrage, sans jamais afficher le secret.
- `SecurityProblemHandler.commence` délègue l'en-tête `WWW-Authenticate` à `BearerTokenAuthenticationEntryPoint`, puis écrit le `ProblemDetail`. Spring Security 7 y ajoute `resource_metadata` (RFC 9728).
- Handlers déclarés une seule fois, dans `oauth2ResourceServer` ; `.jwt(Customizer.withDefaults())` sans converter (aucune autorité, aucune règle n'en demande).
- CSRF désactivé avec un commentaire : à revoir si un identifiant passe dans un cookie.
- `matches` appelé avant le test d'existence de l'utilisateur (un `&&` court-circuitait BCrypt).
- Convention `@Transactional` confirmée : `readOnly = true` sur la classe, `@Transactional` sur chaque méthode qui écrit.

**Pour relire le code** : `.claude/fiches/15-flux-authentification.md` décrit qui appelle quoi, quand et pourquoi.

## 12. Reste à faire et questions ouvertes

- Refresh token (itération 2) et déconnexion ; `GET /api/users/me`.
- Autorisation par rôle : quel rôle peut quoi (le rôle sera relu en base, décision du 12/11).
- Limitation du nombre de tentatives de login ; pagination et filtre `GET /api/tasks?status=`.
- Frontend : CORS, page de login, stockage du token, rafraîchissement automatique, board Kanban, clés de traduction à partir de `code`.
- Temps réel : WebSocket/STOMP, handshake authentifié par JWT, mise à jour du cache TanStack Query.
- Déploiement : Dockerfiles multi-stage, compose de production avec secrets en variables d'environnement, VPS (clés SSH, ufw, fail2ban), Nginx et HTTPS, CI/CD GitHub Actions, health check et logs structurés, sauvegardes `pg_dump`, veille de dépendances (Dependabot).
- Publier l'historique réécrit le 18/11 (push forcé, par l'auteur), puis supprimer les branches de sauvegarde.
- `resource_metadata` : Spring Security 7 annonce `/.well-known/oauth-protected-resource` dans le `WWW-Authenticate`. Vérifier si cette URL est servie et ce qu'elle expose.
- Compléter `JOURNAL.md` (surcharges de versions, fiches) et retirer les surcharges de versions quand Spring Boot les aura intégrées.

## 13. Démarrage rapide

- Prérequis : JDK 25 (`JAVA_HOME`), Docker, Node.js.
- Base locale : copier `.env.example` en `.env`, copier `docker-compose.override.example.yml` en `docker-compose.override.yml`, puis `docker compose up -d`.
- Serveur : copier `application-local.properties.example` en `application-local.properties`, puis `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` depuis `server/`.
- Tests : `./mvnw clean test` depuis `server/`.
- Fichiers à lire d'abord : `GlobalExceptionHandler`, `IntegrationTest`, `UserService`, `TaskService`, puis `auth/config` et `auth/service` pour le JWT.

## 14. Ressources pédagogiques

`.claude/fiches/` contient les fiches écrites pour l'auteur (index : `README.md`). Elles partent du code réel et finissent par des exercices et un quiz. Pour un agent :

- Quand l'auteur pose une question de fond, renvoyer vers la fiche concernée plutôt que tout réexpliquer, puis compléter ce qu'elle ne couvre pas.
- `14-jwt.md` est le guide pas à pas de l'itération 1 du JWT, `15-flux-authentification.md` décrit le code tel qu'il est au 18/11. Vérifier la cohérence entre ces fiches, la section 11 et le code avant de répondre. Il a été écrit avant les décisions du 12/11 (rôle dans le token, `Clock`, durée) : en cas de désaccord, `JOURNAL.md` fait foi.
- Si le code évolue (renommage, nouvelle classe), les références `fichier:ligne` des fiches deviennent fausses : le signaler.
