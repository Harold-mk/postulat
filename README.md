# postulat

Conduire une campagne de candidatures de stage : produire vite des dossiers dont chaque phrase est
vraie et démontrable, puis suivre chaque candidature jusqu'à son issue.

> **postulat** — *postuler*, et une proposition tenue pour vraie. C'est la garantie centrale du
> projet : chaque phrase d'un CV produit cite le fait du profil dont elle dérive, ou est déclarée
> comme écrite par l'auteur. Le modèle de langage n'invente jamais.

## État

| Itération | Contenu | État |
|---|---|---|
| 1 | Domaine du suivi : étapes, transitions, journal d'événements, relances | ✅ |
| 2 | Stockage SQLite, ligne de commande, tableau de bord, sauvegarde (UC-10 à UC-18) | ✅ |
| 3 | Profil, gabarit, validation, vérification de traçabilité (UC-01, UC-03) | ✅ |
| 4 | Note ATS | à venir |
| 5–8 | Modèle de langage, import d'un CV existant (UC-02), CV ciblé, lettre, évaluation | à venir |
| 9 | Interface de bureau | à venir |

## Ce qui fonctionne aujourd'hui

Le suivi de campagne est utilisable au quotidien, en ligne de commande, sans aucun modèle de
langage.

- **Un pipeline de 11 étapes** (`repérée` → `à_préparer` → `prête` → `envoyée` → `entretien` →
  `offre_reçue` → `acceptée`, plus cinq issues terminales). Les transitions autorisées sont
  déclarées dans une table ; une transition non déclarée est refusée avec la liste des possibles.
- **Un journal d'événements immuable.** Dans le domaine, l'étape n'est jamais stockée : elle est
  recalculée en rejouant le journal. La base en garde une copie pour ses requêtes, écrite dans la
  même transaction que l'événement et vérifiée à chaque restauration. Des triggers SQL refusent
  toute modification ou suppression d'un événement.
- **Les échéances**, recalculées à la demande, jamais stockées :
  - une relance due 10 jours après l'envoi, une seconde 10 jours après la première ; après deux
    relances sans réponse, l'abandon est *proposé*, jamais appliqué ;
  - la veille d'un entretien planifié, le préparer ; le jour même, remercier ;
  - le jour d'un retour promis par l'entreprise, faire le point s'il n'est rien arrivé depuis.
- **Une liste de travail triée par urgence** : retard décroissant, puis étape la plus avancée, puis
  ancienneté.
- **Un tableau de bord** : répartition par étape, envois par semaine face à l'objectif de 30,
  candidatures en attente, actions du jour, échéances des sept prochains jours, brouillons restés à
  `repérée`. Une semaine incomplète est signalée comme telle, jamais affichée comme un retard.
- **Contacts, échanges et notes** rattachés à chaque candidature.
- **Les doublons sont détectés** : même entreprise et même poste, sans tenir compte de la casse ni
  des accents.
- **Sauvegarde et restauration.** Une archive est complète ou n'existe pas. Une restauration vérifie
  tout avant d'écrire quoi que ce soit, puis sauvegarde l'état qu'elle remplace.

La production de documents a ses fondations, sans modèle de langage pour l'instant :

- **Le profil**, seule source de faits, s'écrit en YAML à partir d'un gabarit commenté. Quatre
  sortes de faits — expériences, réalisations, formations, compétences — chacun avec un identifiant
  unique, qu'un dossier cite. La vérification liste d'un coup tout ce qui manque, à la ligne et à la
  colonne près : champ obligatoire vide, « à compléter » oublié, identifiant en double, champ mal
  orthographié, erreur de syntaxe.
- **La vérification de traçabilité**, qui décidera si un document produit est recevable. Toute
  phrase d'origine « faits » doit citer des faits qui existent (RG-01), ne contenir que des nombres
  présents dans ces faits (RG-03) — « 15 commerciaux » est refusé si le fait dit 12, « une
  vingtaine » aussi — et ne jamais mentionner une exigence de l'offre que le profil ne couvre pas
  (RG-04). Une phrase écrite par l'auteur n'est jamais rejetée. Des tests adversariaux reproduisent
  les inventions typiques d'un modèle.

## Utilisation

Construire une fois le jar autonome, puis l'utiliser :

```bash
./mvnw package -DskipTests                 # produit target/postulat.jar
java -jar target/postulat.jar actions
```

| Commande | Effet |
|---|---|
| `tableau` | l'état de la campagne d'un coup d'œil |
| `actions` | ce qu'il y a à faire aujourd'hui, le plus urgent d'abord |
| `liste [--toutes] [--etape E] [--entreprise T]` | les candidatures, regroupées par entreprise |
| `voir N` | tout sur la candidature n° N : contacts, historique, échéances |
| `ajouter ENTREPRISE POSTE [--etape E] [--le DATE]` | une candidature, à l'étape atteinte et à sa date réelle |
| `avancer N ETAPE` · `rouvrir N ETAPE` | changer d'étape, ou sortir d'une étape terminale |
| `relancer N` | enregistrer une relance faite par vous ; postulat n'envoie rien |
| `planifier N JOUR --type rh\|technique\|final` | un entretien fixé, même à venir |
| `entretien N --type T [--compte-rendu TEXTE]` | un entretien passé |
| `retour-promis N JOUR` | une réponse promise pour une date |
| `interaction N courriel\|appel [--notes TEXTE]` · `contact N NOM` · `noter N TEXTE` | la mémoire des échanges |
| `sauvegarder` · `restaurer ARCHIVE` | archive datée ; retour à une archive après vérification |
| `profil initialiser` · `profil verifier` | écrire le gabarit commenté du profil ; dire ce qui y manque, à la ligne près |

Toute saisie se date avec `--le` : `2026-10-01`, `01/10/2026`, `hier`. Sans `--le`, c'est
aujourd'hui. Les étapes s'écrivent avec ou sans accents : `offre reçue`, `offre_recue`.
`java -jar target/postulat.jar COMMANDE --aide` détaille chaque commande.

Les délais de relance et l'objectif hebdomadaire se règlent dans un fichier `postulat.properties`
placé dans le répertoire des données :

```properties
relance.delai.jours = 10
relance.nombre = 2
objectif.hebdomadaire = 30
```

## Architecture

Volontairement simple : cinq paquets rangés par sujet.

```
postulat/
  suivi/        pipeline, journal, échéances, tableau de bord      Java pur
  production/   profil, faits, traçabilité                         Java pur
  stockage/     SQLite en JDBC nu, migrations, sauvegardes,        sqlite-jdbc
                lecture du profil YAML                             snakeyaml-engine
  ia/           interface Modele + client Anthropic                              (itération 5)
  cli/          ligne de commande                                  picocli
```

Trois règles :

1. `suivi` et `production` n'importent que `java.*`. Ils se testent sans base, sans réseau, sans clé.
2. Le domaine ne lit jamais l'horloge : le jour courant lui est passé en paramètre.
3. Une seule interface d'abstraction, `Modele`, parce que c'est la seule dépendance qu'il faut
   remplacer par un faux dans les tests.

Le système **n'envoie jamais rien** : il prépare, mesure, rappelle et archive. L'envoi reste au
candidat.

### Migrations de schéma

Les migrations sont des fichiers SQL numérotés dans `src/main/resources/db/migration`, déclarés
dans l'ordre dans `Migrations.FICHIERS`. Elles s'appliquent au démarrage. Elles sont écrites à la
main plutôt qu'avec Flyway : une centaine de lignes, contre une dépendance qui en tire d'autres.
Les principes d'ADR-0004 sont tenus :

- une migration appliquée ne se modifie jamais. Son empreinte est enregistrée, et le programme
  refuse de démarrer si le fichier a changé ;
- avant toute migration d'une base qui contient des données, une sauvegarde est prise
  automatiquement. Si elle échoue, la migration n'est pas appliquée ;
- le retour arrière est la restauration de cette sauvegarde. Il rend le schéma *et* les données,
  et il est testé.

## Construire et tester

Prérequis : un JDK 21 ou plus récent (Eclipse Temurin recommandé). Maven n'a pas besoin d'être
installé : le wrapper fourni télécharge la version fixée par le projet au premier lancement.

```bash
./mvnw verify          # Linux, macOS, Git Bash
.\mvnw.cmd verify      # Windows (PowerShell, cmd)
```

`verify` compile avec tous les avertissements traités comme des erreurs, exécute les tests et
échoue si la couverture du domaine descend sous 90 %. Le rapport de couverture est dans
`target/site/jacoco/index.html`. Les tests du stockage et de la ligne de commande tournent sur une
vraie base SQLite, dans un répertoire temporaire.

## Données personnelles

Le profil, la base de suivi et les dossiers produits contiennent des données personnelles. Ils ne
vivent **jamais** dans ce dépôt, mais dans le répertoire utilisateur du système :
`%LOCALAPPDATA%\postulat` sous Windows, `$XDG_DATA_HOME/postulat` sous Linux. On y trouve
`profil.yaml`, `postulat.db`, le dossier `sauvegardes/` et, s'il existe, `postulat.properties`. L'option
`--donnees REPERTOIRE` en désigne un autre.

## Documentation

La démarche suivie — cadrage des besoins, cahier d'analyse, choix techniques — est décrite dans les
documents de conception du projet.
