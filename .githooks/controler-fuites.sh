#!/usr/bin/env bash
#
# postulat — contrôle anti-fuite : rien de ce qui est publié ne doit exposer une donnée
# personnelle, un secret ou la machine de l'auteur (EN-2, R4).
#
# Ce script est l'unique endroit où vivent les motifs. Trois appelants :
#
#   .githooks/pre-commit        index   avant chaque commit
#   .githooks/pre-merge-commit  index   avant une fusion sans conflit
#   .githooks/pre-push          arbre   avant chaque envoi : le dernier filet
#
# Pourquoi deux modes. Le mode « index » ne voit que ce qu'on s'apprête à commiter :
# rapide, mais aveugle à ce qui entre par un chemin qui ne passe pas par pre-commit —
# git rebase, cherry-pick, revert, stash, et un commit modifié par --amend. Le mode
# « arbre » relit TOUT ce qui est suivi, donc il voit ce que les autres ont laissé passer.
#
# Limites assumées. Ces crochets se contournent avec --no-verify : c'est un garde-fou
# contre l'erreur, pas contre la volonté. Et le mode « arbre » lit l'arbre de travail,
# non l'historique : un secret commité puis supprimé reste dans l'historique. Un secret
# publié se révoque, il ne se réécrit pas.
#
# Faux positif : ajouter « postulat:faux-positif » en commentaire sur la ligne
# concernée. La ligne est alors ignorée du contrôle de contenu, et le marqueur reste
# visible en revue — contrairement à --no-verify, qui n'en laisse aucune trace.

set -uo pipefail

MODE="${1:-}"
case "$MODE" in
    index | arbre) ;;
    *)
        printf 'usage : %s index|arbre\n' "$0" >&2
        exit 2
        ;;
esac

# Les couleurs seulement si la sortie est un terminal : un client graphique
# afficherait les séquences ANSI telles quelles.
if [ -t 2 ]; then
    ROUGE=$(printf '\033[31m')
    NEUTRE=$(printf '\033[0m')
else
    ROUGE=''
    NEUTRE=''
fi

refus=0
signaler() {
    printf '%s[refusé]%s %s\n' "$ROUGE" "$NEUTRE" "$1" >&2
    shift
    for ligne in "$@"; do
        printf '    %s\n' "$ligne" >&2
    done
    refus=1
}

# ===========================================================================
# Motifs de CHEMIN — ce qui ne doit jamais être versionné, quel qu'en soit le
# contenu. Comparaison insensible à la casse : Windows ne distingue pas
# « Profil.YAML » de « profil.yaml », le contrôle non plus.
# ===========================================================================
CHEMINS_INTERDITS='\.(db|db3|s3db|sqlite|sqlite3|sqlitedb|db-journal|db-shm|db-wal)$'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|\.(pem|key|p12|pfx|jks|keystore|crt|cer|csr|ppk|p8|asc|gpg|kdbx)$'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)(id_rsa|id_dsa|id_ecdsa|id_ed25519|\.netrc|_netrc|credentials|\.git-credentials)$'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)\.env($|\.)'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)(cle|clef|api)[-_]?(api|key)'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)[^/]*profil[^/]*\.(ya?ml|json|toml|csv|txt|md|bak)$'
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)(donn[eé]es?|data|sauvegardes?|candidatures?|dossiers?|profils?)/'
# Sauvegardes de postulat et réglages locaux du répertoire des données.
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)postulat-[0-9-]+\.zip$|(^|/)postulat\.properties$'
# Réglages locaux d'outils, qui portent des chemins et des permissions de la machine.
CHEMINS_INTERDITS="$CHEMINS_INTERDITS"'|(^|/)\.idea/|(^|/)\.claude/settings\.local\.json$|(^|/)CLAUDE\.local\.md$'

# Les ressources de test échappent aux motifs de RÉPERTOIRE : un jeu d'essai nommé
# src/test/resources/donnees/offre.json est légitime. Son CONTENU reste contrôlé —
# c'est le bon partage : la forme d'un profil est publiable, les faits ne le sont pas.
CHEMINS_EXEMPTES='^src/(main|test)/resources/'

# Documents produits ou importés. Tolérés dans docs/ — sauf ceux dont le nom dit
# qu'il s'agit d'une pièce de candidature. « docs, c'est pour les documents » est le
# raisonnement par lequel un vrai CV se retrouve publié.
# Le motif personnel ne vise que les formats de document : Candidature.java est une
# classe du domaine, candidature.md ou CV_2026.pdf sont des pièces.
CHEMINS_DOCUMENTS='\.(pdf|docx|doc|odt|rtf|xlsx|xls|pptx)$'
DOCUMENTS_PERSONNELS='(^|/)(cv|curriculum|lettre|motivation|candidature|lm)[^/]*\.(pdf|docx?|odt|rtf|txt|md|html?|tex)$'

# ===========================================================================
# Motifs de CONTENU, en quatre familles.
#
# Les littéraux sont des empreintes de secrets : aucune raison d'apparaître, même en
# exemple. Les génériques sont des formes probables, donc sujettes au faux positif :
# ils ne s'appliquent pas aux documents, qui décriront la configuration du modèle de
# langage à l'itération 5. Les personnels et ceux de la machine sont la raison d'être
# de ce contrôle.
# ===========================================================================
MOTIFS_LITTERAUX='sk-ant-[A-Za-z0-9_-]{12,}'
MOTIFS_LITTERAUX="$MOTIFS_LITTERAUX"'|-----BEGIN [A-Z ]*PRIVATE KEY-----'
MOTIFS_LITTERAUX="$MOTIFS_LITTERAUX"'|AKIA[0-9A-Z]{16}'
MOTIFS_LITTERAUX="$MOTIFS_LITTERAUX"'|gh[pousr]_[A-Za-z0-9]{24,}|github_pat_[A-Za-z0-9_]{20,}'
MOTIFS_LITTERAUX="$MOTIFS_LITTERAUX"'|[Bb]earer[[:space:]]+[A-Za-z0-9._~+/=-]{20,}'

MOTIFS_GENERIQUES='(api[_-]?key|apikey|secret|token|password|passwd|mot[_ -]?de[_ -]?passe|motdepasse)'
MOTIFS_GENERIQUES="$MOTIFS_GENERIQUES"'["'"'"']?[[:space:]]*[:=,][[:space:]]*["'"'"']?[A-Za-z0-9/+=_.-]{16,}'

# Courriel, téléphone français, numéro de sécurité sociale. Le motif de NIR impose un
# sexe (1 ou 2) et un mois valide, au lieu de treize chiffres quelconques : un
# identifiant ou un horodatage ne le déclenche pas.
MOTIFS_PERSONNELS='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
MOTIFS_PERSONNELS="$MOTIFS_PERSONNELS"'|(\+33|0033)[[:space:].-]?[1-9]([[:space:].-]?[0-9]{2}){4}'
MOTIFS_PERSONNELS="$MOTIFS_PERSONNELS"'|(^|[^0-9+])0[1-9]([[:space:].-]?[0-9]{2}){4}([^0-9]|$)'
MOTIFS_PERSONNELS="$MOTIFS_PERSONNELS"'|[12][[:space:]]?[0-9]{2}[[:space:]]?(0[1-9]|1[0-2])[[:space:]]?[0-9]{2}[[:space:]]?[0-9]{3}[[:space:]]?[0-9]{3}([[:space:]]?[0-9]{2})?'

# La machine : un chemin de profil utilisateur trahit le nom du compte, une adresse
# de réseau privé trahit le réseau local. Un nom générique entre chevrons ou en
# variable (C:\Users\<nom>, %USERPROFILE%, $HOME) n'est pas visé.
MOTIFS_MACHINE='[A-Za-z]:[\\/]+(Users|Utilisateurs|Documents and Settings)[\\/]+[A-Za-z0-9._-]+'
MOTIFS_MACHINE="$MOTIFS_MACHINE"'|(^|[^A-Za-z0-9.])/([a-z]/)?(Users|home)/[A-Za-z0-9._-]+'
MOTIFS_MACHINE="$MOTIFS_MACHINE"'|(^|[^0-9.])(10(\.[0-9]{1,3}){3}|192\.168(\.[0-9]{1,3}){2}|172\.(1[6-9]|2[0-9]|3[01])(\.[0-9]{1,3}){2})([^0-9.]|$)'

# Liste personnelle, HORS du dépôt : nom d'utilisateur, nom de la machine, nom, ville,
# ancien employeur, ce qu'on veut. Une expression par ligne, insensible à la casse.
# La versionner serait évidemment auto-contradictoire.
LISTE_PERSONNELLE="${XDG_CONFIG_HOME:-$HOME/.config}/postulat/motifs-interdits"
if [ -r "$LISTE_PERSONNELLE" ]; then
    supplement=$(grep -v '^[[:space:]]*\(#.*\)\?$' "$LISTE_PERSONNELLE" | tr -d '\r' | tr '\n' '|' | sed 's/|$//')
    [ -n "$supplement" ] && MOTIFS_PERSONNELS="$MOTIFS_PERSONNELS|$supplement"
fi

# Adresses de domaines réservés (RFC 2606, RFC 6761) : elles ne peuvent appartenir à
# personne, ce sont donc celles des jeux d'essai. Elles sont retirées AVANT la
# recherche, et non par ligne après coup : une vraie adresse placée sur la même ligne
# qu'une adresse d'essai reste détectée.
DOMAINES_RESERVES='[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+\.)*(example\.(com|net|org)|[A-Za-z0-9-]+\.(test|example|invalid|localhost))([^A-Za-z0-9.-]|$)'

# Lignes écartées après correspondance : marqueur explicite, caractères de
# remplacement, variables d'environnement, et constantes en MAJUSCULES_SOULIGNÉES —
# « TOKEN = "CANDIDATURE_SOUMISE" » est une étape du domaine, pas un secret.
#
# Cette liste est volontairement courte. Un mot qu'on écrit sans y penser, comme
# « exemple », ne peut pas servir d'échappatoire ; seul un marqueur qu'on pose exprès
# le peut.
ECHAPPATOIRES='postulat:faux-positif|[Xx]{8,}|PLACEHOLDER|\$\{|\{\{'
CONSTANTES='[:=,][[:space:]]*["'"'"']?[A-Z0-9_]{16,}["'"'"']?[[:space:]]*[;,)]*[[:space:]]*$'

# Binaires admis sans contrôle de contenu. Tout autre fichier illisible en texte est
# REFUSÉ : PowerShell 5 écrit en UTF-16 par défaut, et un fichier UTF-16 traverse
# silencieusement un contrôle fondé sur grep.
BINAIRES_ADMIS='\.(png|jpe?g|gif|svgz|ico|jar|woff2?|ttf)$|^docs/.*\.pdf$'

# ===========================================================================
# Liste des fichiers à contrôler. En cas d'échec de git, on REFUSE : pour une
# mesure de sécurité, échouer fermé est la seule règle défendable.
# ===========================================================================
#
# La liste passe par un fichier temporaire, et non par « $(…) » : une substitution de
# commande supprime les octets nuls, donc détruirait les séparateurs de « -z ». Le
# détour permet aussi de TESTER le code de retour de git : sans ce test, un git en
# échec rend une liste vide, donc un contrôle qui accepte tout.
liste=$(mktemp) || exit 1
trap 'rm -f "$liste"' EXIT

if [ "$MODE" = index ]; then
    # --diff-filter=d exclut les suppressions et retient tout le reste, y compris les
    # renommages. --no-renames force git à présenter un renommage comme un ajout.
    if ! git diff --cached --name-only --diff-filter=d --no-renames -z >"$liste"; then
        printf '%sLe contrôle n'"'"'a pas pu lire l'"'"'index.%s Commit refusé.\n' "$ROUGE" "$NEUTRE" >&2
        exit 1
    fi
else
    if ! git ls-files -z >"$liste"; then
        printf '%sLe contrôle n'"'"'a pas pu lister les fichiers suivis.%s Refusé.\n' "$ROUGE" "$NEUTRE" >&2
        exit 1
    fi
fi

fichiers=()
while IFS= read -r -d '' fichier; do
    fichiers+=("$fichier")
done <"$liste"

if [ ${#fichiers[@]} -eq 0 ]; then
    exit 0
fi

# Le contenu est écrit une fois dans un fichier temporaire, et tous les contrôles
# portent ensuite sur ce fichier : brancher « git show » dans un « grep -q » le ferait
# tuer par SIGPIPE, ce que « pipefail » rapporterait comme un échec.
contenu=$(mktemp) || exit 1
trap 'rm -f "$liste" "$contenu"' EXIT

lire_contenu() {
    if [ "$MODE" = index ]; then
        git show ":$1" >"$contenu" 2>/dev/null
    else
        cat -- "$1" >"$contenu" 2>/dev/null
    fi
}

for fichier in "${fichiers[@]}"; do

    # --- Chemins ---------------------------------------------------------
    if ! printf '%s' "$fichier" | grep -qE "$CHEMINS_EXEMPTES"; then
        if printf '%s' "$fichier" | grep -qEi "$CHEMINS_INTERDITS"; then
            signaler "$fichier" \
                "base, profil, sauvegarde, secret, réglage local ou répertoire de données : jamais dans le dépôt."
            continue
        fi
    fi

    if printf '%s' "$fichier" | grep -qEi "$DOCUMENTS_PERSONNELS"; then
        signaler "$fichier" \
            "le nom désigne une pièce de candidature. Aucune n'est versionnée, pas même dans docs/."
        continue
    fi

    if printf '%s' "$fichier" | grep -qEi "$CHEMINS_DOCUMENTS" \
        && ! printf '%s' "$fichier" | grep -qE '^docs/'; then
        signaler "$fichier" \
            "document produit ou importé. Seul docs/ accueille des documents versionnés."
        continue
    fi

    # --- Le script des motifs ne s'analyse pas lui-même ------------------
    case "$fichier" in
        .githooks/controler-fuites.sh) continue ;;
    esac

    # --- Lisibilité : échouer fermé sur ce qu'on ne sait pas lire --------
    lire_contenu "$fichier"

    # Un fichier vide n'a rien à cacher.
    [ -s "$contenu" ] || continue

    if ! grep -qI . "$contenu" 2>/dev/null; then
        if ! printf '%s' "$fichier" | grep -qEi "$BINAIRES_ADMIS"; then
            signaler "$fichier" \
                "contenu binaire ou non-UTF-8 : illisible par le contrôle, donc refusé." \
                "Si c'est du texte, le réenregistrer en UTF-8 — PowerShell 5 écrit en UTF-16."
        fi
        continue
    fi

    # --- Contenu ---------------------------------------------------------
    motifs="$MOTIFS_LITTERAUX|$MOTIFS_PERSONNELS|$MOTIFS_MACHINE"
    case "$fichier" in
        *.md | docs/*) ;; # les formes génériques y produiraient trop de faux positifs
        *) motifs="$motifs|$MOTIFS_GENERIQUES" ;;
    esac

    # \5 : le caractère qui suivait l'adresse retirée, rendu tel quel.
    trouve=$(sed -E "s/$DOMAINES_RESERVES/\\5/g" "$contenu" \
        | grep -nEi "$motifs" \
        | grep -vEi "$ECHAPPATOIRES" \
        | grep -vE "$CONSTANTES" \
        | head -3) || true

    if [ -n "$trouve" ]; then
        messages=()
        while IFS= read -r l; do messages+=("ligne $l"); done <<<"$trouve"
        messages+=("ressemble à une clé, une donnée personnelle ou une trace de la machine.")
        messages+=("Faux positif ? ajouter « postulat:faux-positif » en commentaire sur la ligne.")
        signaler "$fichier" "${messages[@]}"
    fi
done

if [ "$refus" -ne 0 ]; then
    if [ "$MODE" = index ]; then
        printf '\n%sCommit refusé.%s  Retirer de l'"'"'index :  git restore --staged <fichier>\n' \
            "$ROUGE" "$NEUTRE" >&2
        if [ -f "$(git rev-parse --git-dir)/MERGE_HEAD" ]; then
            printf 'Fusion en cours : ne pas désindexer une résolution de conflit. Corriger le fichier.\n' >&2
        fi
    else
        printf '\n%sEnvoi refusé.%s  Ces fichiers sont suivis par git et ne doivent pas l'"'"'être.\n' \
            "$ROUGE" "$NEUTRE" >&2
    fi
    exit 1
fi

exit 0
