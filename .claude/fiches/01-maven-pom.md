# 01. Maven et le pom.xml

**Ce que tu vas comprendre**
- Ce que fait Maven et pourquoi on ne télécharge jamais un `.jar` à la main
- Comment se lit un `pom.xml`, bloc par bloc
- Pourquoi la plupart des dépendances du projet n'ont pas de `<version>`
- Ce qu'est un starter Spring Boot et ce que changent les scopes (`test`, `runtime`, `optional`)
- Les commandes Maven à connaître au quotidien
- Ce qui se passe sous le capot : le pom effectif, la résolution des versions, l'option `-parameters`, le jar
  exécutable et son lanceur, le wrapper, et ce qui casse quand on retire l'un d'eux

**Prérequis** : aucun. C'est la première fiche. La suivante est [02. Beans et injection](02-beans-injection.md).

---

## 1. Le problème que ça résout

Ton application utilise du code écrit par d'autres : Spring, Hibernate, le driver PostgreSQL, Jackson pour le
JSON... Chacune de ces bibliothèques dépend elle-même d'autres bibliothèques, dans des versions précises.

Sans outil, il faudrait :

1. télécharger chaque `.jar` à la main, puis ceux dont il dépend, puis ceux dont **eux** dépendent ;
2. vérifier que toutes ces versions sont compatibles entre elles ;
3. écrire une commande `javac` avec un classpath de plusieurs centaines d'entrées ;
4. refaire tout ça sur chaque poste et sur le serveur d'intégration continue.

**Maven** fait tout cela à partir d'un seul fichier déclaratif : le `pom.xml` (Project Object Model). Tu décris
**ce dont** le projet a besoin, Maven se charge du **comment**.

Analogie : le `pom.xml` est une liste de courses. Tu écris "farine, œufs, lait", Maven va au magasin (le dépôt
Maven Central), achète aussi la levure dont la recette a besoin sans que tu l'aies écrite, et range tout dans ton
placard (`~/.m2/repository`) pour la prochaine fois.

Équivalents dans d'autres écosystèmes : `package.json` + npm (ton dossier `client/` en est un exemple), Gradle en
Java/Kotlin, `requirements.txt` + pip en Python.

---

## 2. Les concepts

### 2.1 Les coordonnées : groupId, artifactId, version

Toute bibliothèque Maven (et ton projet lui-même) est identifiée par trois valeurs, souvent abrégées **GAV** :

```xml
<groupId>org.postgresql</groupId>       <!-- l'organisation, à l'envers comme un nom de domaine -->
<artifactId>postgresql</artifactId>     <!-- le nom du module -->
<version>42.7.14</version>              <!-- la version -->
```

### 2.2 Les dépendances et la transitivité

Une `<dependency>` déclare "j'ai besoin de cette bibliothèque". Si elle-même en déclare d'autres, Maven les ajoute
automatiquement : ce sont les **dépendances transitives**. Tu n'en vois qu'une petite partie dans le pom, mais
le projet en utilise des dizaines.

```bash
./mvnw dependency:tree     # affiche l'arbre complet
```

### 2.3 Le parent et la gestion des versions

Un pom peut **hériter** d'un autre pom, le parent. `spring-boot-starter-parent` fournit, entre autres :

- une section `<dependencyManagement>` qui fixe la version de centaines de bibliothèques **testées ensemble** par
  l'équipe Spring Boot ;
- une configuration par défaut des plugins (encodage, version de Java...).

Conséquence : quand une dépendance est "gérée" par le parent, tu ne mets pas de `<version>`. Tu bénéficies d'un
ensemble cohérent, et monter la version du parent monte tout le reste d'un coup.

Un **BOM** (Bill Of Materials) est un pom qui ne contient qu'une liste de versions cohérentes pour une famille de
bibliothèques (Jackson, Testcontainers...). Le parent Spring Boot en importe plusieurs.

### 2.4 Surcharger une version gérée

Le parent expose chaque version sous forme de **propriété**. La redéfinir dans `<properties>` change la version
utilisée sans toucher au reste :

```xml
<properties>
    <postgresql.version>42.7.14</postgresql.version>
</properties>
```

C'est utile pour récupérer un correctif de sécurité avant la prochaine version de Spring Boot. C'est aussi un
risque : tu sors de l'ensemble testé par Spring. À faire en connaissance de cause, et à retirer quand le parent
rattrape la version.

### 2.5 Les starters

Un **starter** est une dépendance qui ne contient presque pas de code : elle regroupe les bibliothèques
nécessaires à une fonctionnalité **et** l'auto-configuration qui va avec (voir
[02. Beans et injection](02-beans-injection.md)).

Ajouter `spring-boot-starter-data-jpa`, c'est obtenir Hibernate, Spring Data, un pool de connexions, et la
configuration qui les relie, en une ligne.

### 2.6 Les scopes

Le scope dit **quand** une dépendance est disponible :

| Scope | Compilation du code | Exécution | Tests | Exemple typique |
|---|---|---|---|---|
| `compile` (défaut) | oui | oui | oui | Spring Web |
| `runtime` | non | oui | oui | un driver JDBC : ton code n'appelle jamais ses classes directement |
| `test` | non (code principal) | non | oui | JUnit, Testcontainers |
| `provided` | oui | non (fourni par l'environnement) | oui | API d'un serveur d'application |

`<optional>true</optional>` est différent : la dépendance n'est pas transmise aux projets qui dépendraient du
tien. Cas particulier de DevTools : le plugin Spring Boot l'exclut en plus par défaut du jar exécutable.

### 2.7 Les plugins et le cycle de vie

Maven fonctionne par **phases** exécutées dans l'ordre : `validate` → `compile` → `test` → `package` → `verify`
→ `install`. Demander `package` exécute toutes les phases précédentes, donc les tests.

Les **plugins** ajoutent du travail à ces phases ou de nouvelles commandes. `spring-boot-maven-plugin` transforme
le jar en jar **exécutable** (toutes les dépendances embarquées, lancement par `java -jar`) et fournit
`spring-boot:run`.

### 2.8 Le Maven Wrapper

`mvnw` (et `mvnw.cmd` sous Windows) télécharge et utilise une version précise de Maven. Tout le monde, y compris
la CI, construit avec la même version, sans rien installer.

### 2.9 SNAPSHOT

Une version qui finit par `-SNAPSHOT` signifie "en cours de développement, peut changer à tout moment". C'est
normal pour **ton** projet tant qu'il n'est pas publié. C'est à proscrire pour une **dépendance** : le même numéro
pourrait désigner un code différent demain, et le build ne serait plus reproductible.

---

## 3. Dans le projet

### Le parent et l'identité du projet

`server/pom.xml:8-16`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>          <!-- LA version qui pilote presque toutes les autres -->
    <relativePath/>                   <!-- le parent vient du dépôt Maven, pas d'un dossier voisin -->
</parent>
<groupId>com.personal</groupId>
<artifactId>task-tracker</artifactId>
<version>0.0.1-SNAPSHOT</version>     <!-- ton projet : SNAPSHOT acceptable -->
```

### Les propriétés

`server/pom.xml:18-22`

```xml
<properties>
    <java.version>25</java.version>                       <!-- lue par le parent pour configurer le compilateur -->
    <jackson-bom.version>3.1.7</jackson-bom.version>      <!-- surcharge de la version de Jackson gérée par le parent -->
    <postgresql.version>42.7.14</postgresql.version>      <!-- surcharge de la version du driver -->
</properties>
```

Les deux dernières lignes sont des surcharges volontaires (paragraphe 2.4).

### Starters sans version, bibliothèques tierces avec version

`server/pom.xml:26-38`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>   <!-- pas de version : gérée par le parent -->
</dependency>
...
<dependency>
    <groupId>org.openapitools</groupId>
    <artifactId>jackson-databind-nullable</artifactId>
    <version>0.2.11</version>                             <!-- inconnue du parent : version obligatoire -->
</dependency>
```

Même chose pour `rest-assured` (`server/pom.xml:113-118`, version `6.0.1`) : le parent ne la gère pas, il faut
la préciser.

### Les scopes en situation

| Lignes | Dépendance | Scope | Pourquoi |
|---|---|---|---|
| `server/pom.xml:55-59` | `postgresql` | `runtime` | Ton code ne manipule que des interfaces JDBC/JPA ; le driver n'est chargé qu'à l'exécution |
| `server/pom.xml:70-75` | `spring-boot-devtools` | `runtime` + `optional` | Outil de développement, non transmis et exclu par défaut du jar exécutable |
| `server/pom.xml:78-118` | tout le bloc `<!-- Tests -->` | `test` | Absent du jar final |

### Les starters de test

`server/pom.xml:78-97` : chaque starter principal a son pendant de test (`-webmvc-test`, `-validation-test`,
`-security-test`, `-data-jpa-test`). Ce découpage des starters de test par fonctionnalité accompagne la
modularisation de Spring Boot 4 ; dans les versions 2 et 3, on utilisait surtout un seul `spring-boot-starter-test`.

### Le plugin

`server/pom.xml:121-128` : seul `spring-boot-maven-plugin` est déclaré, sans version ni configuration. Tout le
reste vient du parent.

### Les commandes du README

`README.md` utilise deux commandes, à lancer depuis `server/` :

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # démarre l'application avec le profil local
./mvnw clean test                                         # supprime target/ puis compile et lance les tests
```

---

## 4. Sous le capot

Les sections précédentes disent **ce que** fait Maven. Celle-ci montre **comment**, et surtout comment le **voir**
dans le projet. Maven n'a rien de magique : tout ce qu'il fait est écrit dans des fichiers `.pom` et `.jar` que tu
peux ouvrir dans `~/.m2/repository`. Chaque sous-section suit le même plan que la fiche 02 : le mécanisme réel,
comment l'observer, la source, et ce qui se passe si on retire l'élément.

Toutes les commandes se lancent depuis `server/`. Les chiffres cités ont été relevés sur le projet en Spring Boot
4.1.1 ; ils bougeront à chaque montée de version.

### 4.1 Le parent : deux étages, deux rôles

**Le mécanisme.** Ton pom hérite de `spring-boot-starter-parent`, qui hérite lui-même de
`spring-boot-dependencies`. Chaque étage a un rôle distinct :

| Étage | Ce qu'il contient | Taille réelle (4.1.1) |
|---|---|---|
| `spring-boot-dependencies` | une `<dependencyManagement>` (les versions) et une `<pluginManagement>` (les versions des plugins) | 3 761 lignes, 652 entrées de dépendances dont 46 BOM importés, 30 plugins |
| `spring-boot-starter-parent` | les réglages de build : propriétés, configuration des plugins, filtrage des ressources | 387 lignes |

Ce que `spring-boot-starter-parent` règle pour toi, extrait de son pom :

```xml
<properties>
    <java.version>17</java.version>                              <!-- valeur par défaut, que ton pom remplace par 25 -->
    <resource.delimiter>@</resource.delimiter>
    <maven.compiler.release>${java.version}</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    ...
</properties>
```

Ta ligne `<java.version>25</java.version>` ne fait donc rien par elle-même : c'est le parent qui la recopie dans
`maven.compiler.release`, la propriété que lit vraiment le plugin du compilateur.

Le parent active aussi, sans que tu le voies :

- **l'option `-parameters` du compilateur** (détaillée en 4.5) ;
- **le filtrage des fichiers `application*.properties` et `application*.yml`** : au moment de les copier dans
  `target/classes`, Maven remplace `@nom.de.propriété@` par la valeur de la propriété Maven. Le délimiteur est `@`
  et non `${...}`, pour ne pas entrer en collision avec les `${...}` de Spring. Le projet n'en utilise pas encore ;
- **l'exécution `repackage` de `spring-boot-maven-plugin`** (détaillée en 4.7). Sans elle, déclarer le plugin dans
  ton pom ne produirait pas de jar exécutable.

**L'observer.** Le pom effectif est ton pom après fusion avec ses deux parents :

```bash
./mvnw help:effective-pom -Doutput=target/effective-pom.xml
grep -n 'maven.compiler.release' target/effective-pom.xml     # 25 : ta valeur, recopiée par le parent
```

Le fichier fait environ 10 700 lignes pour un pom de 129. Les poms des parents sont lisibles directement :
`~/.m2/repository/org/springframework/boot/spring-boot-starter-parent/4.1.1/spring-boot-starter-parent-4.1.1.pom`.

**Sans le parent ?** C'est possible (entreprise qui impose son propre parent, par exemple). La documentation du
plugin Spring Boot l'explique : on importe `spring-boot-dependencies` comme BOM et on garde la gestion des
versions, **mais pas la configuration des plugins**. Il faudrait alors réécrire à la main tout ce qui précède :
`-parameters`, l'encodage, le filtrage, l'exécution `repackage`.

**La source.** [Introduction to the POM](https://maven.apache.org/guides/introduction/introduction-to-the-pom.html)
(héritage et pom effectif),
[Spring Boot Maven Plugin : Using the Plugin](https://docs.spring.io/spring-boot/maven-plugin/using.html)
(ce qu'apporte le parent, et comment s'en passer),
[help:effective-pom](https://maven.apache.org/plugins/maven-help-plugin/effective-pom-mojo.html).

### 4.2 `dependencyManagement` et BOM : une table de versions, pas des dépendances

**Le mécanisme.** Une entrée de `<dependencyManagement>` **n'ajoute rien** au projet. Elle dit seulement : "si
cette bibliothèque apparaît quelque part dans l'arbre, prends cette version". C'est pourquoi les 652 entrées de
`spring-boot-dependencies` ne te font pas télécharger 652 bibliothèques.

Chaque version y est écrite sous forme de propriété :

```xml
<postgresql.version>42.7.13</postgresql.version>      <!-- dans spring-boot-dependencies -->
...
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${postgresql.version}</version>
</dependency>
```

Ta surcharge `<postgresql.version>42.7.14</postgresql.version>` fonctionne parce que les propriétés de l'enfant
l'emportent sur celles du parent : la même ligne `${postgresql.version}` est évaluée avec ta valeur.

Un **BOM** importé (`<type>pom</type>` + `<scope>import</scope>`) recopie la `dependencyManagement` d'un autre pom.
Pour Jackson, `spring-boot-dependencies` importe deux BOM : `com.fasterxml.jackson:jackson-bom` (Jackson 2) et
`tools.jackson:jackson-bom` (Jackson 3, version `${jackson-bom.version}`, 3.1.5 par défaut). Ta propriété
`jackson-bom.version` à 3.1.7 change donc la version de **toute** la famille Jackson 3 d'un coup.

**L'observer.** Dans le pom effectif de 4.1 :

```bash
grep -n -A1 '<artifactId>postgresql</artifactId>' target/effective-pom.xml      # 42.7.14, pas 42.7.13
grep -n -A1 '<artifactId>jackson-databind</artifactId>' target/effective-pom.xml
```

La seconde commande renvoie **deux** entrées : `2.21.5` (Jackson 2, groupe `com.fasterxml.jackson.core`) et `3.1.7`
(Jackson 3, groupe `tools.jackson.core`). Les deux familles cohabitent dans la table, mais seul le
`jackson-databind` 3 apparaît dans l'arbre du projet (voir 4.3) : une entrée de la table qu'aucun chemin ne réclame
reste lettre morte. Seule exception visible, `jackson-annotations` 2.21, que Jackson 3 réutilise.

**La source.** [Introduction to the Dependency Mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html),
sections "Dependency Management" et "Importing Dependencies".

### 4.3 La résolution des transitives : une seule version par bibliothèque

**Le mécanisme.** Plusieurs chemins de l'arbre peuvent réclamer la même bibliothèque, dans des versions différentes.
Le classpath ne peut en contenir qu'**une**. Maven tranche ainsi :

1. si la bibliothèque est dans la `dependencyManagement`, cette version gagne, quoi que demandent les chemins ;
2. sinon, **la plus proche gagne** (*nearest definition*) : la version du chemin le plus court depuis ton pom ;
3. à égalité de profondeur, la première déclarée dans le pom gagne.

Le scope suit une logique voisine : une bibliothèque nécessaire à la compilation par un chemin reste en `compile`,
même si un autre chemin la tire en `runtime`.

**L'observer.** `./mvnw dependency:tree -Dverbose` montre les décisions. Trois extraits réels du projet :

```
\- (tools.jackson.core:jackson-databind:jar:3.1.7:compile - version managed from 3.1.5; omitted for duplicate)
\- tools.jackson.core:jackson-databind:jar:3.1.7:compile (version managed from 3.1.1)
\- (org.slf4j:slf4j-api:jar:2.0.18:compile - version managed from 2.0.17; omitted for duplicate)
```

Lis-les ainsi :

- `spring-boot-jackson` demande `jackson-databind` 3.1.5, `flyway-core` demande 3.1.1 : la table de versions
  (avec ta surcharge) impose 3.1.7 aux deux (règle 1) ;
- `omitted for duplicate` : ce chemin réclame une bibliothèque déjà retenue ailleurs, il n'en ajoute pas une
  seconde copie. Le projet en compte 201, et **zéro** `omitted for conflict` : la table de versions évite tout
  conflit.

Conséquence surprenante dans l'arbre simple (`./mvnw dependency:tree`, sans `-Dverbose`) : `jackson-databind`
apparaît sous `flyway-database-postgresql` → `flyway-core`, et non sous le starter web. Ce n'est pas Flyway qui
"apporte" Jackson au projet : ce chemin (profondeur 3) est simplement plus court que celui du starter web
(profondeur 4), Maven y accroche donc la bibliothèque. De même, `spring-boot` s'affiche sous `spring-boot-devtools`
(profondeur 2), en scope `compile` : retirer DevTools ne le ferait pas disparaître, il reste tiré par tous les
starters. **La position dans l'arbre dit où Maven a rangé la bibliothèque, pas qui en a besoin.**

C'est ce qui rend dangereux un `<version>` forcé sur une transitive : tu sors de la table testée, et la règle 1
s'applique avec **ta** version à tous les chemins.

**La source.** [Introduction to the Dependency Mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html),
section "Transitive Dependencies" (*dependency mediation*),
[dependency:tree](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html).

### 4.4 Un starter, c'est un pom avec un jar vide

**Le mécanisme.** Ouvre le jar du starter JPA :

```bash
unzip -l ~/.m2/repository/org/springframework/boot/spring-boot-starter-data-jpa/4.1.1/spring-boot-starter-data-jpa-4.1.1.jar
```

```
        0  02-01-1980 00:00   META-INF/
      263  02-01-1980 00:00   META-INF/MANIFEST.MF
    11359  02-01-1980 00:00   META-INF/LICENSE.txt
      210  02-01-1980 00:00   META-INF/NOTICE.txt
```

Aucune classe. Son manifeste le dit d'ailleurs : `Spring-Boot-Jar-Type: dependencies-starter`. Tout l'intérêt du
starter est dans son `.pom`, qui déclare quatre dépendances : `spring-boot-starter`, `spring-boot-starter-jdbc`,
`spring-boot-data-jpa` et `spring-boot-jdbc`. Le code (dont les classes d'auto-configuration) vit dans ces modules :
par exemple `spring-boot-data-jpa-4.1.1.jar` contient 7 classes et le fichier qui liste
`DataJpaRepositoriesAutoConfiguration`. La suite du mécanisme (comment Boot lit ce fichier et évalue les conditions)
est dans [02. Beans et injection](02-beans-injection.md), section 4.6.

**Pourquoi c'est nécessaire, ou pas.** Un starter n'est qu'un raccourci. Tu pourrais le remplacer par ses quatre
dépendances et obtenir exactement le même classpath. L'intérêt est ailleurs : l'équipe Spring Boot choisit pour toi
**quels** modules vont ensemble, et le jour où le découpage change (comme en Spring Boot 4, où l'auto-configuration
a été éclatée en modules par domaine), ton pom n'a pas à bouger.

**L'observer.** `./mvnw dependency:tree` : sous `spring-boot-starter-data-jpa`, tu trouves bien `HikariCP` (via
`spring-boot-starter-jdbc`) et `hibernate-core` (via `spring-boot-data-jpa` → `spring-boot-hibernate`).

**La source.** [Build Systems : Starters](https://docs.spring.io/spring-boot/reference/using/build-systems.html).

### 4.5 L'option `-parameters` : pourquoi `@PathVariable Long taskId` suffit

**Le mécanisme.** Par défaut, `javac` ne conserve pas le **nom** des paramètres de méthode dans le `.class` : seul
leur type compte pour la JVM. Avec l'option `-parameters`, il ajoute un attribut `MethodParameters` qui les liste.
Spring s'en sert à deux endroits du projet :

- `@PathVariable Long taskId` sans nom explicite : Spring lit le nom du paramètre, `taskId`, pour savoir quelle
  partie de l'URL `/api/tasks/{taskId}` y injecter ;
- le départage de deux beans du même type par le nom du paramètre de constructeur
  (voir [02. Beans et injection](02-beans-injection.md), section 4.2).

Ton pom ne mentionne jamais cette option : elle vient de la `pluginManagement` du parent
(`<parameters>true</parameters>` sur `maven-compiler-plugin`).

**L'observer.**

- Dans le log de compilation, la ligne
  `Compiling 35 source files with javac [debug parameters release 25] to target/classes` : `parameters` est là.
- Dans le bytecode, avec l'outil `javap` fourni avec le JDK :
  ```bash
  javap -v -cp target/classes com.personal.task_tracker.user.service.UserService | grep -A4 'MethodParameters'
  ```
  ```
      MethodParameters:
        Name                           Flags
        repository
        passwordEncoder
  ```
  Ce sont les noms des paramètres du constructeur, tels qu'écrits dans le source.

**Si on le retire.** Vérifié sur le projet : en ajoutant dans le pom un `maven-compiler-plugin` configuré avec
`<parameters>false</parameters>`, tout compile, le contexte démarre, mais `GET /api/users/{id}` répond **500**.
Le log affiche :

```
java.lang.IllegalArgumentException: Name for argument of type [java.lang.Long] not specified, and parameter name information not available via reflection. Ensure that the compiler uses the '-parameters' flag.
```

Le message est explicite. Les alternatives seraient d'écrire `@PathVariable("taskId")` partout, ou de garder
l'option : c'est le choix du parent. À noter : l'option `debug` (`-g`) était toujours active pendant l'expérience,
Spring ne se rabat donc pas sur les informations de débogage pour retrouver les noms.

**La source.** [maven-compiler-plugin : parameters](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html),
[javac](https://docs.oracle.com/en/java/javase/25/docs/specs/man/javac.html) (option `-parameters`),
[Method Arguments (Spring MVC)](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/arguments.html).

### 4.6 Le cycle de vie : des phases vides, remplies par des plugins

**Le mécanisme.** Une phase (`compile`, `test`, `package`...) ne fait rien par elle-même. C'est un point d'accroche :
le type de packaging (`jar` par défaut) y attache des **goals** de plugins. `./mvnw package` exécute, dans l'ordre,
tous les goals attachés aux phases jusqu'à `package` inclus. `clean` appartient à un **autre** cycle de vie, d'où
le `./mvnw clean test` en deux mots.

**L'observer.** Lance `./mvnw package -DskipTests` et regarde les lignes `---` du log. Sortie réelle :

```
[INFO] --- resources:3.5.0:resources (default-resources) @ task-tracker ---
[INFO] --- compiler:3.15.0:compile (default-compile) @ task-tracker ---
[INFO] --- resources:3.5.0:testResources (default-testResources) @ task-tracker ---
[INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ task-tracker ---
[INFO] --- surefire:3.5.6:test (default-test) @ task-tracker ---
[INFO] --- jar:3.5.1:jar (default-jar) @ task-tracker ---
[INFO] --- spring-boot:4.1.1:repackage (repackage) @ task-tracker ---
```

Chaque ligne se lit `plugin:version:goal (identifiant de l'exécution)`. Les `default-...` viennent du packaging
`jar` ; la dernière, `repackage`, vient du parent. Les versions (3.15.0, 3.5.6...) viennent de la
`pluginManagement` de `spring-boot-dependencies`. Note que `surefire:test` s'exécute même avec `-DskipTests` : le
plugin démarre, affiche `Tests are skipped.` et passe la main.

**La source.** [Introduction to the Build Lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)
(dont la liste des goals attachés par défaut au packaging `jar`).

### 4.7 Le jar exécutable : un jar qui contient des jars

**Le mécanisme.** `jar:jar` produit d'abord un jar ordinaire, avec tes seules classes. Puis `spring-boot:repackage`
le renomme en `.jar.original` et construit à sa place un jar qui contient **tout** :

```
task-tracker-0.0.1-SNAPSHOT.jar
├── META-INF/MANIFEST.MF
├── org/springframework/boot/loader/...   ← le lanceur de Spring Boot, en classes normales
└── BOOT-INF/
    ├── classes/                         ← tes classes et application.properties
    ├── lib/                             ← les 85 jars de dépendances, stockés tels quels
    ├── classpath.idx                    ← l'ordre du classpath
    └── layers.idx                       ← le découpage en couches pour les images Docker
```

Problème : Java ne sait pas lire un jar **à l'intérieur** d'un jar. C'est pourquoi le manifeste ne désigne pas ta
classe comme point d'entrée :

```
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.personal.task_tracker.TaskTrackerApplication
```

`java -jar` lance `JarLauncher` (une classe de Spring Boot copiée à la racine du jar). Celui-ci crée un chargeur de
classes capable de lire `BOOT-INF/classes` et chaque jar de `BOOT-INF/lib`, puis appelle le `main` de la
`Start-Class`. C'est aussi pour cela que les jars de `BOOT-INF/lib` sont **stockés sans compression** (`Stored` dans
`unzip -v`) : le lanceur peut les lire sur place sans les décompresser.

Tu n'as écrit nulle part le nom de ta classe principale : le plugin l'a trouvée en cherchant la classe qui porte
une méthode `main`.

**L'observer.**

```bash
./mvnw package -DskipTests
unzip -p target/task-tracker-0.0.1-SNAPSHOT.jar META-INF/MANIFEST.MF
unzip -l target/task-tracker-0.0.1-SNAPSHOT.jar | grep -c 'BOOT-INF/lib/.*\.jar'      # 85
unzip -l target/task-tracker-0.0.1-SNAPSHOT.jar | grep devtools                      # rien : exclu (2.6)
java -jar target/task-tracker-0.0.1-SNAPSHOT.jar.original
```

La dernière commande échoue : le jar d'origine n'a pas de `Main-Class`. Sur une JVM en français, le message est
`aucun attribut manifest principal dans target/task-tracker-0.0.1-SNAPSHOT.jar.original` (en anglais :
`no main manifest attribute`).

Pour voir le chargeur de classes à l'œuvre, lance le jar (avec une base disponible), puis, dans un autre terminal,
`jcmd <pid> VM.classloaders`. Extrait réel :

```
+-- "app", jdk.internal.loader.ClassLoaders$AppClassLoader
      |
      +-- org.springframework.boot.loader.launch.LaunchedClassLoader
            |
            +-- org.springframework.boot.tomcat.TomcatEmbeddedWebappClassLoader
```

Le chargeur standard de Java (`app`) ne connaît que le jar externe ; c'est `LaunchedClassLoader`, créé par
`JarLauncher`, qui charge tes classes et les 85 bibliothèques.

**Si on le retire.** Sans `spring-boot-maven-plugin`, tu n'obtiens que l'équivalent du `.jar.original` : 78
entrées, tes classes seules, impossible à lancer avec `java -jar`. Il faudrait fournir toi-même les 85 jars et un
classpath à la main (`java -cp ...`), exactement le problème décrit en section 1. Tu perdrais aussi
`spring-boot:run`.

**La source.** [The Executable Jar Format](https://docs.spring.io/spring-boot/specification/executable-jar/index.html),
[Nested JARs](https://docs.spring.io/spring-boot/specification/executable-jar/nested-jars.html),
[Launching Executable Jars](https://docs.spring.io/spring-boot/specification/executable-jar/launching.html),
[Packaging Executable Archives](https://docs.spring.io/spring-boot/maven-plugin/packaging.html).

### 4.8 Le wrapper : un script qui télécharge Maven

**Le mécanisme.** Le projet ne contient pas Maven, seulement `server/mvnw` et
`server/.mvn/wrapper/maven-wrapper.properties` :

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

`mvnw` est un script shell. Il lit `distributionUrl`, calcule un dossier
`~/.m2/wrapper/dists/apache-maven-3.9.16/<empreinte>`, y télécharge et décompresse Maven si le dossier n'existe pas,
puis lui passe la main avec tes arguments. `distributionType=only-script` signifie que tout est fait par le script :
aucun `maven-wrapper.jar` n'est nécessaire (la ligne du `.gitignore` qui l'ignore est une précaution pour un autre
mode du wrapper).

**L'observer.**

```bash
./mvnw -v
```

```
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: /Users/<toi>/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f
Java version: 25.0.4.1, vendor: Eclipse Adoptium, runtime: ...
```

`Maven home` pointe dans `~/.m2/wrapper/dists`, pas dans une installation système. La ligne `Java version` rappelle
que le JDK, lui, n'est pas fourni : c'est celui de `JAVA_HOME`.

**Si on le retire.** Il faudrait installer Maven sur chaque poste et sur la CI, et rien ne garantirait la même
version partout. Changer de version de Maven pour tout le monde revient à modifier une ligne de
`maven-wrapper.properties`.

**La source.** [Maven Wrapper](https://maven.apache.org/tools/wrapper/).

---

## 5. À toi de jouer

**Exercice 1 : voir ce qui est vraiment utilisé.**
Depuis `server/`, lance `./mvnw dependency:tree`. Repère sous quel starter apparaît `hibernate-core`, puis
`HikariCP`.
*À observer* : aucune des deux n'est écrite dans le pom ; elles arrivent par `spring-boot-starter-data-jpa`.

**Exercice 2 : retrouver la version qu'impose le parent.**
Lance `./mvnw help:effective-pom -Doutput=target/effective-pom.xml` et cherche `hibernate-core`, puis
`jackson-databind` dans le fichier produit.
*À observer* : le "pom effectif" est le pom après fusion avec le parent. La version de Jackson y reflète ta
surcharge `jackson-bom.version`.

**Exercice 3 : comprendre un scope.**
Dans un fichier de `src/main/java`, tente d'importer une classe de RestAssured (`io.restassured.RestAssured`) puis
lance `./mvnw compile`.
*À observer* : échec de compilation, car le scope `test` rend la bibliothèque invisible au code principal.
Annule la modification ensuite.

**Exercice 4 : le cycle de vie.**
Lance `./mvnw package` et regarde le contenu de `server/target/`.
*À observer* : les tests ont été lancés (la phase `test` précède `package`) et un jar exécutable est produit.
`./mvnw package -DskipTests` saute les tests, à n'utiliser qu'en connaissance de cause.

---

## 6. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Could not find artifact ...` | Faute de frappe dans un GAV, ou version inexistante | Vérifier les coordonnées sur Maven Central |
| `ClassNotFoundException` à l'exécution alors que ça compile | Dépendance manquante à l'exécution, ou scope mal choisi | Vérifier le scope ; `dependency:tree` |
| `NoSuchMethodError` / comportement étrange après un ajout | Deux versions incompatibles d'une même lib (conflit de transitives) | `dependency:tree -Dverbose`, retirer la `<version>` forcée, laisser le parent gérer |
| L'IDE ne voit pas une dépendance ajoutée | Projet Maven non rechargé | "Reload Maven project" dans l'IDE |
| Le build marche chez toi, pas sur la CI | Version de Maven ou de JDK différente | Toujours passer par `./mvnw`, vérifier `java -version` |
| Tests qui échouent à cause de vieux fichiers | Résidus dans `target/` (migration renommée par exemple) | `./mvnw clean ...` |

---

## 7. Pour aller plus loin

- **Gradle** : l'alternative à Maven, scripts en Kotlin ou Groovy, plus flexible et souvent plus rapide.
- **Multi-module** : un pom parent qui agrège plusieurs modules (`api`, `domain`, `infra`).
- **Dépendances vulnérables** : plugin OWASP Dependency-Check, Dependabot, Renovate.
- **Image Docker** ⏳ : `./mvnw spring-boot:build-image` construit une image sans Dockerfile.
- **GraalVM native image** ⏳ : compilation en exécutable natif via le plugin Spring Boot.
- **Docs officielles** : en plus des liens de la section 4 (mécanisme des dépendances, cycle de vie, jar
  exécutable, wrapper), le "Maven Getting Started Guide" (maven.apache.org) et la
  [documentation du plugin Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/using.html).

---

## 8. Quiz

**1. Pourquoi `spring-boot-starter-webmvc` n'a-t-il pas de `<version>` alors que `rest-assured` en a une ?**
<details><summary>Réponse</summary>
Le premier est géré par le <code>dependencyManagement</code> du parent <code>spring-boot-starter-parent</code>.
Le second n'y figure pas : sans version, Maven ne saurait pas laquelle prendre.
</details>

**2. Que se passe-t-il si tu passes le parent de 4.1.1 à une version supérieure ?**
<details><summary>Réponse</summary>
Toutes les dépendances gérées par le parent changent de version d'un coup, vers un nouvel ensemble testé. Les
versions surchargées dans <code>&lt;properties&gt;</code> et celles écrites en dur restent figées : à revoir
à chaque montée.
</details>

**3. Pourquoi le driver PostgreSQL est-il en scope `runtime` ?**
<details><summary>Réponse</summary>
Le code de l'application n'importe aucune classe du driver : il passe par JPA et JDBC. Le driver n'est nécessaire
qu'à l'exécution, quand la connexion est ouverte.
</details>

**4. `./mvnw package` lance-t-il les tests ? Pourquoi ?**
<details><summary>Réponse</summary>
Oui : les phases Maven s'exécutent dans l'ordre, et <code>test</code> précède <code>package</code>.
</details>

**5. Peut-on dépendre d'une bibliothèque en version `1.2.0-SNAPSHOT` ?**
<details><summary>Réponse</summary>
Techniquement oui, mais c'est à proscrire : une SNAPSHOT peut changer sans changer de numéro, le build n'est plus
reproductible. Seules des versions stables sont acceptables pour une dépendance.
</details>

**6. À quoi sert `mvnw` plutôt que `mvn` ?**
<details><summary>Réponse</summary>
Il utilise une version de Maven fixée par le projet, téléchargée automatiquement : même outil pour tout le monde,
sans installation préalable.
</details>
