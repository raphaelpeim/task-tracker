# 01. Maven et le pom.xml

**Ce que tu vas comprendre**
- Ce que fait Maven et pourquoi on ne télécharge jamais un `.jar` à la main
- Comment se lit un `pom.xml`, bloc par bloc
- Pourquoi la plupart des dépendances du projet n'ont pas de `<version>`
- Ce qu'est un starter Spring Boot et ce que changent les scopes (`test`, `runtime`, `optional`)
- Les commandes Maven à connaître au quotidien

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

## 4. À toi de jouer

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

## 5. Pièges fréquents

| Symptôme | Cause | Solution |
|---|---|---|
| `Could not find artifact ...` | Faute de frappe dans un GAV, ou version inexistante | Vérifier les coordonnées sur Maven Central |
| `ClassNotFoundException` à l'exécution alors que ça compile | Dépendance manquante à l'exécution, ou scope mal choisi | Vérifier le scope ; `dependency:tree` |
| `NoSuchMethodError` / comportement étrange après un ajout | Deux versions incompatibles d'une même lib (conflit de transitives) | `dependency:tree -Dverbose`, retirer la `<version>` forcée, laisser le parent gérer |
| L'IDE ne voit pas une dépendance ajoutée | Projet Maven non rechargé | "Reload Maven project" dans l'IDE |
| Le build marche chez toi, pas sur la CI | Version de Maven ou de JDK différente | Toujours passer par `./mvnw`, vérifier `java -version` |
| Tests qui échouent à cause de vieux fichiers | Résidus dans `target/` (migration renommée par exemple) | `./mvnw clean ...` |

---

## 6. Pour aller plus loin

- **Gradle** : l'alternative à Maven, scripts en Kotlin ou Groovy, plus flexible et souvent plus rapide.
- **Multi-module** : un pom parent qui agrège plusieurs modules (`api`, `domain`, `infra`).
- **Dépendances vulnérables** : plugin OWASP Dependency-Check, Dependabot, Renovate.
- **Image Docker** ⏳ : `./mvnw spring-boot:build-image` construit une image sans Dockerfile.
- **GraalVM native image** ⏳ : compilation en exécutable natif via le plugin Spring Boot.
- **Docs officielles** : "Maven Getting Started Guide", "Introduction to the Dependency Mechanism" (maven.apache.org) ;
  "Spring Boot Maven Plugin Documentation" et la section "Build Systems" de la documentation de référence Spring Boot.

---

## 7. Quiz

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
