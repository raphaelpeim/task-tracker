# Suivi des user stories

## Revue du back (2026-10-14) : détails mineurs

### Ajout d'un `.env.example` à la racine

- **Blocage** : le fichier doit documenter `POSTGRES_USER` et `POSTGRES_PASSWORD`, exigés par `docker-compose.yml`. Un hook de la session refuse toute commande qui touche un fichier nommé `.env.example`, car son nom correspond à un fichier de secrets.
- **Tentative** : création du fichier avec les deux clés sans valeur. La commande a été refusée par le hook avant exécution.
- **Options** :
  1. Créer le fichier soi-même, avec le contenu `POSTGRES_USER=` et `POSTGRES_PASSWORD=` (une clé par ligne, sans valeur), puis mettre à jour l'étape 1 du README.
  2. Ne pas créer de fichier et laisser le README documenter les deux variables (c'est déjà le cas).
- **Décision (2026-10-14)** : option 1, fichier créé par l'utilisateur, README mis à jour.
- **Statut** : **DONE**

## JWT itération 1 (2026-11-12)

### Secret JWT pour les tests

- **Blocage** : le contexte Spring des tests ne démarre pas sans `app.jwt.secret` (`@NotBlank` sur `JwtProperties`). Un hook de la session refuse d'écrire une valeur de secret en clair dans le code de test, même une valeur réservée aux tests.
- **Tentative** : propriété `app.jwt.secret` (Base64 d'une chaîne de test de 41 octets) dans les `properties` de `@SpringBootTest` de `IntegrationTest`. Écriture refusée par le hook. Vérification faite à la place en passant une clé jetable par variable d'environnement : `APP_JWT_SECRET=$(openssl rand -base64 32) ./mvnw clean test` (101 tests, 2 échecs attendus : claim `roles`, `LoginRequestDto` absent de `DOMAINS`).
- **Options** :
  1. Clé aléatoire générée au démarrage des tests (`@DynamicPropertySource` dans `IntegrationTest`, `SecureRandom` puis Base64) : aucun secret dans le dépôt, `./mvnw clean test` marche sans préparation.
  2. Variable d'environnement obligatoire pour lancer les tests (`APP_JWT_SECRET`), à documenter dans le README et à fournir en CI.
  3. Valeur de test en clair, écrite par l'auteur lui-même s'il estime qu'une clé qui ne signe rien hors des tests n'est pas un secret.
- **Décision (2026-11-12)** : option 1, clé de 32 octets tirée par `SecureRandom` une fois par exécution, injectée par `@DynamicPropertySource` dans `IntegrationTest`.
- **Statut** : **DONE**

## JWT itération 1, finalisation (2026-11-18)

### Message « utilisateur introuvable » modifié hors périmètre

- **Blocage** : dans le code non commité, `UserService.findUserById` renvoie `"There is no user with id : "` (espace avant les deux-points) au lieu de `"There is no user with id: "`. Quatre tests échouent (`UserControllerTest.shouldReturn404WhenUserNotFound` et trois tests d'assignee inconnu de `TaskControllerTest`). L'origine du changement n'est pas connue : volontaire ou accidentelle.
- **Tentative** : `./mvnw clean test` : 103 tests, 4 échecs, tous sur ce texte. Les tests d'authentification passent.
- **Options** :
  1. Revenir à `"There is no user with id: "` (typographie anglaise, cohérente avec les autres messages).
  2. Garder l'espace et adapter les quatre tests.
- **Décision (2026-11-18)** : option 1, retour à `"There is no user with id: "`.
- **Statut** : **DONE**
