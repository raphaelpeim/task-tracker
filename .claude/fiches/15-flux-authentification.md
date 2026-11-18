# 15. Le trajet de l'authentification : qui est appelé, quand et pourquoi

**Ce que tu vas comprendre**
- Les trois moments où le code de sécurité s'exécute : au démarrage, au login, à chaque requête protégée
- Pour chaque classe : qui l'appelle, à quel moment, et ce qui casserait sans elle
- Pourquoi une même erreur 401 peut être écrite par deux classes différentes
- La raison de chaque décision de sécurité prise pendant l'itération 1 du JWT

**Prérequis** : [10. Sécurité](10-securite.md) (chaîne de filtres, BCrypt) et [14. JWT](14-jwt.md) (anatomie d'un
token). Cette fiche ne réexplique pas ces notions, elle montre comment les pièces s'enchaînent dans le code actuel.

**État du code décrit** : 18 novembre 2026, avant commit. Les références `fichier:ligne` partent de
`server/src/main/java/com/personal/task_tracker/` et deviendront fausses dès que le code bougera.

---

## 1. Vue d'ensemble : trois moments

Le code de sécurité ne s'exécute pas en continu. Il intervient à trois moments bien distincts :

| Moment | Combien de fois | Ce qui se passe |
|---|---|---|
| **Démarrage** | Une fois | Spring lit la configuration, vérifie le secret, fabrique les objets (beans) et monte la chaîne de filtres |
| **Login** | À chaque `POST /api/auth/login` | On vérifie e-mail et mot de passe, puis on fabrique et signe un token |
| **Requête protégée** | À chaque autre appel (`/api/tasks`, `/api/users`...) | On vérifie le token, sans base de données, puis on laisse passer ou on renvoie 401 |

L'inscription (`POST /api/auth/register`) est un quatrième trajet, plus simple : elle hache le mot de passe et
crée l'utilisateur, sans token.

Idée clé : **le login est le seul moment où l'on regarde le mot de passe**. Ensuite, le token remplace le mot de
passe. Le serveur ne garde aucune trace des tokens émis (on dit *stateless*, sans état) : il sait seulement
vérifier qu'un token est authentique.

---

## 2. Au démarrage : tout est préparé une seule fois

Quand tu lances l'appli, Spring construit tous les beans avant d'accepter la première requête. Voici ceux qui
concernent la sécurité, dans l'ordre logique.

### 2.1 Lire la configuration : `JwtProperties`

- **Qui l'appelle** : `@ConfigurationPropertiesScan` sur `TaskTrackerApplication.java:8` demande à Spring de
  chercher les classes annotées `@ConfigurationProperties`. Il trouve `auth/config/JwtProperties.java:11`.
- **Ce qu'il fait** : il remplit le record avec les propriétés `app.jwt.*` :
  - `issuer` et `expiration` viennent de `application.properties` (`task-tracker`, `PT1H` soit une heure) ;
  - `secret` vient de `application-local.properties` (ignoré par git) en local, d'une variable d'environnement
    `APP_JWT_SECRET` en production, et d'une valeur tirée au hasard dans les tests (`IntegrationTest`).
- **Pourquoi `@Validated`** : `@NotBlank` et `@NotNull` sont vérifiés à ce moment-là. Une propriété absente
  arrête le démarrage avec un message clair, au lieu de provoquer une `NullPointerException` au premier login.

### 2.2 Préparer la clé, l'encodeur et le décodeur : `JwtConfig`

- **Qui l'appelle** : Spring, parce que la classe est annotée `@Configuration` et que ses méthodes `@Bean`
  fabriquent des objets dont d'autres ont besoin.
- `secretKey()` (`auth/config/JwtConfig.java:46-54`) décode le secret Base64 en octets, **vérifie qu'il y en a au
  moins 32**, puis en fait une clé HMAC-SHA256.
  - Pourquoi 32 octets : HS256 signe avec une clé de 256 bits. Plus courte, elle devient devinable par force brute.
  - Pourquoi ici et pas dans `JwtProperties` : c'est le seul endroit où l'on connaît la vraie longueur, après
    décodage. Une annotation sur la chaîne compterait des caractères Base64 (44 caractères pour 32 octets).
  - Pourquoi ça bloque le démarrage : `jwtEncoder` et `jwtDecoder` appellent `secretKey()` et sont créés au
    lancement. L'exception arrête donc l'appli avant toute requête.
  - Le message d'erreur ne contient jamais le secret, sinon il finirait dans les logs.
- `jwtEncoder()` (`JwtConfig.java:31-34`) : l'objet qui **signe** les tokens. Il sera utilisé par `TokenService`
  au login.
- `jwtDecoder()` (`JwtConfig.java:36-44`) : l'objet qui **vérifie** les tokens. Il sera utilisé par Spring
  Security à chaque requête protégée. Deux réglages :
  - `macAlgorithm(HS256)` : il n'accepte que HS256. Un token qui annonce un autre algorithme (par exemple `none`,
    c'est-à-dire « pas de signature ») est refusé ;
  - `createDefaultWithIssuer(...)` : en plus de l'expiration (`exp`), il vérifie que le token a été émis par
    `task-tracker`. Sans ce réglage, le claim `iss` serait écrit mais jamais contrôlé.

### 2.3 Préparer les règles d'accès : `SecurityConfig`

Fichier `commons/config/SecurityConfig.java`.

- `encoder()` (lignes 18-21) : le `PasswordEncoder` BCrypt, utilisé à l'inscription (pour hacher) et au login
  (pour comparer).
- `filterChain(...)` (lignes 23-45) : la `SecurityFilterChain`, c'est-à-dire la liste des filtres que **chaque**
  requête HTTP traversera avant d'atteindre un contrôleur. Elle est construite une seule fois. Chaque réglage
  ajoute, retire ou configure des filtres :

| Réglage | Lignes | Ce qu'il fait | Pourquoi |
|---|---|---|---|
| `permitAll()` sur `POST /api/auth/register` et `/login` | 27-28 | Ces deux routes passent sans token | On ne peut pas exiger un token pour obtenir un token |
| `anyRequest().authenticated()` | 29-30 | Tout le reste exige un utilisateur authentifié | Protection par défaut : une nouvelle route est protégée sans qu'on y pense |
| `csrf(disable)` | 32-33 | Retire la protection CSRF | Le CSRF exploite les cookies envoyés automatiquement par le navigateur. Ici le token passe dans un en-tête, que le navigateur n'ajoute jamais seul. À réactiver si un jour un cookie porte un identifiant (refresh token de l'itération 2) |
| `STATELESS` | 34-37 | Aucune session HTTP n'est créée | Chaque requête apporte son token, le serveur ne retient rien entre deux appels |
| `oauth2ResourceServer(...).jwt(...)` | 38-40 | Ajoute le filtre qui lit l'en-tête `Authorization: Bearer ...` et fait vérifier le token par `jwtDecoder` | C'est ce qui rend l'API utilisable avec un JWT. Sans `.jwt(...)`, Spring refuse de démarrer : il ne sait pas quel format de token valider |
| `authenticationEntryPoint` / `accessDeniedHandler` | 41-42 | Branche `SecurityProblemHandler` pour écrire les 401 et 403 | Pour que les erreurs de sécurité aient le même format JSON (RFC 9457) que les autres erreurs |

### 2.4 Préparer le hash factice : le constructeur d'`AuthService`

- **Qui l'appelle** : Spring, en créant le bean `AuthService` (`auth/service/AuthService.java:27-38`).
- **Ce qu'il fait** (ligne 33) : il calcule une fois pour toutes le hash BCrypt d'un mot de passe bidon.
- **Pourquoi au démarrage** : un calcul BCrypt coûte de l'ordre de 100 ms. Le faire à chaque login doublerait le
  temps de réponse pour rien. L'explication de son rôle est en section 4.

### 2.5 `SecurityProblemHandler`

- Créé par Spring grâce à `@Component` (`commons/exception/SecurityProblemHandler.java:24`), puis injecté dans
  `filterChain(...)`. Il ne fait rien au démarrage, il attend qu'un filtre l'appelle (section 6).

---

## 3. L'inscription : `POST /api/auth/register`

```
requête
  → filtres de sécurité : route en permitAll, passe sans token
  → AuthController.register (auth/controller/AuthController.java:28-34)
      @Valid vérifie le JSON (sinon 400)
  → AuthService.register (AuthService.java:46-51)
      1. passwordEncoder.encode(motDePasse)          hache le mot de passe (BCrypt, sel aléatoire)
      2. userService.createUser(dto, hash)           normalise, vérifie l'unicité, enregistre
  → 201 Created avec l'utilisateur (sans mot de passe)
```

**Pourquoi le hachage est dans `auth` et pas dans `user`** (décision du 12/11) : le mot de passe en clair ne
traverse jamais la couche `user`. `UserService` reçoit seulement un hash, et ne connaît pas le `PasswordEncoder`.
Tout ce qui touche aux mots de passe vit dans un seul module, et `user` n'a pas besoin de dépendre de `auth`.

**Limite acceptée** : si l'e-mail est déjà pris, la réponse est un 409. Elle révèle donc qu'un compte existe. C'est
un compromis assumé pour l'ergonomie de l'inscription.

---

## 4. Le login : `POST /api/auth/login`

### 4.1 Le trajet

```
requête { "email": "...", "password": "..." }
  → filtres de sécurité : route en permitAll, passe sans token
  → AuthController.login (AuthController.java:36-42)
      @Valid sur LoginRequestDto (auth/dto/LoginRequestDto.java) :
        @NotBlank sur les deux champs, @MaxBytes(72) sur le mot de passe   (sinon 400)
  → AuthService.login (AuthService.java:59-73)
      1. userService.findUserByEmail(email)          e-mail mis en minuscules, Optional<AppUser>
      2. hash à tester = hash de l'utilisateur, ou hash factice s'il n'existe pas
      3. passwordEncoder.matches(motDePasse, hash)   TOUJOURS exécuté, ~100 ms
      4. utilisateur absent OU mot de passe faux     → InvalidCredentialsException
      5. sinon tokenService.generateToken(user)
  → 200 OK { "accessToken": "...", "tokenType": "Bearer", "expiresIn": 3600 }
```

### 4.2 Pourquoi `@MaxBytes(72)` et pas `@Size`

BCrypt ne lit que les 72 premiers **octets** du mot de passe et ignore le reste. Sans limite, deux mots de passe
qui ne diffèrent qu'après le 72e octet seraient considérés comme identiques. `@Size` compte des caractères, alors
qu'un caractère accentué ou un emoji occupe plusieurs octets en UTF-8. Voir [10. Sécurité](10-securite.md),
section 3.4.

### 4.3 Pourquoi un hash factice : l'attaque par le temps de réponse

Sans hash factice, un e-mail inconnu répond en quelques millisecondes (pas de BCrypt), et un e-mail connu avec un
mauvais mot de passe en ~100 ms (BCrypt). Le message d'erreur est le même, mais le chronomètre révèle quels e-mails
ont un compte. Un attaquant peut ainsi dresser une liste de cibles : c'est une **énumération de comptes par canal
temporel**.

En comparant toujours, avec un hash factice quand l'utilisateur n'existe pas, les deux cas prennent le même temps.
Spring Security fait la même chose dans `DaoAuthenticationProvider` (`mitigateAgainstTimingAttack`).

Trois règles pour que ça fonctionne :

1. **Le hash factice vient du même `PasswordEncoder`** : BCrypt lit son facteur de coût dans le hash (`$2a$10$...`).
   Un hash d'un autre coût, ou une chaîne qui n'est pas un hash BCrypt, rendrait la comparaison plus rapide, et
   la différence de temps réapparaîtrait.
2. **`matches` est appelé avant de tester l'existence de l'utilisateur**. Dans `a && b`, Java n'évalue pas `b` si
   `a` est faux. Avec `user.isPresent() && passwordEncoder.matches(...)`, BCrypt n'est jamais appelé pour un
   e-mail inconnu, et la protection ne sert à rien. Il faut stocker le résultat de `matches` dans une variable,
   puis tester `user.isPresent() && resultat`. C'est ce que fait le projet : `matches` à la ligne 62 de
   `AuthService.java`, le test à la ligne 64.
3. **Le succès exige que l'utilisateur existe**. Même si quelqu'un devine le mot de passe factice, `isPresent()`
   est faux et le login échoue. Le mot de passe factice n'a donc pas besoin d'être secret.

### 4.4 Pourquoi la même erreur dans les deux cas

E-mail inconnu ou mot de passe faux : même exception (`auth/exception/InvalidCredentialsException.java`), même
réponse 401 avec le code `auth.invalidCredentials`. Une réponse différente dirait à un attaquant quels e-mails
existent.

Cette exception n'étend **pas** `AuthenticationException` de Spring : c'est une erreur métier du endpoint de login,
traitée par `GlobalExceptionHandler.handleInvalidCredentials` (`commons/exception/GlobalExceptionHandler.java:210-213`).
Si elle étendait `AuthenticationException`, elle serait confondue avec « token absent ou invalide » et prendrait le
code `request.unauthorized`.

### 4.5 La fabrication du token : `TokenService`

`auth/service/TokenService.java:30-44`. Appelé uniquement par `AuthService.login`.

| Élément | Valeur | Pourquoi |
|---|---|---|
| `iss` (émetteur) | `task-tracker` | Le décodeur le vérifie : un token signé par un autre système avec la même clé serait refusé |
| `sub` (sujet) | l'id de l'utilisateur, en texte | Identifie l'utilisateur sans exposer son e-mail. La norme JWT impose une chaîne |
| `iat` (émis à) | maintenant | Trace, et permet de vérifier la durée (`exp - iat`) |
| `exp` (expire à) | maintenant + 1 heure | Limite la durée d'utilisation d'un token volé. Une heure faute de refresh token, à réduire quand il existera |
| En-tête `alg` | HS256 explicite | L'encodeur choisit RS256 par défaut, qui échouerait avec une clé secrète |
| **Pas de rôle** | | Décision du 12/11 : le rôle sera relu en base quand une règle en aura besoin. Avantage : un utilisateur rétrogradé perd ses droits tout de suite, pas à l'expiration de son token |

Le token est **signé, pas chiffré** : n'importe qui peut lire son contenu en décodant le Base64. On n'y met donc
rien de confidentiel. La signature garantit seulement qu'il n'a pas été modifié et qu'il vient de nous.

---

## 5. Une requête protégée : `GET /api/tasks` (par exemple)

### 5.1 Le trajet dans les filtres

```
requête  Authorization: Bearer eyJhbGciOi...
  │
  ▼
BearerTokenAuthenticationFilter     (ajouté par oauth2ResourceServer().jwt())
  │  pas d'en-tête Bearer       → ne fait rien, la requête continue en anonyme
  │  en-tête présent            → JwtDecoder vérifie, dans l'ordre :
  │                                 1. la signature (clé HS256)
  │                                 2. l'algorithme annoncé (HS256 seulement)
  │                                 3. exp : pas expiré
  │                                 4. iss : émis par task-tracker
  │     échec                   → SecurityProblemHandler.commence → 401        (cas A)
  │     succès                  → l'utilisateur est authentifié pour CETTE requête
  ▼
ExceptionTranslationFilter          (surveille le filtre suivant)
  ▼
AuthorizationFilter                 applique anyRequest().authenticated()
  │  anonyme                    → refus → ExceptionTranslationFilter
  │                               → SecurityProblemHandler.commence → 401      (cas B)
  │  authentifié                → passe
  ▼
contrôleur (TaskController...)
```

### 5.2 Ce que « authentifié » veut dire ici

Quand le token est valide, Spring crée un `JwtAuthenticationToken` et le range dans le `SecurityContextHolder`
pour la durée de la requête (voir [10. Sécurité](10-securite.md), section 4.4). Son nom (`getName()`) est le `sub`,
c'est-à-dire l'id de l'utilisateur.

Ses autorités (les droits) sont calculées par le converter par défaut de Spring, qui lit les claims `scope` ou
`scp`. Nos tokens n'en ont pas, donc l'utilisateur authentifié n'a **aucune autorité**. Ce n'est pas un problème :
aucune règle n'en demande pour l'instant.

**Ce qui protège contre un faux token, c'est la signature**, vérifiée en étape 1. Un rôle dans le token n'ajoutait
aucune protection : il n'était fiable que parce que la signature l'était.

**Aucun accès à la base** dans ce trajet. C'est tout l'intérêt du JWT : le serveur vérifie le token par calcul.
La contrepartie : un token ne peut pas être révoqué avant son expiration.

### 5.3 Un piège à connaître pour le front

Le filtre bearer s'exécute sur **toutes** les routes, y compris celles en `permitAll`. Si le front envoie un vieux
token expiré sur `POST /api/auth/login`, le filtre essaie de le vérifier, échoue, et renvoie un 401 avant même
d'atteindre le login. Le front ne doit donc pas joindre de token aux appels de login et d'inscription.

---

## 6. Qui écrit quelle erreur

Les erreurs de sécurité naissent à deux endroits différents, et ce ne sont pas les mêmes classes qui les écrivent.

| Situation | Où ça se passe | Qui écrit la réponse | Statut et `code` |
|---|---|---|---|
| Pas de token sur une route protégée | Filtres (cas B) | `SecurityProblemHandler.commence` | 401, `request.unauthorized` |
| Token invalide, expiré, mal signé | Filtres (cas A) | `SecurityProblemHandler.commence` | 401, `request.unauthorized` |
| Authentifié mais pas autorisé (aucun cas aujourd'hui) | Filtres | `SecurityProblemHandler.handle` | 403, `request.forbidden` |
| Mauvais e-mail ou mot de passe au login | Contrôleur | `GlobalExceptionHandler.handleInvalidCredentials` | 401, `auth.invalidCredentials` |
| JSON de login invalide | Contrôleur | `GlobalExceptionHandler` | 400, code de validation |

**Pourquoi deux classes** : `GlobalExceptionHandler` (un `@RestControllerAdvice`) n'intercepte que les exceptions
levées **dans un contrôleur**. Les filtres de sécurité s'exécutent **avant** le contrôleur, donc leurs erreurs ne
l'atteignent jamais. `SecurityProblemHandler` comble ce trou en produisant le même format JSON. Voir
[10. Sécurité](10-securite.md), section 4.5.

**Pourquoi un seul branchement suffit** : `SecurityProblemHandler` n'est déclaré que dans `oauth2ResourceServer`
(`SecurityConfig.java:41-42`). Ce bloc l'utilise pour le cas A, et l'enregistre aussi comme gestionnaire par défaut
de l'`ExceptionTranslationFilter`, qui s'en sert pour le cas B. Un bloc `exceptionHandling` séparé ferait doublon.
Le test `JwtAuthenticationTest.shouldReturn401WithoutToken` vérifie que le cas B produit bien notre format JSON.

**L'en-tête `WWW-Authenticate`** : la norme HTTP (RFC 9110) impose qu'un 401 l'indique, pour dire au client comment
s'authentifier. `commence` (`SecurityProblemHandler.java:48-53`) appelle d'abord `BearerTokenAuthenticationEntryPoint`
de Spring, qui pose le statut et cet en-tête :
- sans token : `WWW-Authenticate: Bearer resource_metadata="..."`
- token refusé : `WWW-Authenticate: Bearer error="invalid_token", error_description="...", resource_metadata="..."`

Le paramètre `resource_metadata` est ajouté par Spring Security 7 (RFC 9728) : il pointe vers une URL
`/.well-known/oauth-protected-resource` qui décrit le serveur de ressources. Les tests vérifient seulement le
schéma `Bearer` et la présence ou l'absence de `error="invalid_token"`.

Puis il écrit le corps JSON du projet. On obtient ainsi l'en-tête standard de Spring et notre format d'erreur.

---

## 7. Récapitulatif : chaque pièce en une ligne

| Classe | Appelée par | Quand | Sans elle |
|---|---|---|---|
| `JwtProperties` | Spring (`@ConfigurationPropertiesScan`) | Démarrage | Pas de configuration typée, erreurs au premier login au lieu du démarrage |
| `JwtConfig.secretKey()` | `jwtEncoder()` et `jwtDecoder()` | Démarrage | Pas de clé, ou une clé trop courte acceptée |
| `JwtConfig.jwtEncoder()` | `TokenService` | Chaque login | Impossible de signer un token |
| `JwtConfig.jwtDecoder()` | `BearerTokenAuthenticationFilter` | Chaque requête avec token | Impossible de vérifier un token |
| `SecurityConfig.encoder()` | `AuthService` | Inscription, login, démarrage (hash factice) | Mots de passe non hachés ou non comparables |
| `SecurityConfig.filterChain()` | Spring | Démarrage (construction), puis chaque requête (exécution) | Boot applique sa sécurité par défaut (login par formulaire, session) |
| `AuthController` | Spring MVC | Inscription et login | Pas de route d'authentification |
| `AuthService` | `AuthController` | Inscription et login | Pas de logique d'authentification |
| `TokenService` | `AuthService.login` | Login réussi | Pas de token |
| `UserService.findUserByEmail` | `AuthService.login` | Chaque login | Impossible de retrouver l'utilisateur |
| `InvalidCredentialsException` | `AuthService.login` | Login raté | Erreur de login confondue avec une erreur de token |
| `SecurityProblemHandler` | Filtres de sécurité | 401 ou 403 avant le contrôleur | Réponses de Spring par défaut, sans le format JSON du projet |
| `GlobalExceptionHandler` | Spring MVC | Erreur levée dans un contrôleur | 500 au lieu de 401 au login raté |

---

## 8. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| L'appli refuse de démarrer : « must decode to at least 32 bytes » | Secret trop court, ou 32 caractères au lieu de 32 octets | Générer 32 octets aléatoires et les encoder en Base64 (`openssl rand -base64 32`) |
| L'appli refuse de démarrer, message sur « Jwt and Opaque Token » | `.jwt(...)` retiré de `oauth2ResourceServer` | Le remettre |
| Login 401 alors que les identifiants sont bons | Le front envoie un vieux token sur `/login` | Ne pas joindre de token aux routes d'authentification |
| Login refusé pour tous les utilisateurs | Le hash stocké est re-haché avant `matches` | `matches(motDePasseSaisi, hashStocké)`, jamais `encode` au login |
| Le hash factice ne protège pas | `matches` placé à droite d'un `&&` | Appeler `matches` d'abord, stocker le résultat |
| Token valide refusé après un redémarrage | Le secret a changé (normal dans les tests, où il est tiré au hasard) | Garder le même secret entre deux démarrages en local |

---

## 9. Quiz

**1. Un client envoie un token dont il a modifié le `sub` pour usurper un autre utilisateur. Qui l'arrête, et à quelle étape ?**

<details><summary>Réponse</summary>

Le `JwtDecoder`, dès l'étape 1 : la signature a été calculée sur le contenu d'origine. Le contenu modifié ne
correspond plus, et le client ne peut pas recalculer la signature sans la clé secrète. Le filtre bearer appelle
alors `SecurityProblemHandler.commence`, qui renvoie un 401.
</details>

**2. Pourquoi l'erreur de login n'est-elle pas écrite par `SecurityProblemHandler` ?**

<details><summary>Réponse</summary>

Parce qu'elle est levée dans `AuthService`, appelé par un contrôleur, donc après les filtres. C'est
`GlobalExceptionHandler` qui la reçoit. `SecurityProblemHandler` ne traite que les erreurs levées par les filtres,
avant le contrôleur.
</details>

**3. Un utilisateur est passé de ADMIN à USER en base. Avec le choix « pas de rôle dans le token », quand perd-il ses droits ?**

<details><summary>Réponse</summary>

Immédiatement, dès que les règles d'autorisation relisent le rôle en base. Avec un rôle dans le token, il les
aurait gardés jusqu'à l'expiration de son token, soit jusqu'à une heure.
</details>

**4. Combien de requêtes SQL déclenche un `GET /api/tasks` pour vérifier le token ?**

<details><summary>Réponse</summary>

Aucune. La vérification est un calcul (signature, dates, émetteur). C'est ce qui rend l'API *stateless*, et c'est
aussi pourquoi un token ne peut pas être révoqué avant son expiration.
</details>

**5. Pourquoi le mot de passe factice n'a-t-il pas besoin d'être secret ?**

<details><summary>Réponse</summary>

Parce que le succès du login exige aussi que l'utilisateur existe. Pour un e-mail inconnu, `isPresent()` est faux,
et le login échoue même si le mot de passe factice a été deviné. Le hash factice sert uniquement à égaliser le
temps de réponse.
</details>

**6. Pourquoi désactiver CSRF ne crée-t-il pas de faille ici, et quand faudra-t-il y revenir ?**

<details><summary>Réponse</summary>

Le CSRF exploite les identifiants que le navigateur envoie tout seul, c'est-à-dire les cookies. Le token passe ici
dans l'en-tête `Authorization`, que le navigateur n'ajoute jamais de lui-même. Il faudra y revenir si un
identifiant passe un jour dans un cookie, par exemple le refresh token de l'itération 2.
</details>
