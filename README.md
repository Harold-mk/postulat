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
| 4 | Note ATS déterministe | ✅ |
| 5 | Modèle de langage : adaptateur Anthropic, coût mesuré et journalisé, double de test | ✅ |
| 6 | Analyse d'offre, rapprochement avec le profil, note d'adéquation, arbitrage (UC-05, UC-06) | ✅ |
| 7–8 | Rendu sur une page, CV ciblé, lettre, évaluation, phrases versées au profil (UC-04), import d'un CV (UC-02) | à venir |
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
- **La note ATS**, qui simule un filtre de recrutement par mots-clés, sans modèle de langage. Elle
  est délibérément littérale, comme un vrai filtre : une exigence de l'offre compte si son libellé
  figure tel quel dans le texte extrait du document, casse et accents mis à part — « PowerBI » ne
  vaut pas « Power BI ». Une exigence obligatoire pèse deux fois une souhaitée ; la note dit aussi
  quelles obligatoires manquent, celles qu'un filtre strict tiendrait pour éliminatoires.
- **L'analyse d'offre.** Le texte d'une annonce, conservé tel quel, devient une fiche — poste,
  entreprise, contrat, missions, exigences obligatoires et souhaitées reprises des mots de l'offre.
  Puis chaque exigence est rapprochée du profil : couverte, avec les faits qui la démontrent, ou
  lacune, avec ce qui manque ; et une note d'adéquation justifiée précède toute génération. Le
  rapprochement est un jugement du modèle, mais il ne peut citer que des faits du profil : sinon il
  est refusé en entier. La décision reste au candidat — poursuivre, ou écarter avec un motif ; sans
  décision, l'offre reste un brouillon du tableau de bord. Modèle indisponible ? L'offre est
  conservée, analysable plus tard. Profil incomplet ? Elle est analysée, et le rapprochement attend.
- **Le modèle de langage est branché** derrière une seule interface,
  `Modele`, et son adaptateur vers l'API Anthropic. Le modèle répond dans une forme imposée (sorties
  structurées), la partie stable des demandes — consignes et profil — est mise en cache, et un refus des classifieurs de
  sûreté est rejoué côté serveur quand le modèle le permet. Chaque appel, réussi ou non, est
  journalisé dans la base avec ses jetons, son coût et de quoi le reconstituer ; le coût s'affiche
  par candidature et se cumule par mois. Deux gammes, comme le prévoit le cadrage : économique
  (`claude-haiku-5-5`) pour l'extraction, capable (`claude-opus-5-5`) pour la rédaction et le
  jugement. Les tests n'appellent jamais l'API : un faux serveur local et un faux modèle en tiennent
  lieu.

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
| `offre ajouter FICHIER` · `offre analyser N` | soumettre le texte d'une offre : analyse, rapprochement, note ; ou refaire l'analyse plus tard |
| `ajouter ENTREPRISE POSTE [--etape E] [--le DATE]` | une candidature déposée hors de l'outil, à l'étape atteinte et à sa date réelle |
| `avancer N ETAPE` · `ecarter N MOTIF` · `rouvrir N ETAPE` | changer d'étape ; ne pas postuler, en disant pourquoi ; sortir d'une étape terminale |
| `relancer N` | enregistrer une relance faite par vous ; postulat n'envoie rien |
| `planifier N JOUR --type rh\|technique\|final` | un entretien fixé, même à venir |
| `entretien N --type T [--compte-rendu TEXTE]` | un entretien passé |
| `retour-promis N JOUR` | une réponse promise pour une date |
| `interaction N courriel\|appel [--notes TEXTE]` · `contact N NOM` · `noter N TEXTE` | la mémoire des échanges |
| `sauvegarder` · `restaurer ARCHIVE` | archive datée ; retour à une archive après vérification |
| `profil initialiser` · `profil verifier` | écrire le gabarit commenté du profil ; dire ce qui y manque, à la ligne près |
| `modele essai [--gamme capable]` · `modele couts` | vérifier la clé d'API par un appel minuscule ; le cumul des coûts, mois par mois |

Toute saisie se date avec `--le` : `2026-10-01`, `01/10/2026`, `hier`. Sans `--le`, c'est
aujourd'hui. Les étapes s'écrivent avec ou sans accents : `offre reçue`, `offre_recue`.
`java -jar target/postulat.jar COMMANDE --aide` détaille chaque commande.

Une offre se donne par un fichier texte : copier l'annonce, la coller dans le Bloc-notes, enregistrer
(UTF-8 par défaut). `offre ajouter -` la lit sur l'entrée standard, mais Windows PowerShell 5.1 y perd
les accents sauf après `$OutputEncoding = [Text.Encoding]::UTF8` : le fichier est plus sûr.

Les délais de relance, l'objectif hebdomadaire et le modèle de chaque gamme se règlent dans un
fichier `postulat.properties` placé dans le répertoire des données. Seuls les modèles dont le tarif
est connu sont acceptés, pour que leur coût reste mesurable :

```properties
relance.delai.jours = 10
relance.nombre = 2
objectif.hebdomadaire = 30
modele.economique = claude-haiku-5-5
modele.capable = claude-opus-5-5
```

### Clé d'API

Le modèle de langage demande une clé de la Console Anthropic, approvisionnée en crédit :
l'abonnement Claude ne donne pas accès à l'API. La clé se place dans la variable d'environnement
`ANTHROPIC_API_KEY`, jamais dans un fichier du projet — sous Windows, *Paramètres › Système ›
Informations système › Paramètres système avancés › Variables d'environnement*, puis un nouveau
terminal. `postulat modele essai` vérifie la connexion pour une fraction de centime. Les crochets
git refusent de toute façon un commit qui contiendrait une clé.

## Architecture

Volontairement simple : cinq paquets rangés par sujet.

```
postulat/
  suivi/        pipeline, journal, échéances, tableau de bord      Java pur
  production/   profil, offre, rapprochement, traçabilité, ATS     Java pur
  stockage/     SQLite en JDBC nu, migrations, sauvegardes,        sqlite-jdbc
                lecture du profil YAML                             snakeyaml-engine
  ia/           interface Modele, client Anthropic, journal        anthropic-java
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
