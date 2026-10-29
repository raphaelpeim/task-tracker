# Fiches Spring Boot du task-tracker

Quatorze fiches pour comprendre un projet Spring Boot à partir du code du task-tracker. Elles s'adressent à quelqu'un
qui sait programmer mais découvre Spring : chaque notion part du problème qu'elle résout, puis montre le vrai code
du projet.

L'inventaire complet des notions est dans [`notions-spring-boot.md`](notions-spring-boot.md), le guide de mise
en place de l'authentification dans [`14-jwt.md`](14-jwt.md).

## Structure d'une fiche

Chaque fiche suit le même plan :

1. **Le problème que ça résout** : l'intuition, ce qu'on ferait sans
2. **Les concepts** : l'explication, progressive
3. **Dans le projet** : le code réel du task-tracker, fichier et ligne
4. **Sous le capot** : les mécanismes réels derrière la "magie", pourquoi ils sont nécessaires, comment les
   observer dans le projet (logs, classes générées, point d'arrêt) et les liens vers la documentation officielle
5. **À toi de jouer** : des exercices à faire sur le projet
6. **Pièges fréquents** : symptôme, cause, solution
7. **Pour aller plus loin** : notions liées et documentation
8. **Quiz** : pour vérifier ce que tu as retenu (réponses repliées)

Exception : le guide [`14-jwt.md`](14-jwt.md) suit l'ordre des étapes de mise en place. Sa partie "Sous le capot"
est la section 11, en fin de guide.

## Les fiches

| # | Fiche | En une phrase |
|---|---|---|
| 01 | [Maven et le pom.xml](01-maven-pom.md) | Comment le projet est construit et d'où viennent ses bibliothèques |
| 02 | [Beans et injection](02-beans-injection.md) | Comment Spring crée et relie les objets de l'application |
| 03 | [Configuration](03-configuration.md) | Comment l'application change de comportement sans recompiler |
| 04 | [Architecture en couches](04-architecture-couches.md) | Qui fait quoi : controller, service, repository, DTO, mapper |
| 05 | [Web et REST](05-web-rest.md) | Comment une requête HTTP devient un appel de méthode Java |
| 06 | [Validation](06-validation.md) | Comment refuser une donnée invalide avant qu'elle n'entre |
| 07 | [Exceptions](07-exceptions.md) | Comment une erreur devient une réponse HTTP propre |
| 08 | [Persistance](08-persistance.md) | Comment les objets Java sont stockés en base et le schéma évolue |
| 09 | [Transactions](09-transactions.md) | Comment garantir que tout réussit ou rien |
| 10 | [Sécurité](10-securite.md) | Qui peut appeler l'API, et comment protéger les mots de passe |
| 11 | [Tests](11-tests.md) | Comment prouver que le code marche, contre une vraie base |
| 12 | [Java moderne](12-java-moderne.md) | Les tournures de Java récentes utilisées dans le projet |
| 13 | [Outillage](13-outillage.md) | Docker, git et ce qui entoure le code |
| 14 | [JWT](14-jwt.md) | Guide pas à pas pour ajouter l'authentification par token (format différent des autres fiches) |

## Ordre de lecture conseillé

```
01 Maven ──► 02 Beans ──► 03 Configuration
                │
                ▼
         04 Architecture ──► 05 REST ──► 06 Validation ──► 07 Exceptions
                │
                ▼
         08 Persistance ──► 09 Transactions
                │
                ▼
         10 Sécurité ──► 14 JWT
                │
                ▼
             11 Tests

12 Java moderne et 13 Outillage : à lire quand tu veux, en parallèle.
```

Si tu n'as qu'une heure : **02**, **04** puis **08**. Ce sont les trois fiches qui expliquent le plus de code.

## Comment s'en servir

- Lis une fiche avec le projet ouvert à côté : les références `fichier:ligne` sont là pour ça.
- Fais les exercices de la partie 4 : c'est en cassant quelque chose qu'on comprend pourquoi c'est construit ainsi.
  Fais-les de préférence sur une branche dédiée (`git switch -c exercices`) ou après avoir commité ton travail en
  cours : `git restore <fichier>` annule **toutes** les modifications non commitées du fichier, pas seulement
  celles de l'exercice.
- Reviens à la grille d'auto-évaluation de l'inventaire (section 17) pour mesurer ta progression.
