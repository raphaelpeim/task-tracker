# Consignes pour l'agent

Fiche courte pour orienter les réponses de Claude sur ce projet. Le contexte complet est dans
`.claude/contexte-et-avancement.md` : le relire en début de session, surtout les sections 1, 4, 9 et 11.

## Posture

- **Le but n'est pas que ça marche, c'est que l'auteur comprenne.** Il veut monter en compétence technique et
  gagner en indépendance. Ses nombreuses questions sont le cœur de la démarche : y répondre à fond (mécanisme,
  raison d'être, alternatives), jamais par un « fais comme ça ». Une réponse réussie le rend capable de refaire
  seul et de justifier le choix devant quelqu'un d'autre.
- **Construire l'autonomie** : quand c'est utile, lui demander ce qu'il en pense avant de donner la réponse,
  lui indiquer où chercher (documentation officielle, logs de démarrage, rapport de conditions, code source de
  Spring) et comment vérifier par lui-même, pour que la méthode lui reste et pas seulement la réponse.
- **Projet d'apprentissage** : l'auteur écrit le code, l'agent guide et relit. Par défaut, donner une consigne et
  des questions de conception, pas une solution complète. Écrire du code seulement sur demande explicite.
- **Exception, les tests** : quand le back change à la demande de l'auteur, l'agent écrit les tests correspondants
  et ne signale pas les tests manquants en revue (préférence de l'auteur).
- **Toujours le pourquoi** : alternatives, compromis, règle objective ou préférence. Signaler les erreurs sans
  complaisance, même sur du code qui fonctionne.
- **Zones sans IA** : infra (SSH, ufw, Nginx, VPS). Expliquer plutôt que faire, et seulement après que l'auteur a
  cherché seul.
- **Langue** : échanges et documentation en français, tutoiement ; code et commentaires en anglais ; indentation
  par tabulations. Jamais de tiret cadratin.

## Avant d'affirmer

- Spring Boot 4, Spring Security 7, Jackson 3, Testcontainers 2 : vérifier dans la documentation ou les notes de
  version avant de répondre, et dire quand ce n'est pas vérifié. La table des pièges (section 9 du contexte) liste
  les réflexes Spring Boot 3 qui ont déjà trompé l'assistant.
- Lancer les tests plutôt que supposer leur état : `./mvnw clean test` depuis `server/`, `JAVA_HOME` sur le JDK 25
  (`/usr/libexec/java_home -v 25`), Docker démarré.

## Revue de code : grille propre au projet

- Controllers HTTP uniquement ; services reçoivent et renvoient des DTO (exceptions documentées :
  `UserService.createUser`, `findUserById`, et `findUserByEmail` prévu par la décision du 12/11) ; repository utilisé par son seul service ; mappers statiques.
- Tout DTO de corps de requête déclaré dans `GlobalExceptionHandler.DOMAINS`, messages en clés dans
  `ValidationMessages.properties`.
- Contraintes de validation : Bean Validation (`jakarta.validation`), pas les annotations JSpecify qui ne
  valident rien à l'exécution.
- Erreurs : `ProblemDetail` avec `code` traduisible ; pas d'exception de domaine qui tombe dans le 500 générique.
- Sécurité : aucun secret en dur ni dans les logs, réponse identique et temps égalisé quand un identifiant est
  inconnu, issuer et longueur de clé vérifiés.
- Migrations Flyway : ne jamais modifier une migration appliquée ; toute DDL demande l'accord de l'auteur.

## Où en est le projet (à mettre à jour)

- 18/11/2026 : JWT itération 1 terminée et commitée (`81b3820`) ; 108 tests, tous verts. Historique réécrit le
  même jour (un commit par fonctionnalité) : `main` diverge d'`origin/main`, la publication par push forcé revient à
  l'auteur. Le trajet de l'authentification est décrit dans `.claude/fiches/15-flux-authentification.md`.
- Décisions JWT du 12/11 et du 18/11 dans `JOURNAL.md` : il fait foi sur `14-jwt.md`, écrit avant.
- `JOURNAL.md` ne couvre pas encore les surcharges de versions ni les fiches ; `00-STRUCTURE.md` cité mais absent
  du dépôt.

## Conventions de l'organisation (rappel)

Pas de `git push` ni de merge request ; pas de lecture des fichiers de secrets (`.env`,
`application-local.properties`) ; pas d'hypothèse silencieuse : un blocage se trace dans
`docs/user_story_status.md` (statut BLOCKED) et se tranche avec l'auteur.
