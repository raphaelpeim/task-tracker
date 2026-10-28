# 03. Configuration

**Ce que tu vas comprendre**
- Où se règle le comportement de l'application sans toucher au code Java
- Ce qu'est un profil et comment le profil `local` du projet est activé
- Comment Spring choisit une valeur quand elle est définie à plusieurs endroits
- Comment lire la configuration dans ton code (`@ConfigurationProperties`, `@Value`)
- Comment garder les secrets hors de git

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
3. variables d'environnement du système ;
4. fichier de profil (`application-local.properties`) ;
5. fichier commun (`application.properties`) ;
6. valeurs par défaut de Spring Boot.

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

Il faut l'activer, par exemple avec `@ConfigurationPropertiesScan` sur la classe principale. C'est le choix
recommandé dès qu'il y a plus d'une ou deux valeurs.

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

`server/src/main/resources/application.properties:1-3`

```properties
spring.application.name=task-tracker
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

| Ligne | Effet |
|---|---|
| 1 | Nom de l'application (logs, outils de supervision) |
| 2 | Hibernate vérifie le schéma au démarrage sans jamais le modifier (c'est Flyway qui le fait évoluer) |
| 3 | Session Hibernate fermée après le service (voir [09. Transactions](09-transactions.md)) |

Il ne contient **que** des réglages valables partout, et aucune information propre à un poste.

### Le fichier de profil et son modèle

`server/src/main/resources/application-local.properties.example:1-4`

```properties
server.port=

spring.datasource.url=
spring.datasource.username=
```

Le fichier liste aussi la clé du mot de passe de la base, puis `spring.jpa.show-sql` et
`logging.level.org.springframework.web` (lignes 5 à 8), toutes sans valeur. C'est un **modèle** : le README demande
de le copier en `application-local.properties` et de le compléter.

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

Sans ce profil, aucune URL de base n'est configurée et le démarrage échoue.

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

### Ce qui arrive : `JwtProperties`

`server/src/main/java/com/personal/task_tracker/auth/config/JwtProperties.java` existe mais est encore vide. Le
guide `.claude/fiches/14-jwt.md` (section 2) en fait un `@ConfigurationProperties` qui lit `app.jwt.*`, avec la clé
passée par `${JWT_SECRET}`.

---

## 4. À toi de jouer

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

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Could not resolve placeholder 'X'` | Variable d'environnement ou propriété absente | La définir, ou prévoir une valeur par défaut `${X:defaut}` si c'est acceptable |
| Ma valeur est ignorée | Une source plus prioritaire la redéfinit (variable d'environnement oubliée dans le shell, configuration de l'IDE) | Vérifier l'ordre de priorité ; `env \| grep -i nom` |
| Le profil ne s'applique pas | Mauvais nom de fichier (`application_local`, `application-Local`) ou profil non activé | Nom exact `application-<profil>.properties` ; chercher "The following 1 profile is active" dans les logs |
| Un secret apparaît dans `git status` | Fichier de config local non couvert par `.gitignore` | L'ajouter au `.gitignore` **avant** le premier commit ; si déjà commité, changer le secret |
| `@ConfigurationProperties` jamais rempli (champs à `null`) | Classe non enregistrée | `@ConfigurationPropertiesScan` ou `@EnableConfigurationProperties` |
| `Failed to bind properties under 'app.jwt.expiration'` | Format de valeur invalide | Durées en ISO-8601 (`PT1H`) ou avec unité (`1h`, `30m`) |
| Une faute de frappe dans une clé passe inaperçue | Une clé inconnue est simplement ignorée | Utiliser l'autocomplétion de l'IDE ; préférer `@ConfigurationProperties` validé |

---

## 6. Pour aller plus loin

- **Profils multiples** ⏳ : `application-test.properties`, `application-prod.properties`, groupes de profils.
- **Configuration externe** : fichier `config/application.properties` à côté du jar, `spring.config.import` pour un
  gestionnaire de secrets.
- **Logging** ⏳ : `logging.level.*`, `logging.file.name`, configuration Logback avancée.
- **Actuator** ⏳ : l'endpoint `/actuator/env` montre d'où vient chaque valeur (à protéger, il peut exposer des
  secrets).
- **Principes "12-factor"**, facteur III "Config" : la configuration vit dans l'environnement.
- **Docs officielles** : Spring Boot Reference, sections "Externalized Configuration" et "Profiles" ; annexe
  "Common Application Properties".

---

## 7. Quiz

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
