#!/usr/bin/env bash
#
# postulat — l'identité inscrite dans les commits. Git écrit l'adresse de l'auteur dans
# CHAQUE commit, et un dépôt public la publie : aucun contrôle de contenu ne la voit.
#
# Seule une adresse « noreply » de GitHub est admise :
#     <id>+<pseudo>@users.noreply.github.com
# Elle se trouve dans GitHub > Settings > Emails, case « Keep my email address private ».
# Puis, dans ce dépôt seulement :
#     git config user.email "<id>+<pseudo>@users.noreply.github.com"
#
# Publier délibérément une autre adresse reste possible, en le disant une fois :
#     git config postulat.adressePublique oui
#
# Deux usages :
#     identite.sh configuree          l'adresse que le prochain commit portera
#     identite.sh commits <révisions>  les auteurs et validateurs de ces commits

set -uo pipefail

if [ "$(git config --get postulat.adressePublique)" = "oui" ]; then
    exit 0
fi

admise() {
    case "$1" in
        *@users.noreply.github.com) return 0 ;;
        *) return 1 ;;
    esac
}

expliquer() {
    printf 'Elle serait publiée sur GitHub. Utiliser l'"'"'adresse « noreply » de GitHub :\n' >&2
    printf '    git config user.email "<id>+<pseudo>@users.noreply.github.com"\n' >&2
    printf '(GitHub > Settings > Emails > Keep my email address private.)\n' >&2
}

case "${1:-}" in
    configuree)
        adresse=$(git config --get user.email || true)
        if ! admise "$adresse"; then
            printf '[refusé] Le commit porterait l'"'"'adresse « %s ».\n' "${adresse:-aucune}" >&2
            expliquer
            exit 1
        fi
        ;;
    commits)
        shift
        if ! adresses=$(git log --format='%ae%n%ce' "$@"); then
            printf '[refusé] Lecture des commits impossible : envoi refusé.\n' >&2
            exit 1
        fi
        fautives=$(printf '%s\n' "$adresses" | sort -u | while IFS= read -r a; do
            [ -n "$a" ] && ! admise "$a" && printf '%s\n' "$a"
        done)
        if [ -n "$fautives" ]; then
            printf '[refusé] Des commits à envoyer portent une adresse personnelle :\n' >&2
            printf '    %s\n' $fautives >&2
            expliquer
            printf 'Pour corriger des commits pas encore publiés : git rebase -r <base> --exec "git commit --amend --no-edit --reset-author"\n' >&2
            exit 1
        fi
        ;;
    *)
        printf 'usage : %s configuree | commits <révisions>\n' "$0" >&2
        exit 2
        ;;
esac

exit 0
