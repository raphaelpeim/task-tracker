# 13. Environnement, outillage, infra

**Ce que tu vas comprendre**
- Pourquoi la base de données tourne dans Docker, et comment `docker-compose` la décrit
- Le système de fichiers « commun + local non commité » : `.env`, override compose, profil Spring
- Ce que `.gitignore` protège, et pourquoi c'est une question de sécurité
- Les outils du quotidien : Maven Wrapper, DevTools, le profil `local`
- Les conventions qui rendent le projet lisible : commits, README, JOURNAL
- Ce qui se passe sous le capot : les deux chargeurs de classes de DevTools et son redémarrage, ce que Compose crée
  et comment il retrouve ses conteneurs, le conteneur Ryuk de Testcontainers, le mode du Maven Wrapper

**Prérequis** : [01. Maven et le pom.xml](01-maven-pom.md), [03. Configuration](03-configuration.md).

---

## 1. Le problème que ça résout

Le code ne suffit pas pour qu'un projet tourne. Il faut aussi :
- une base PostgreSQL, dans la bonne version, avec des identifiants ;
- un JDK 25, et pas un autre ;
- une configuration propre à **ta** machine (port, mot de passe) qui ne doit **pas** atterrir dans git ;
- une façon de savoir pourquoi le code est comme il est.

Sans outillage, chaque développeur installe PostgreSQL à la main (dans une version différente), écrit son mot de
passe dans un fichier commité par erreur, et découvre les décisions passées en relisant le code ligne à ligne.

**Analogie** : une cuisine de restaurant. La recette (le code) ne sert à rien si chaque cuisinier a un four
différent. On standardise l'équipement (Docker, Maven Wrapper), on range à part ce qui est personnel (le casier :
`.env`, fichiers locaux), et on tient un cahier de bord (JOURNAL) pour expliquer les choix.

L'objectif concret : **un nouveau venu clone le dépôt, suit le README, et le projet tourne**.

---

## 2. Les concepts

### 2.1 Docker : image, conteneur, volume

- Une **image** est un modèle figé : « PostgreSQL 16 installé et prêt ». Elle se télécharge depuis un registre
  (Docker Hub).
- Un **conteneur** est une instance qui tourne à partir d'une image. Isolé du reste de la machine, il démarre en
  quelques secondes et se jette sans laisser de trace.
- Un **volume** est un espace de stockage géré par Docker qui **survit** au conteneur. Sans volume, supprimer le
  conteneur supprime les données.

L'intérêt : tout le monde a exactement le même PostgreSQL, sans rien installer sur sa machine.

### 2.2 Docker Compose

Plutôt que de taper une longue commande `docker run` avec toutes les options, on décrit les **services** dans un
fichier `docker-compose.yml`, puis :

```sh
docker compose up -d      # démarre en arrière-plan (-d : détaché)
docker compose ps         # liste les conteneurs
docker compose logs -f    # suit les logs
docker compose down       # arrête et supprime les conteneurs (le volume reste)
docker compose down -v    # supprime AUSSI les volumes : les données sont perdues
```

**L'override** : si un fichier `docker-compose.override.yml` existe à côté, Compose le **fusionne** automatiquement
avec le fichier principal. Il sert aux réglages propres à une machine.

**Les variables** : `${VARIABLE}` dans le fichier compose est remplacé par la valeur lue dans l'environnement ou
dans un fichier `.env` à la racine. La forme `${VARIABLE:?message}` fait **échouer** le démarrage avec ce message si
la variable manque, plutôt que de démarrer avec une valeur vide.

### 2.3 Le schéma « commun commité + local non commité »

Le projet applique la même idée à trois endroits :

| Rôle | Commité (partagé) | Non commité (personnel) | Modèle commité |
|---|---|---|---|
| Variables de Docker | `docker-compose.yml` | `.env` | `.env.example` |
| Réglages de Docker | `docker-compose.yml` | `docker-compose.override.yml` | `docker-compose.override.example.yml` |
| Configuration Spring | `application.properties` | `application-local.properties` | `application-local.properties.example` |

Le fichier **`.example`** documente les clés attendues, sans valeur. On le copie, on le complète, et la copie est
ignorée par git. Ainsi le dépôt dit **quoi** configurer, jamais **avec quelles valeurs**.

### 2.4 `.gitignore`

Ce fichier liste ce que git ne doit pas suivre. Trois familles :
- **Généré** : `target/` (Maven), `node_modules/`, `dist/`. Ça se reconstruit, ça encombrerait le dépôt.
- **Personnel** : réglages d'IDE (`.idea/`), fichiers système (`.DS_Store`).
- **Sensible** : `.env`, clés (`*.pem`, `*.key`), fichiers de configuration locaux. C'est ici que `.gitignore`
  devient une **protection de sécurité** : un secret commité reste dans l'historique git même après suppression.

Un motif précédé de `!` réintègre un fichier exclu par une règle plus large.

### 2.5 Maven Wrapper

`mvnw` (macOS, Linux) et `mvnw.cmd` (Windows) sont de petits scripts commités qui téléchargent la **version de Maven
fixée par le projet** et l'utilisent. Plus besoin d'installer Maven, et tout le monde construit avec la même
version. On l'utilise à la place de `mvn` : `./mvnw test`.

Le JDK, lui, n'est pas fourni : Maven utilise celui que désigne `JAVA_HOME`.

### 2.6 Spring Boot DevTools

Dépendance de confort pour le développement : quand une classe est recompilée, l'application **redémarre
automatiquement** (plus vite qu'un démarrage complet, grâce à deux chargeurs de classes séparés). Elle est déclarée
`optional` et `runtime`, et Spring Boot la désactive quand l'application tourne depuis le jar packagé.

### 2.7 Conventions de commit

Le format **Conventional Commits** : `type(périmètre): description`.

| Type | Usage |
|---|---|
| `feat` | Nouvelle fonctionnalité |
| `fix` | Correction de bug |
| `chore` | Maintenance, configuration, outillage |
| `docs` | Documentation |
| `test` | Ajout ou correction de tests |
| `refactor` | Réécriture sans changement de comportement |

Bénéfices : un historique qui se lit d'un coup d'œil, la possibilité de générer un changelog, et des commits
**atomiques** (un commit = un changement cohérent), plus faciles à relire et à annuler.

### 2.8 README et journal de décisions

- Le **README** répond à « comment je fais tourner le projet ? » : prérequis, étapes, commandes.
- Un **journal de décisions** (ici `JOURNAL.md`, ailleurs on parle d'ADR, *Architecture Decision Records*) répond à
  « pourquoi c'est fait comme ça ? ». Le code dit **ce qui** est fait ; seul un écrit garde le **pourquoi** et les
  alternatives écartées.

---

## 3. Dans le projet

### La base locale

`docker-compose.yml:1` à `:13` (la ligne du mot de passe a la même forme que celle de l'utilisateur) :

```yaml
name: task-tracker
services:
  postgres:
    image: postgres:16                                                            # (1)
    environment:
      POSTGRES_DB: task_tracker                                                   # (2)
      POSTGRES_USER: ${POSTGRES_USER:?POSTGRES_USER is missing, to define in .env} # (3)
      POSTGRES_PASSWORD: ...
    volumes:
      - postgres_data:/var/lib/postgresql/data                                    # (4)

volumes:
  postgres_data:
```

1. Version **majeure** fixée : un `postgres:latest` changerait de version sans prévenir.
2. L'image officielle crée cette base au premier démarrage.
3. Pas de valeur dans le fichier : elle vient de `.env`. Si elle manque, Compose refuse de démarrer avec un message
   qui dit quoi faire.
4. Les données vivent dans le volume nommé `postgres_data`, pas dans le conteneur.

Remarque : **aucun port n'est exposé** dans ce fichier. PostgreSQL est donc injoignable depuis ta machine tant que
l'override n'est pas en place.

`docker-compose.override.example.yml:1` à `:4` :

```yaml
services:
  postgres:
    ports:
      - "5432:5432"      # port de ta machine : port du conteneur
```

Pourquoi en override ? Le port 5432 est peut-être déjà pris sur ta machine (un PostgreSQL installé à côté). Chacun
choisit le sien (`"5433:5432"`) sans toucher au fichier commun.

Les tests, eux, n'utilisent pas cette base : Testcontainers démarre son propre conteneur jetable
(`postgres:16-alpine`, `server/src/test/java/com/personal/task_tracker/TestcontainersConfiguration.java`), sur un
port aléatoire. Même version majeure que la base locale, ce qui garde les tests fidèles.

### Ce que `.gitignore` protège

Extraits de `.gitignore` :

```gitignore
server/target/                                       # :56  généré par Maven
server/.mvn/wrapper/maven-wrapper.jar                # :57  inutilisé ici (wrapper en mode only-script), voir 4.4
server/src/main/resources/application-*.properties   # :58  configurations locales Spring
.env                                                 # :90  variables Docker
*.pem                                                # :93  clés et certificats
*.key                                                # :94
docker-compose.override.yml                          # :95  réglages Docker locaux
```

La ligne `:58` mérite qu'on s'y arrête : elle ignore **tout** fichier `application-<profil>.properties`. Le fichier
commun `application.properties` reste suivi (pas de tiret), et le modèle `application-local.properties.example` aussi
(il finit par `.example`, pas par `.properties`). Un futur `application-prod.properties` serait ignoré d'office :
c'est voulu, la configuration de production n'a pas sa place dans le dépôt.

### Le parcours du README

`README.md` décrit l'installation complète :
- `:9` : JDK 25, avec un piège noté : Maven compile avec le JDK de `JAVA_HOME`, pas celui du `PATH` ;
- `:21` à `:26` : copier les deux modèles, puis `docker compose up -d` ;
- `:29` : que faire si une migration Flyway déjà appliquée a été modifiée (recréer la base locale avec
  `docker compose down -v`, voir [08. Persistance](08-persistance.md)) ;
- `:38` : `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`, qui active le profil `local` ;
- `:47` : `./mvnw clean test`, et pourquoi le `clean` (une migration renommée resterait dans `target/` sous son
  ancien nom).

Chaque piège rencontré est documenté là où le lecteur en a besoin.

### Le monorepo

`README.md:4` : un seul dépôt contient `server/` (Spring Boot) et `client/` (React, Vite, TypeScript). `.gitignore`
est découpé en sections par partie (`:35` client, `:53` serveur). Le `JOURNAL.md` explique le choix : simplifier la
gestion pour un développeur seul.

### DevTools

`server/pom.xml:70` à `:75` :

```xml
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-devtools</artifactId>
	<scope>runtime</scope>
	<optional>true</optional>
</dependency>
```

### Les commits et le journal

L'historique (`git log --oneline`) suit les Conventional Commits :

```
1124267 feat(user): add users read endpoints
28ca9e7 feat(auth): add user registration endpoint
e08a31a feat(task): create task CRUD API
14a4d8e feat(user): create app_user
f74655a chore(server): configure PostgreSQL, Flyway and security
```

Le périmètre (`user`, `auth`, `task`, `server`) correspond aux packages par domaine (voir
[04. Architecture en couches](04-architecture-couches.md)).

`JOURNAL.md` consigne les décisions **avec leur raison**, par exemple :
- `VARCHAR + CHECK` plutôt qu'un type ENUM PostgreSQL, plus simple à faire évoluer ;
- `ddl-auto=validate` plutôt que `update`, pour que seul Flyway modifie le schéma ;
- les pièges de Spring Boot 4 (Jackson 3, artefacts Testcontainers renommés, starter Flyway devenu nécessaire) ;
- une décision **revue** plus tard, et marquée comme telle (le mapping DTO déplacé dans le service).

Ce dernier point est une bonne pratique : on ne réécrit pas l'histoire, on note qu'une décision a été remplacée.

---

## 4. Sous le capot

Les sections précédentes disent **à quoi servent** les outils. Celle-ci montre **comment ils fonctionnent**, avec de
quoi l'observer sur ta machine. Même plan que la fiche 02 : le mécanisme réel, comment l'observer, la source, et ce
qui se passe sans.

Pour les observations DevTools, l'application doit tourner avec `./mvnw spring-boot:run` (commande du README).
`jcmd`, utilisé plus bas, est fourni avec le JDK ; `jcmd` sans argument liste les JVM en cours et leur PID.

### 4.1 DevTools : deux chargeurs de classes, un seul redémarrage partiel

**Le mécanisme.** En Java, une classe est chargée par un *chargeur de classes* (`ClassLoader`), et une classe déjà
chargée ne peut pas être remplacée. Pour prendre en compte une modification, il faut donc jeter le chargeur
entier et en créer un neuf. DevTools répartit les classes entre deux chargeurs :

| Chargeur | Ce qu'il charge | Au redémarrage |
|---|---|---|
| `AppClassLoader` (le chargeur standard de Java) | les jars des dépendances : Spring, Hibernate, Tomcat... | conservé |
| `RestartClassLoader` (créé par DevTools) | le dossier `target/classes` : **tes** classes | jeté, puis recréé |

Quand des fichiers changent dans `target/classes`, DevTools ferme le contexte Spring, abandonne le
`RestartClassLoader`, en crée un nouveau et relance `main` dans le **même** processus Java. Les milliers de classes
des bibliothèques, déjà chargées et optimisées par la JVM, restent en place : c'est tout le gain.

DevTools ne surveille pas tes fichiers `.java` : il surveille le classpath. Il faut donc **recompiler** (l'IDE le
fait à l'enregistrement si la compilation automatique est activée, sinon `./mvnw compile`) pour déclencher le
redémarrage.

**L'observer.** Lance l'application avec `-Dspring-boot.run.arguments=--logging.level.org.springframework.boot.devtools=DEBUG`
en plus des arguments du README. Extraits réels, relevés sur le projet :

```
[  restartedMain] o.s.b.devtools.restart.ChangeableUrls    : Matching URLs for reloading : [file:/.../server/target/classes/]
...
[  restartedMain] c.p.task_tracker.TaskTrackerApplication  : Started TaskTrackerApplication in 1.782 seconds (process running for 1.934)
```

Le nom du thread, `restartedMain` au lieu de `main`, est la signature de DevTools. `Matching URLs for reloading`
indique ce qui ira dans le `RestartClassLoader` : `target/classes` seulement.

Modifie une classe et recompile (`./mvnw compile` dans un autre terminal). Suite du log :

```
[   File Watcher] rtingClassPathChangeChangedEventListener : Restarting due to 34 class path changes (0 additions, 0 deletions, 34 modifications)
[       Thread-1] o.s.boot.devtools.restart.Restarter      : Stopping application
[       Thread-1] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.
...
[  restartedMain] com.zaxxer.hikari.HikariDataSource       : HikariPool-2 - Starting...
[  restartedMain] c.p.task_tracker.TaskTrackerApplication  : Started TaskTrackerApplication in 0.317 seconds (process running for 57.481)
```

Trois choses à lire : 0,3 seconde au lieu de 1,8 ; `process running for 57.481` prouve que c'est le **même**
processus (le PID ne change pas non plus) ; `HikariPool-2` montre que le contexte Spring, lui, est entièrement
reconstruit (nouveau pool de connexions, Flyway revérifie les migrations). Le nombre de changements dépend de ce que
le compilateur a réécrit.

Pour voir les chargeurs eux-mêmes, `jcmd <pid> VM.classloaders` pendant que l'application tourne, après un
redémarrage. Extrait réel :

```
+-- "app", jdk.internal.loader.ClassLoaders$AppClassLoader
      |
      +-- org.springframework.boot.devtools.restart.classloader.RestartClassLoader
      |     |
      |     +-- org.springframework.boot.tomcat.TomcatEmbeddedWebappClassLoader
      |
      +-- net.bytebuddy.utility.dispatcher.JavaDispatcher$DynamicClassLoader
      |
      +-- org.springframework.boot.devtools.restart.classloader.RestartClassLoader
            |
            +-- org.springframework.boot.tomcat.TomcatEmbeddedWebappClassLoader
```

Deux `RestartClassLoader` : l'ancien n'a pas encore été nettoyé par le ramasse-miettes, le nouveau sert. Avec
`jcmd <pid> VM.classloaders show-classes=true`, tu vérifies que `com.personal.task_tracker.task.service.TaskService`
(et son proxy `TaskService$$SpringCGLIB$$0`, voir [02. Beans et injection](02-beans-injection.md), section 4.4) est
chargé **une fois dans chaque** `RestartClassLoader`, tandis que les classes de Spring, comme `DispatcherServlet`,
sont sous `AppClassLoader`.

**La conséquence à connaître.** Une même classe chargée par deux chargeurs différents, ce sont **deux classes
différentes** pour la JVM. Si une bibliothèque garde en mémoire un objet créé avant le redémarrage (un cache, par
exemple), tu peux obtenir un `ClassCastException` du type "`TaskService` ne peut pas être converti en
`TaskService`". La documentation de DevTools décrit ces limites et comment désactiver le redémarrage pour
diagnostiquer.

**La source.** [Developer Tools](https://docs.spring.io/spring-boot/reference/using/devtools.html), sections
"Automatic Restart" et "Restart vs Reload".

### 4.2 Comment DevTools s'active, et pourquoi il disparaît en production

**Le mécanisme.** DevTools ne demande aucune annotation. Son jar contient un fichier
`META-INF/spring.factories` que Spring Boot lit au démarrage. Il y déclare notamment :

```properties
org.springframework.context.ApplicationListener=\
org.springframework.boot.devtools.logger.DevToolsLogFactory$Listener,\
org.springframework.boot.devtools.restart.RestartApplicationListener

org.springframework.boot.EnvironmentPostProcessor=\
org.springframework.boot.devtools.env.DevToolsHomePropertiesPostProcessor,\
org.springframework.boot.devtools.env.DevToolsPropertyDefaultsPostProcessor
```

`RestartApplicationListener` est appelé avant même la création du contexte. Si les conditions sont réunies, il
interrompt le démarrage sur le thread `main` et relance tout dans un thread `restartedMain`, avec le
`RestartClassLoader`. Le log DEBUG le dit : `Creating new Restarter for thread Thread[#3,main,5,main]` puis
`Immediately restarting application`. C'est pour cela que `jcmd` montre aussi `TaskTrackerApplication` chargée par
`AppClassLoader` : c'est la trace de ce tout premier lancement abandonné.

`DevToolsPropertyDefaultsPostProcessor` ajoute des **valeurs par défaut de développement**. Le log l'annonce :
`Devtools property defaults active! Set 'spring.devtools.add-properties' to 'false' to disable`. Avec Spring Boot 4,
chaque module apporte les siennes dans un fichier `META-INF/spring-devtools.properties`. Contenu réel de deux d'entre
eux :

```properties
# spring-boot-webmvc-4.1.1.jar
defaults.spring.mvc.log-resolved-exception=true

# spring-boot-autoconfigure-4.1.1.jar
defaults.spring.template.provider.cache=false
defaults.spring.web.error.include-binding-errors=always
defaults.spring.web.error.include-message=always
defaults.spring.web.error.include-stacktrace=always
defaults.spring.web.resources.cache.period=0
defaults.spring.web.resources.chain.cache=false
```

Conséquence : avec DevTools, les pages d'erreur par défaut de Spring Boot sont plus bavardes (message, détails,
pile d'appels) qu'en production. Le projet renvoie ses propres réponses d'erreur via `GlobalExceptionHandler`, mais
une erreur qui lui échapperait serait affichée différemment selon que DevTools est présent ou non.

**Quand DevTools est inactif.** Vérifié sur le projet dans les deux cas :

- **dans les tests** : les logs d'un `./mvnw test` montrent le thread `main`, jamais `restartedMain`, et aucun
  message `Devtools property defaults` ;
- **avec `java -jar`** : DevTools n'est même pas dans le jar (le plugin l'exclut, voir
  [01. Maven et le pom.xml](01-maven-pom.md), section 4.7). L'application démarre sur le thread `main`, et `jcmd`
  montre `LaunchedClassLoader` à la place des `RestartClassLoader`.

C'est le rôle du `<optional>true</optional>` et du scope `runtime` vus en section 3 : DevTools est présent pour
`spring-boot:run`, absent de tout ce qui part en production.

**Si on le retire.** Rien ne casse : l'application démarre normalement, sur le thread `main`. Tu perds le
redémarrage automatique (il faut arrêter et relancer à la main, environ 1,8 seconde de démarrage contre 0,3) et les
valeurs par défaut de développement.

**La source.** [Developer Tools](https://docs.spring.io/spring-boot/reference/using/devtools.html), sections
"Property Defaults" et "Automatic Restart".

### 4.3 Docker Compose ne garde aucun état : il retrouve tout par des étiquettes

**Le mécanisme.** Compose ne tient pas de registre de "ses" conteneurs. À chaque `docker compose up`, il calcule le
**nom de projet**, puis demande à Docker les conteneurs, volumes et réseaux qui portent l'étiquette
`com.docker.compose.project=<nom>`. Le nom de projet vient ici de la ligne `name: task-tracker` ; sans elle, ce
serait le nom du **dossier** qui contient le fichier compose. Tous les noms en découlent :

| Ressource | Nom réel | Construit à partir de |
|---|---|---|
| Conteneur | `task-tracker-postgres-1` | projet + service + numéro |
| Volume | `task-tracker_postgres_data` | projet + volume déclaré |
| Réseau | `task-tracker_default` | projet + `default` (créé automatiquement, jamais déclaré dans le fichier) |

Le réseau `default` est un réseau privé que Compose crée pour le projet : les services d'un même fichier s'y
joignent par leur nom (un futur service `api` pourrait joindre la base à l'adresse `postgres:5432`).

Fixer `name:` a une conséquence : le nom ne dépend plus du dossier. Une seconde copie du dépôt (un autre clone, un
*worktree* git) qui lancerait `docker compose up` viserait **le même** projet, donc le même conteneur et le même
volume de données.

**L'observer.** Sans rien démarrer, et sans afficher les variables d'environnement :

```bash
docker network ls --format '{{.Name}}' | grep task-tracker
docker volume inspect task-tracker_postgres_data --format '{{.Mountpoint}} {{json .Labels}}'
docker inspect task-tracker-postgres-1 --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
```

La dernière commande affiche les **deux** fichiers que Compose a fusionnés : `docker-compose.yml` et
`docker-compose.override.yml`. C'est la preuve concrète de l'override décrit en 2.2. La deuxième montre un
`Mountpoint` en `/var/lib/docker/volumes/task-tracker_postgres_data/_data` : sur macOS, ce chemin n'existe pas sur
ton disque, il est dans la machine virtuelle Linux de Docker Desktop. C'est pourquoi on manipule les données par
`docker compose exec ... psql` et non en ouvrant un dossier.

**La source.** [Specify a project name](https://docs.docker.com/compose/how-tos/project-name/),
[Networking in Compose](https://docs.docker.com/compose/how-tos/networking/),
[Merge Compose files](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/),
[Interpolation](https://docs.docker.com/reference/compose-file/interpolation/).

### 4.4 Les compagnons invisibles : Ryuk et le Maven Wrapper

**Ryuk, le nettoyeur de Testcontainers.** Pendant un `./mvnw test`, `docker ps` montre **deux** conteneurs, pas un :

```
clever_kilby postgres:16-alpine Up 2 seconds
testcontainers-ryuk-e52e94b1-6298-4088-aaa3-49af1c573af8 testcontainers/ryuk:0.14.0 Up 3 seconds
```

Le premier est la base de `TestcontainersConfiguration` (nom aléatoire attribué par Docker). Le second est démarré
par Testcontainers lui-même : Ryuk garde une connexion ouverte avec la JVM des tests et, quand elle se termine
(normalement ou pas, même après un arrêt brutal), supprime les conteneurs qu'elle avait créés. Sans lui, un test
interrompu laisserait des bases PostgreSQL orphelines tourner sur ta machine. Comment Spring branche ensuite la
datasource sur ce conteneur (`@ServiceConnection`) est expliqué dans [11. Tests](11-tests.md).
Source : [Testcontainers : Custom configuration](https://java.testcontainers.org/features/configuration/)
(section sur Ryuk).

**Le mode du Maven Wrapper.** `server/.mvn/wrapper/maven-wrapper.properties` contient
`distributionType=only-script` : tout est fait par le script `mvnw`, sans `maven-wrapper.jar`. La règle `:57` du
`.gitignore` est donc une précaution pour un autre mode du wrapper ; aucun jar n'est téléchargé dans ce projet.
Le détail de ce que fait le script (dossier `~/.m2/wrapper/dists`, `./mvnw -v`) est dans
[01. Maven et le pom.xml](01-maven-pom.md), section 4.8. Source : [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

---

## 5. À toi de jouer

**Exercice 1 : explorer le conteneur.** Base démarrée :

```sh
docker compose ps
docker compose logs postgres | tail -20
docker volume ls
docker compose exec postgres psql -U <ton-utilisateur> -d task_tracker -c '\dt'
```

À observer : le conteneur `task-tracker-postgres-1` (le nom vient de `name:` et du service), le volume
`task-tracker_postgres_data`, et les tables `app_user`, `task` et `flyway_schema_history`. La dernière commande est
en lecture seule.

**Exercice 2 : vérifier ce que git ignore.** Sans rien modifier :

```sh
git check-ignore -v .env docker-compose.override.yml server/src/main/resources/application-local.properties
git check-ignore -v server/src/main/resources/application-local.properties.example
git status --ignored --short | head -20
```

À observer : pour les trois premiers, la règle du `.gitignore` qui les exclut et son numéro de ligne. Le
`.example` ne sort rien : il **n'est pas** ignoré, donc il peut être commité.

**Exercice 3 : le message de variable manquante.** Depuis la racine, lance
`POSTGRES_USER= docker compose config --quiet`. Une variable du shell l'emporte sur `.env`, et la forme `:?` rejette
aussi une valeur vide. À observer : le message `POSTGRES_USER is missing, to define in .env`, sans que rien ne
démarre. `docker compose config` sert à afficher la configuration finale (base + override fusionnés) ; `--quiet`
se contente de la valider. Sans `--quiet`, elle s'afficherait avec les valeurs de `.env` en clair : ne la copie
nulle part.

**Exercice 4 : lire l'historique comme un récit.** Lance `git log --stat --reverse` et parcours les commits du plus
ancien au plus récent, en parallèle du `JOURNAL.md`. Pour chaque commit, retrouve l'entrée du journal qui en
explique les choix.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Connection refused` sur `localhost:5432` au démarrage du serveur | Conteneur arrêté, ou override absent (aucun port exposé) | `docker compose ps`, copier `docker-compose.override.example.yml` |
| `port is already allocated` au `docker compose up` | Un autre PostgreSQL occupe déjà le 5432 | Changer le port de gauche dans l'override (`"5433:5432"`) et dans la datasource locale |
| Compose refuse de démarrer : `POSTGRES_USER is missing` | `.env` absent ou incomplet | Copier `.env.example` en `.env` et le compléter |
| Les données ont disparu | `docker compose down -v` a supprimé le volume | Normal : `-v` efface les volumes. Sans `-v`, les données restent |
| Les tests échouent tous au démarrage | Docker n'est pas lancé : Testcontainers ne peut pas créer son conteneur | Démarrer Docker |
| Erreur de compilation sur une syntaxe Java récente | `JAVA_HOME` pointe vers un JDK plus ancien que 25 | `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` (macOS) |
| Un secret apparaît dans `git status` | Fichier sensible non couvert par `.gitignore` | Ajouter la règle **avant** de commiter. Déjà commité : le retirer ne suffit pas, il faut changer le secret |
| Comportement différent entre deux machines | Versions divergentes (Maven, image Docker) | `./mvnw` plutôt que `mvn`, image avec version fixée |

---

## 7. Pour aller plus loin

- **Image Docker de l'application** ⏳ : `./mvnw spring-boot:build-image` construit une image de l'API (via les
  *buildpacks*), pour la déployer ou la lancer à côté de PostgreSQL dans le même compose.
- **Spring Boot Docker Compose support** : la dépendance `spring-boot-docker-compose` peut démarrer le compose et
  configurer la datasource automatiquement au lancement de l'application.
- **Intégration continue** ⏳ : lancer `./mvnw clean test` à chaque push (GitHub Actions, GitLab CI), Docker étant
  disponible sur les runners pour Testcontainers.
- **Hooks git** : vérifier le format du message de commit ou l'absence de secret avant chaque commit.
- **Gestion des secrets en production** : coffre de secrets (Vault, gestionnaire du cloud) plutôt que des fichiers.
- **ADR** : formaliser les décisions importantes du journal en fiches courtes (contexte, décision, conséquences).
- **Documentation officielle** : les liens de la section 4 (Developer Tools, nom de projet, réseau, fusion et
  interpolation Compose, Ryuk, Maven Wrapper), la « Compose file reference » de Docker Docs, et
  conventionalcommits.org.

---

## 8. Quiz

**1. Quelle différence entre `docker compose down` et `docker compose down -v` ?**

<details><summary>Réponse</summary>

Les deux arrêtent et suppriment les conteneurs. `-v` supprime en plus les volumes, donc toutes les données de la
base locale.

</details>

**2. Pourquoi le port 5432 est-il déclaré dans un override non commité plutôt que dans `docker-compose.yml` ?**

<details><summary>Réponse</summary>

Parce qu'il dépend de la machine : le port peut déjà être occupé. Chacun choisit le sien sans modifier le fichier
commun.

</details>

**3. À quoi sert un fichier `.example`, et pourquoi est-il commité alors que la copie ne l'est pas ?**

<details><summary>Réponse</summary>

Il documente les clés à renseigner, sans valeur. Il ne contient rien de sensible, donc il est partagé. La copie
complétée contient les vraies valeurs et reste sur la machine.

</details>

**4. Un mot de passe a été commité par erreur. Suffit-il de supprimer le fichier et de commiter ?**

<details><summary>Réponse</summary>

Non. Il reste dans l'historique git. Il faut considérer le secret comme compromis et le changer ; réécrire
l'historique est un complément, pas une solution suffisante.

</details>

**5. Pourquoi utiliser `./mvnw` plutôt que `mvn` ?**

<details><summary>Réponse</summary>

Le wrapper utilise la version de Maven fixée par le projet, la même pour tout le monde, sans installation préalable.

</details>

**6. Qu'apporte `JOURNAL.md` que le code ne dit pas ?**

<details><summary>Réponse</summary>

Le pourquoi : les raisons d'un choix, les alternatives écartées, les pièges rencontrés et les décisions revues
ensuite.

</details>
