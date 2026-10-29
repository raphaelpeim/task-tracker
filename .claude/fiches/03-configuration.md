# 03. Configuration

**Ce que tu vas comprendre**
- Où se règle le comportement de l'application sans toucher au code Java
- Ce qu'est un profil et comment le profil `local` du projet est activé
- Comment Spring choisit une valeur quand elle est définie à plusieurs endroits
- Comment lire la configuration dans ton code (`@ConfigurationProperties`, `@Value`)
- Comment garder les secrets hors de git
- Ce qui se passe réellement au démarrage : la pile de sources de l'`Environment`, le chargement des fichiers par
  profil, le binding de `JwtProperties` et le rapport "APPLICATION FAILED TO START", avec de quoi l'observer

**Prérequis** : [02. Beans et injection](02-beans-injection.md) (la configuration est lue au démarrage du
contexte, et `@ConfigurationProperties` produit un bean).

---

## 1. Le problème que ça résout

Ton application tourne au moins dans trois environnements : ton poste, les tests automatisés, et un jour un
serveur. Entre eux, presque tout ce qui touche à l'extérieur change :

- l'adresse et les identifiants de la base de données ;
- le port HTTP ;
- le niveau de détail des logs ;
- les clés secrètes (comme la future clé JWT).

Si ces valeurs étaient écrites dans le code Java, il faudrait recompiler pour chaque environnement, et les mots de
passe finiraient dans git, lisibles par quiconque a accès au dépôt (et pour toujours, dans l'historique).

La solution est d'**externaliser la configuration** : le même jar, construit une fois, lit ses réglages au
démarrage depuis des fichiers, des variables d'environnement ou la ligne de commande.

Analogie : le code est la voiture, la configuration est le réglage du siège et des rétroviseurs. On ne
reconstruit pas la voiture pour chaque conducteur.

---

## 2. Les concepts

### 2.1 `application.properties`

Fichier placé dans `src/main/resources/`, lu automatiquement au démarrage. Format `clé=valeur`, une par ligne :

```properties
server.port=8081
logging.level.org.springframework.web=DEBUG
```

Les clés reconnues par Spring Boot sont documentées (annexe "Common Application Properties"). L'IDE les
complète automatiquement. Tu peux aussi définir tes propres clés, de préférence sous un préfixe à toi (`app.`).

Le format YAML (`application.yml`) est une alternative équivalente, plus lisible quand les clés sont imbriquées.
Le projet utilise `.properties`.

### 2.2 Les profils

Un **profil** est un nom d'environnement (`local`, `test`, `prod`...). Quand un profil est actif, Spring lit en
plus le fichier `application-<profil>.properties`, dont les valeurs **surchargent** celles du fichier commun.

```
application.properties          ← toujours lu : réglages communs
application-local.properties    ← lu si le profil "local" est actif : réglages du poste
```

Activer un profil :

| Moyen | Exemple |
|---|---|
| Via le plugin Maven | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Variable d'environnement | `SPRING_PROFILES_ACTIVE=local` |
| Argument au lancement du jar | `java -jar app.jar --spring.profiles.active=local` |
| Configuration de lancement de l'IDE | champ "Active profiles" |

Un bean peut aussi n'exister que dans un profil grâce à `@Profile("local")`.

### 2.3 Les placeholders `${...}`

Une valeur peut faire référence à une autre propriété ou à une variable d'environnement :

```properties
# obligatoire : démarrage en échec si la variable est absente
app.jwt.secret=${JWT_SECRET}
# avec valeur par défaut après les deux-points
server.port=${PORT:8080}
```

Attention : dans un `.properties`, un commentaire `#` doit être **seul sur sa ligne**. Écrit en fin de ligne, il
ferait partie de la valeur.

C'est le moyen standard d'injecter un secret sans l'écrire dans un fichier versionné.

### 2.4 L'ordre de priorité des sources

La même clé peut être définie à plusieurs endroits. Spring Boot les empile et **la source la plus haute gagne**.
Version simplifiée, de la plus forte à la plus faible :

1. propriétés des tests (`@SpringBootTest(properties = ...)`, `@DynamicPropertySource`) ;
2. arguments de ligne de commande (`--server.port=9000`) ;
3. propriétés système Java (`-Dserver.port=9000` passé à la JVM) ;
4. variables d'environnement du système ;
5. fichier de profil (`application-local.properties`) ;
6. fichier commun (`application.properties`) ;
7. valeurs par défaut de Spring Boot.

Intérêt : on peut changer un réglage en production par une variable d'environnement, sans reconstruire le jar et
sans modifier les fichiers. La liste complète (plus d'une dizaine de niveaux) est dans la documentation, section
"Externalized Configuration".

### 2.5 Le relaxed binding

Les variables d'environnement n'acceptent ni points ni tirets. Spring fait la correspondance tout seul :

| Dans un fichier | En variable d'environnement |
|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` |
| `app.jwt.secret` | `APP_JWT_SECRET` |

Règle : majuscules, points remplacés par `_`, tirets supprimés.

### 2.6 Lire la configuration dans le code

**a) `@Value`** : une valeur à la fois.

```java
@Value("${app.jwt.issuer}")
private String issuer;
```

Simple, mais les clés sont éparpillées dans le code, sans validation ni autocomplétion.

**b) `@ConfigurationProperties`** : un objet typé qui regroupe un préfixe entier.

```java
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String issuer, @NotNull Duration expiration) {
}
```

- conversion automatique des types (`PT1H` devient une `Duration`, `true` un `boolean`) ;
- validation au démarrage (l'application refuse de démarrer si la config est invalide, ce qui vaut mieux qu'une
  erreur au milieu de la nuit) ;
- un seul endroit où lire toute la configuration d'un module ;
- injectable comme n'importe quel bean.

Il faut l'activer, par exemple avec `@ConfigurationPropertiesScan` sur la classe principale (c'est ce que fait le
projet, `TaskTrackerApplication.java:8`). C'est le choix recommandé dès qu'il y a plus d'une ou deux valeurs.

### 2.7 Les secrets

- Jamais de mot de passe, clé ou jeton dans un fichier versionné, ni dans le code.
- Les fichiers qui contiennent des valeurs locales sont **ignorés par git** ; un fichier `.example` versionné
  documente les clés attendues, **sans valeur**.
- En production : variables d'environnement, ou un gestionnaire de secrets branché via `spring.config.import`.

### 2.8 Les classes `@Configuration`

À ne pas confondre avec les fichiers de propriétés : une classe `@Configuration` déclare des **beans** en Java
(voir [02. Beans et injection](02-beans-injection.md)). Elle **lit** souvent des propriétés, mais c'est du code.

---

## 3. Dans le projet

### Le fichier commun

`server/src/main/resources/application.properties:1-7`

```properties
app.jwt.issuer=task-tracker
app.jwt.expiration=PT1H

spring.application.name=task-tracker

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

| Ligne | Effet |
|---|---|
| 1-2 | Émetteur et durée de vie des futurs jetons JWT, lus par `JwtProperties` (voir plus bas) |
| 4 | Nom de l'application (logs, outils de supervision) |
| 6 | Hibernate vérifie le schéma au démarrage sans jamais le modifier (c'est Flyway qui le fait évoluer) |
| 7 | Session Hibernate fermée après le service (voir [09. Transactions](09-transactions.md)) |

Il ne contient **que** des réglages valables partout, et aucune information propre à un poste. Note l'absence de
`app.jwt.secret` : la clé est un secret, elle va dans le fichier local.

### Le fichier de profil et son modèle

`server/src/main/resources/application-local.properties.example:3-8`

```properties
logging.level.org.springframework.web=

server.port=

spring.datasource.url=
spring.datasource.username=
```

Le fichier déclare aussi la clé JWT `app.jwt.secret` (ligne 1), le mot de passe de la base (ligne 9) et
`spring.jpa.show-sql` (ligne 11), toutes sans valeur. C'est un **modèle** : le README demande de le copier en
`application-local.properties` et de le compléter.

`.gitignore:58`

```
server/src/main/resources/application-*.properties
```

Tout fichier de profil est ignoré par git. Le modèle échappe à la règle car son nom finit par `.example`, et non
par `.properties`.

### L'activation du profil

`README.md`, section "Serveur" :

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sans ce profil, aucune URL de base n'est configurée et le démarrage échoue (la clé `app.jwt.secret` manquerait aussi,
voir 4.4).

### Des propriétés propres aux tests

`server/src/test/java/com/personal/task_tracker/IntegrationTest.java:22-28`

```java
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,     // port libre au hasard
		properties = {
				"spring.jpa.properties.hibernate.generate_statistics=true",  // ne vaut que pour les tests
				"logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
		}
)
```

Ces propriétés ont la priorité la plus haute et ne touchent jamais l'application réelle. Et l'URL de la base de
test ? Elle n'apparaît nulle part : `@ServiceConnection` dans
`server/src/test/java/com/personal/task_tracker/TestcontainersConfiguration.java` la fournit à partir du conteneur
PostgreSQL démarré pour les tests.

### Le même principe côté Docker

`docker-compose.yml` lit `${POSTGRES_USER:?...}` depuis le fichier `.env` (ignoré par git, documenté par
`.env.example`). Ce n'est pas Spring, mais c'est la même idée : valeurs hors du dépôt, modèle sans valeur dans le
dépôt, et échec explicite si la variable manque.

### La configuration JWT : `JwtProperties` et `JwtConfig`

`server/src/main/java/com/personal/task_tracker/auth/config/JwtProperties.java:10-20`

```java
@Validated                                    // valider au démarrage
@ConfigurationProperties(prefix = "app.jwt")  // lit app.jwt.secret, app.jwt.issuer, app.jwt.expiration
public record JwtProperties(
		@NotBlank
		String secret,                        // fourni par application-local.properties
		@NotBlank
		String issuer,                        // application.properties
		@NotNull
		Duration expiration                   // "PT1H" converti en Duration
) {
}
```

Il est enregistré comme bean par `@ConfigurationPropertiesScan`
(`server/src/main/java/com/personal/task_tracker/TaskTrackerApplication.java:8`), puis injecté comme n'importe quel
bean dans `server/src/main/java/com/personal/task_tracker/auth/config/JwtConfig.java:22-24`, qui en tire les beans
`JwtEncoder` et `JwtDecoder`. La suite (émission et vérification des jetons) est dans
[14. JWT](14-jwt.md), qui est en cours d'implémentation.

---

## 4. Sous le capot

Les sections précédentes disent **quoi** configurer. Celle-ci montre **comment** Spring Boot transforme des
fichiers, des variables et des arguments en un objet Java typé et validé, et comment le **voir** dans le projet.
Même plan que dans [02. Beans et injection](02-beans-injection.md), section 4 : le mécanisme réel, comment
l'observer, la source. Ici s'ajoute souvent une question : que se passe-t-il si on retire la pièce ?

**Comment les observations ont été faites.** Avec un test temporaire qui étend `IntegrationTest` (donc le vrai
contexte, avec PostgreSQL via Testcontainers) et qui affiche ce qu'il trouve dans le contexte. Les propriétés et les
niveaux de log sont passés à la JVM du test par `argLine`, sans toucher aux fichiers :

```bash
cd server
./mvnw -q test -Dtest=MonTestTemporaire -Dsurefire.failIfNoSpecifiedTests=false \
  "-DargLine=-Dapp.jwt.issuer=essai -Dlogging.level.org.springframework.boot.context.config=TRACE"
```

Au moment d'écrire, la configuration JWT est en cours et aucun fichier de test ne fournit la clé `app.jwt.secret` :
sans un `-Dapp.jwt.secret=...` (une clé en base64) dans l'`argLine`, le contexte de test ne démarre pas. C'est
justement l'exemple de 4.4.

### 4.1 L'`Environment` : une pile de sources

**Le mécanisme.** Avant de créer le moindre bean, `SpringApplication.run(...)` construit un `Environment` : une
**liste ordonnée** de `PropertySource`. Chaque source sait seulement répondre à "as-tu une valeur pour cette clé ?".
`environment.getProperty("app.jwt.issuer")` interroge les sources de haut en bas et renvoie la première réponse non
nulle. Les fichiers `.properties` ne sont qu'une source parmi d'autres.

`@Value`, les placeholders `${...}` et `@ConfigurationProperties` lisent tous cet objet. C'est ce qui rend la
section 2.4 possible : sans cette abstraction unique, chaque bibliothèque lirait ses réglages à sa façon
(`System.getenv` ici, un fichier là), et tu ne pourrais pas surcharger n'importe quelle clé, y compris celles de
Hibernate ou de Tomcat, depuis n'importe quelle source.

**L'observer.** Le test temporaire injecte `ConfigurableEnvironment` et parcourt la pile :

```java
for (PropertySource<?> ps : env.getPropertySources()) {
	System.out.println(ps.getName() + " -> " + ps.getProperty("app.jwt.issuer"));
}
```

Lancé avec un profil `probe` (un fichier `application-probe.properties` temporaire dans `src/test/resources`),
`-Dapp.jwt.issuer=from-system-property` et la variable d'environnement `APP_JWT_ISSUER=from-env-var`, voici la
sortie réelle, mise en colonnes (l'`Environment` est un `ApplicationServletEnvironment`) :

```
 0  server.ports
 1  configurationProperties
 2  Inlined Test Properties
 3  servletConfigInitParams
 4  servletContextInitParams
 5  systemProperties            app.jwt.issuer = from-system-property
 6  systemEnvironment           app.jwt.issuer = from-env-var
 7  random
 8  Config resource 'class path resource [application-probe.properties]' via location 'optional:classpath:/'
                                app.jwt.issuer = from-profile-file
 9  Config resource 'class path resource [application.properties]' via location 'optional:classpath:/'
                                app.jwt.issuer = task-tracker
10  applicationInfo

valeur résolue : from-system-property
```

| Source | Ce qu'elle contient |
|---|---|
| `server.ports` | `local.server.port`, ajouté une fois Tomcat démarré : c'est ce que lit `@LocalServerPort` dans `IntegrationTest` |
| `configurationProperties` | pas une vraie source : un adaptateur qui ré-expose toutes les autres avec les règles du binding relaxé (4.3) |
| `Inlined Test Properties` | le `properties = {...}` de `@SpringBootTest` dans `IntegrationTest` |
| `servlet...InitParams` | paramètres du conteneur de servlets, vides ici |
| `systemProperties` | les `-Dcle=valeur` passés à la JVM |
| `systemEnvironment` | les variables d'environnement |
| `random` | répond aux clés `random.*` (`${random.uuid}`, `${random.int}`...) |
| `Config resource ...` | un `PropertySource` par fichier chargé, le profil au-dessus du commun (4.2) |
| `applicationInfo` | ajoutée par Spring Boot, tout en bas de la pile |

C'est la liste de 2.4, en vrai : les propriétés de test au-dessus de tout, puis les `-D`, puis l'environnement, puis
les fichiers.

Une absence instructive : `spring.datasource.url` n'est dans **aucune** source pendant les tests (`getProperty`
renvoie `null`). `@ServiceConnection` ne passe pas par l'`Environment` : il enregistre un bean
`JdbcConnectionDetails` (nom observé : `jdbcContainerConnectionDetailsForPostgresContainer`), que l'auto-configuration
JDBC utilise à la place des propriétés `spring.datasource.*`.

**La source.** [Environment Abstraction](https://docs.spring.io/spring-framework/reference/core/beans/environment.html),
[Externalized Configuration, ordre des sources](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.order).

### 4.2 Les fichiers et les profils : deux passes

**Le mécanisme.** Les fichiers sont chargés par `ConfigDataEnvironmentPostProcessor`, une classe déclarée dans le
fichier `META-INF/spring.factories` du jar `spring-boot` (même principe d'annuaire que pour l'auto-configuration,
voir [02. Beans et injection](02-beans-injection.md), section 4.6). Il travaille en deux passes :

1. **avant les profils**, il cherche un fichier `application` (extensions `.properties`, `.xml`, `.yaml`, `.yml`)
   dans `file:./`, `file:./config/`, `file:./config/*/`, `classpath:/` et `classpath:/config/` ;
2. il lit alors `spring.profiles.active` dans tout ce qu'il connaît déjà (ligne de commande, `-D`, variables,
   fichiers chargés) ;
3. **après activation**, il cherche `application-<profil>.*` aux mêmes endroits et insère ces sources
   **au-dessus** du fichier commun. D'où la surcharge.

Et `-Dspring-boot.run.profiles=local` ? Ce n'est pas une propriété Spring : c'est un paramètre du plugin Maven, qui
le transforme en argument `--spring.profiles.active=local` passé à l'application (la chaîne
`--spring.profiles.active` est écrite en dur dans la classe `AbstractRunMojo` du plugin 4.1.1). Le profil est donc
activé par la source "ligne de commande".

**L'observer.** Avec `-Dlogging.level.org.springframework.boot.context.config=TRACE` et le profil `probe`, extrait
réel, filtré et raccourci (les `...` sont de moi) :

```
Adding initial config data import from locations [optional:file:./;optional:file:./config/;optional:file:./config/*/]
Adding initial config data import from locations [optional:classpath:/;optional:classpath:/config/]
Processing imports for phase BEFORE_PROFILE_ACTIVATION. no activation context
Skipping missing resource file:./application.properties
Skipping missing resource classpath:/application.yaml
Loaded resource class path resource [application.properties] from location optional:classpath:/;optional:classpath:/config/
Deducing profiles from current config data environment contributors
Processing imports for phase AFTER_PROFILE_ACTIVATION. [... profiles = [Profiles@... active = '[probe]', ...
Skipping missing resource classpath:/application-probe.yaml
Loaded resource class path resource [application-probe.properties] from location optional:classpath:/;optional:classpath:/config/
Adding imported property source 'Config resource 'class path resource [application-probe.properties]' via location 'optional:classpath:/''
Adding imported property source 'Config resource 'class path resource [application.properties]' via location 'optional:classpath:/''
Setting active profiles: [probe]
```

Les deux phases sont nommées en toutes lettres, et l'ordre d'ajout des sources est celui de 4.1. Sans TRACE, une
ligne INFO résume au démarrage : `The following 1 profile is active: "probe"`, ou, sans profil,
`No active profile set, falling back to 1 default profile: "default"`.

**Ce qui se passe si le fichier est mal nommé.** Rien, et c'est le problème : `application_local.properties` est
simplement absent de la liste, au mieux visible en TRACE comme une ligne "Skipping missing resource" pour le nom
attendu. Tous ces emplacements sont préfixés `optional:`, un fichier absent n'est donc pas une erreur. D'après la
documentation, un import déclaré par toi sans `optional:` (`spring.config.import=...`) fait au contraire échouer le
démarrage si le fichier manque.

**La source.** [External Application Properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files),
[Profile Specific Files](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.profile-specific),
[Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html).

### 4.3 Le binding relaxé et la conversion, vus de près

**Le mécanisme.** Spring Boot ramène chaque nom de propriété à une forme canonique : minuscules, segments séparés
par des points, tirets autorisés (`app.jwt.expiration`). Pour chaque type de source, il connaît les variantes
acceptées. La source `systemEnvironment` (classe `OriginAwareSystemEnvironmentPropertySource`) fait elle-même la
traduction : interrogée sur `app.jwt.expiration`, elle essaie aussi `APP_JWT_EXPIRATION`.

Une fois la valeur trouvée, elle est encore du **texte**. Le binder la convertit vers le type du champ Java. Pour
une `Duration`, Spring Boot accepte l'ISO-8601 du JDK (`PT1H`) **et** son propre format court (`1h`, `30m`,
`500ms`...). Ce second format n'existe pas dans le JDK : `Duration.parse("2h")` lève une
`DateTimeParseException`.

**L'observer.** Variable d'environnement `APP_JWT_EXPIRATION=2h` posée avant `./mvnw` (la JVM du test en hérite).
Sortie réelle :

```
app.jwt.expiration found in : systemEnvironment = 2h
app.jwt.expiration found in : Config resource 'class path resource [application.properties]' ... = PT1H
app.jwt.expiration RESOLVED = 2h
JwtProperties issuer = from-system-property expiration = PT2H
```

Deux mécanismes en une ligne : `APP_JWT_EXPIRATION` a été reconnu comme `app.jwt.expiration` (et gagne sur le
fichier, 4.1), puis `"2h"` est devenu la `Duration` `PT2H`.

**Ce qui casse.** Une variable `APP_JWT_ISSUER` oubliée dans ton shell ou dans ta configuration de lancement d'IDE
gagne silencieusement sur tous les fichiers. C'est la ligne "Ma valeur est ignorée" des pièges.

**La source.** [Relaxed Binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding),
[Binding From Environment Variables](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables),
[Converting Durations](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.conversion.durations).

### 4.4 `@ConfigurationProperties` : du texte au record validé

**Le mécanisme.** Trois étapes, toutes au démarrage :

1. **Enregistrement.** `@ConfigurationPropertiesScan` parcourt le package de `TaskTrackerApplication` et ses
   sous-packages à la recherche de classes `@ConfigurationProperties`, et enregistre pour chacune une définition de
   bean. Nom observé : `app.jwt-com.personal.task_tracker.auth.config.JwtProperties` (préfixe, tiret, nom complet de
   la classe).
2. **Création = binding.** `JwtProperties` est un record : un seul constructeur, donc **binding par
   constructeur**. Pour fabriquer le bean, Spring Boot appelle son `Binder`, qui pour chaque paramètre (`secret`,
   `issuer`, `expiration`) cherche `app.jwt.<nom>` dans l'`Environment` (avec le binding relaxé), convertit le texte
   (4.3), puis appelle le constructeur. Le bean obtenu est ton record, sans proxy : classe observée
   `com.personal.task_tracker.auth.config.JwtProperties`.
3. **Validation.** Parce que la classe porte `@Validated` et qu'une implémentation de Bean Validation est sur le
   classpath (Hibernate Validator 9.1.3, apporté par `spring-boot-starter-validation`), le binder ajoute une étape de
   validation à la fin du binding. Une contrainte violée lève une exception **pendant la création du bean**, donc
   pendant le démarrage, avant que la première requête n'arrive.

**L'observer.** Lance le test sans `app.jwt.secret`. La chaîne des causes réelle (noms de package raccourcis) :

```
UnsatisfiedDependencyException: Error creating bean with name 'jwtConfig' ...: Unsatisfied dependency expressed
  through constructor parameter 0: Error creating bean with name 'app.jwt-com.personal.task_tracker.auth.config.JwtProperties':
  Could not bind properties to 'JwtProperties' : prefix=app.jwt, ignoreInvalidFields=false, ignoreUnknownFields=true
Caused by: ConfigurationPropertiesBindException: ...
Caused by: BindException: Failed to bind properties under 'app.jwt' to com.personal.task_tracker.auth.config.JwtProperties
Caused by: BindValidationException: Binding validation errors on app.jwt
```

Lis-la à l'envers : la validation échoue, donc le binding, donc la création de `JwtProperties`, donc celle de
`jwtConfig` qui en a besoin. Dans les lignes `at ...` de la pile, on suit le chemin du mécanisme :
`ConfigurationPropertiesBeanRegistrar` (la définition du bean), `ConstructorBound.from`,
`ConfigurationPropertiesBinder.bindOrCreate`, `Binder.bind`, puis `ValidationBindHandler.onFinish`.

Note au passage `ignoreUnknownFields=true` : une clé mal orthographiée (`app.jwt.isuer`) est ignorée sans un mot.
C'est la contrainte `@NotBlank` sur le vrai champ qui te préviendra.

**Ce qui se passe si on retire une pièce.** Trois expériences faites sur le projet, puis annulées :

| Expérience | Résultat observé |
|---|---|
| Retirer `@ConfigurationPropertiesScan` | `Parameter 0 of constructor in com.personal.task_tracker.auth.config.JwtConfig required a bean of type 'com.personal.task_tracker.auth.config.JwtProperties' that could not be found.` Sans scan, le record n'est pas un bean du tout |
| Retirer `@Validated`, toujours sans `app.jwt.secret` | Le binding réussit avec `secret = null`. L'échec arrive plus loin, dans `JwtConfig.secretKey()` : `NullPointerException: Cannot invoke "String.getBytes(java.nio.charset.Charset)" because "src" is null`, noyée dans la pile de création de `jwtEncoder` |
| Garder `@Validated`, fournir une clé non base64 (`pas-du-base64!`) | La validation passe (la chaîne n'est pas vide), puis `IllegalArgumentException: Illegal base64 character 2d` dans `jwtEncoder`. `@Validated` ne vérifie que les contraintes que tu as déclarées |

Morale : `@Validated` ne rend pas la configuration correcte, il déplace l'échec **au plus tôt et au bon endroit**,
avec un message qui nomme la propriété.

**La source.** [Type-safe Configuration Properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties),
[Constructor Binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding),
[Enabling @ConfigurationProperties-annotated Types](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.enabling-annotated-types),
[@ConfigurationProperties Validation](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation).

### 4.5 "APPLICATION FAILED TO START" : les `FailureAnalyzer`

**Le mécanisme.** Quand `run()` échoue, `SpringApplication` confie l'exception à `FailureAnalyzers`. Celui-ci charge
toutes les implémentations de `FailureAnalyzer` déclarées dans les fichiers `META-INF/spring.factories` du
classpath, et leur présente l'exception. Chaque analyseur cherche dans la chaîne des causes le type d'erreur qu'il
sait expliquer. Le premier qui reconnaît la sienne produit une **Description** et une **Action**, imprimées dans
l'encadré "APPLICATION FAILED TO START" par `LoggingFailureAnalysisReporter`. Si aucun ne reconnaît l'erreur, tu
n'as que la pile brute.

**L'observer.** La liste des analyseurs de base est un simple fichier texte :

```bash
unzip -p ~/.m2/repository/org/springframework/boot/spring-boot/4.1.1/spring-boot-4.1.1.jar META-INF/spring.factories
```

Extrait réel :

```
# Error Reporters
org.springframework.boot.SpringBootExceptionReporter=\
org.springframework.boot.diagnostics.FailureAnalyzers
...
# Failure Analyzers
org.springframework.boot.diagnostics.FailureAnalyzer=\
...
org.springframework.boot.diagnostics.analyzer.BeanCurrentlyInCreationFailureAnalyzer,\
...
org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer,\
org.springframework.boot.diagnostics.analyzer.BindValidationFailureAnalyzer,\
...
org.springframework.boot.diagnostics.analyzer.NoUniqueBeanDefinitionFailureAnalyzer,\
...
```

Les messages des exercices de la fiche 02 (cycle, deux candidats) viennent de ces classes. Quatre cas réels du
projet :

*a) Contrainte violée* (`BindValidationFailureAnalyzer`), test lancé sans `app.jwt.secret` :

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target com.personal.task_tracker.auth.config.JwtProperties failed:

    Property: app.jwt.secret
    Value: "null"
    Reason: ne doit pas être vide


Action:

Update your application's configuration
```

La raison est en français parce que la JVM tourne avec une locale française : c'est le message par défaut de
Hibernate Validator pour `@NotBlank`.

*b) Conversion impossible* (`BindFailureAnalyzer`), avec `-Dapp.jwt.expiration=une-heure` :

```
Description:

Failed to bind properties under 'app.jwt.expiration' to java.time.Duration:

    Property: app.jwt.expiration
    Value: "une-heure"
    Origin: "app.jwt.expiration" from property source "systemProperties"
    Reason: failed to convert java.lang.String to @jakarta.validation.constraints.NotNull java.time.Duration (caused by java.lang.IllegalArgumentException: 'une-heure' is not a valid duration)
```

La ligne `Origin` te dit **quelle source de 4.1** a fourni la mauvaise valeur. Précieux quand tu cherches qui gagne.

*c) Pas de base de données* (exercice 1, `./mvnw spring-boot:run` sans profil) :

```
Description:

Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.

Reason: Failed to determine a suitable driver class


Action:

Consider the following:
	If you want an embedded database (H2, HSQL or Derby), please put it on the classpath.
	If you have database settings to be loaded from a particular profile you may need to activate it (no profiles are currently active).
```

Celui-ci vient de `DataSourceBeanCreationFailureAnalyzer`, déclaré dans le `spring.factories` du module
`spring-boot-jdbc` : chaque module de Boot 4 apporte ses propres analyseurs. Il consulte même les profils actifs de
l'`Environment` pour te suggérer d'en activer un.

*d) Aucun analyseur ne reconnaît l'erreur* (clé `pas-du-base64!`) : pas d'encadré, seulement
`ERROR ... o.s.boot.SpringApplication : Application run failed` suivi de la pile, dont la dernière cause est
`Caused by: java.lang.IllegalArgumentException: Illegal base64 character 2d`. Réflexe : quand il n'y a pas
d'encadré, descends jusqu'au **dernier** `Caused by`.

**La source.** [Create Your Own FailureAnalyzer](https://docs.spring.io/spring-boot/how-to/application.html#howto.application.failure-analyzer),
[FailureAnalyzer (Javadoc)](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/diagnostics/FailureAnalyzer.html).

### 4.6 Le refaire toi-même : un mini binder en 50 lignes

Comme le mini-conteneur de la fiche 02, crée ce fichier **hors du projet** et lance-le avec
`java MiniEnv.java` :

```java
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class MiniEnv {

	// La classe à remplir : un record ordinaire, sans annotation
	record JwtProperties(String issuer, Duration expiration) {
	}

	// La pile de sources, de la plus forte à la plus faible
	static final List<Map<String, String>> sources = List.of(
			Map.of("app.jwt.issuer", "depuis-la-ligne-de-commande"),                   // ~ systemProperties
			Map.of("APP_JWT_EXPIRATION", "PT2H"),                                       // ~ systemEnvironment
			Map.of("app.jwt.issuer", "task-tracker", "app.jwt.expiration", "PT1H")     // ~ application.properties
	);

	static String getProperty(String key) {
		String envName = key.replace('.', '_').replace("-", "").toUpperCase();     // binding relaxé
		for (Map<String, String> source : sources) {
			String value = source.containsKey(key) ? source.get(key) : source.get(envName);
			if (value != null) {
				return value;                                                       // la première source gagne
			}
		}
		return null;
	}

	static <T extends Record> T bind(String prefix, Class<T> type) throws Exception {
		RecordComponent[] components = type.getRecordComponents();
		Class<?>[] types = new Class<?>[components.length];
		Object[] args = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			String key = prefix + "." + components[i].getName();
			String raw = getProperty(key);
			if (raw == null) {
				throw new IllegalStateException(key + " : ne doit pas être nul");     // ~ @Validated
			}
			types[i] = components[i].getType();
			args[i] = types[i] == Duration.class ? Duration.parse(raw) : raw;      // ~ conversion de type
		}
		return type.getDeclaredConstructor(types).newInstance(args);               // ~ binding par constructeur
	}

	public static void main(String[] args) throws Exception {
		System.out.println(bind("app.jwt", JwtProperties.class));
	}
}
```

Sortie : `JwtProperties[issuer=depuis-la-ligne-de-commande, expiration=PT2H]`. Compare avec 4.1 à 4.4 : une liste
de sources interrogée dans l'ordre, un nom traduit pour l'environnement, une conversion de texte vers le type du
paramètre, un appel au constructeur, un refus si une valeur manque. Ce qui manque par rapport à Spring Boot :
l'origine de chaque valeur (la ligne `Origin` de 4.5), les autres formes relaxées (`camelCase`, `kebab-case`), un
vrai service de conversion, Bean Validation, et le rapport d'échec.

**Défis :**

1. Retire `APP_JWT_EXPIRATION` et `app.jwt.expiration` des sources. Que se passe-t-il ?
2. Remets-les, mais remplace `"PT2H"` par `"2h"`. Que se passe-t-il, et pourquoi Spring Boot, lui, l'accepte ?

<details><summary>Réponses</summary>

1. `IllegalStateException: app.jwt.expiration : ne doit pas être nul`. C'est l'équivalent de la
   `BindValidationException` de 4.4, sans l'encadré du `FailureAnalyzer`.
2. `DateTimeParseException: Text cannot be parsed to a Duration` : `Duration.parse` du JDK ne connaît que
   l'ISO-8601. Spring Boot passe par son propre convertisseur de durées, qui accepte aussi `2h` (4.3).
</details>

---

## 5. À toi de jouer

**Exercice 1 : démarrer sans profil.**
Lance `./mvnw spring-boot:run` (sans `-Dspring-boot.run.profiles=local`).
*À observer* : échec au démarrage avec un message du type "Failed to configure a DataSource: 'url' attribute is
not specified". L'URL de la base n'est que dans le fichier du profil `local`.

**Exercice 2 : la ligne de commande gagne.**
Lance avec le profil local et un port différent de celui de ton fichier :
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=9090`
*À observer* : la ligne "Tomcat started on port 9090" dans les logs, sans avoir modifié aucun fichier.

**Exercice 3 : la variable d'environnement gagne sur le fichier.**
`SERVER_PORT=9191 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
*À observer* : port 9191. Le relaxed binding a transformé `SERVER_PORT` en `server.port`, et l'environnement
passe avant `application-local.properties`.

**Exercice 4 : régler les logs par package.**
Dans **ton** `application-local.properties`, mets `spring.jpa.show-sql=true`, puis appelle `GET /api/tasks`.
*À observer* : les requêtes SQL générées par Hibernate apparaissent dans la console. Tu verras notamment la jointure
sur l'assigné (voir [08. Persistance](08-persistance.md)). Remets la valeur précédente ensuite si tu veux des logs
plus calmes.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Could not resolve placeholder 'X'` | Variable d'environnement ou propriété absente | La définir, ou prévoir une valeur par défaut `${X:defaut}` si c'est acceptable |
| Ma valeur est ignorée | Une source plus prioritaire la redéfinit (variable d'environnement oubliée dans le shell, configuration de l'IDE) | Vérifier l'ordre de priorité ; `env \| grep -i nom` |
| Le profil ne s'applique pas | Mauvais nom de fichier (`application_local`, `application-Local`) ou profil non activé | Nom exact `application-<profil>.properties` ; chercher "The following 1 profile is active" dans les logs |
| Un secret apparaît dans `git status` | Fichier de config local non couvert par `.gitignore` | L'ajouter au `.gitignore` **avant** le premier commit ; si déjà commité, changer le secret |
| `required a bean of type '...JwtProperties' that could not be found` | Classe `@ConfigurationProperties` non enregistrée comme bean (4.4) | `@ConfigurationPropertiesScan` ou `@EnableConfigurationProperties` |
| Pas d'encadré "APPLICATION FAILED TO START", seulement `Application run failed` et une longue pile | Aucun `FailureAnalyzer` ne reconnaît l'erreur (4.5) | Lire le **dernier** `Caused by` |
| `Failed to bind properties under 'app.jwt.expiration'` | Format de valeur invalide | Durées en ISO-8601 (`PT1H`) ou avec unité (`1h`, `30m`) |
| Une faute de frappe dans une clé passe inaperçue | Une clé inconnue est simplement ignorée | Utiliser l'autocomplétion de l'IDE ; préférer `@ConfigurationProperties` validé |

---

## 7. Pour aller plus loin

- **Profils multiples** ⏳ : `application-test.properties`, `application-prod.properties`, groupes de profils.
- **Configuration externe** : fichier `config/application.properties` à côté du jar, `spring.config.import` pour un
  gestionnaire de secrets.
- **Logging** ⏳ : `logging.level.*`, `logging.file.name`, configuration Logback avancée.
- **Actuator** ⏳ : l'endpoint `/actuator/env` montre d'où vient chaque valeur (à protéger, il peut exposer des
  secrets).
- **Principes "12-factor"**, [facteur III "Config"](https://12factor.net/config) : la configuration vit dans
  l'environnement.
- **Docs officielles** : en plus des liens de la section 4,
  [Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) en entier,
  et l'annexe [Common Application Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).

---

## 8. Quiz

**1. Le profil `local` est actif. `server.port` vaut 8081 dans `application.properties` et 8082 dans
`application-local.properties`. Quel port est utilisé ?**
<details><summary>Réponse</summary>
8082 : le fichier du profil actif surcharge le fichier commun.
</details>

**2. Et si en plus tu lances avec `SERVER_PORT=8083` ?**
<details><summary>Réponse</summary>
8083 : les variables d'environnement passent avant les fichiers de propriétés.
</details>

**3. Pourquoi `application-local.properties.example` est-il versionné alors que `application-local.properties`
ne l'est pas ?**
<details><summary>Réponse</summary>
Le modèle documente les clés à remplir, sans aucune valeur. Le vrai fichier contient les valeurs propres au poste,
dont des identifiants : il est ignoré par la règle <code>application-*.properties</code> du
<code>.gitignore</code>.
</details>

**4. Quelle est la différence entre `${JWT_SECRET}` et `${JWT_SECRET:abc}` ?**
<details><summary>Réponse</summary>
La première forme fait échouer le démarrage si la variable est absente. La seconde utilise <code>abc</code> par
défaut. Pour un secret, on veut la première : un secret par défaut écrit dans le fichier n'est plus un secret.
</details>

**5. Citer deux avantages de `@ConfigurationProperties` sur `@Value`.**
<details><summary>Réponse</summary>
Au choix : conversion de types (Duration...), validation au démarrage, regroupement de toute la config d'un module
en un seul objet, autocomplétion dans l'IDE, injectable comme un bean, facile à construire dans un test.
</details>

**6. D'où vient l'URL de la base pendant les tests d'intégration ?**
<details><summary>Réponse</summary>
De <code>@ServiceConnection</code> sur le conteneur PostgreSQL de <code>TestcontainersConfiguration</code> :
Spring Boot lit l'adresse et les identifiants du conteneur démarré par Testcontainers. Aucun fichier de profil
n'est utilisé.
</details>
