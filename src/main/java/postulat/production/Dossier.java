package postulat.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tout ce qui a été produit pour une candidature à un instant donné (glossaire § 4.2) : le CV ciblé et
 * la lettre, structurés phrase par phrase, leur évaluation, et le rapport de production (CA-5.5).
 * C'est un concept, pas un répertoire : les fichiers rendus viennent à la validation (UC-09).
 *
 * <p>Le candidat le corrige à la main sans appeler aucun modèle (UC-08) : une phrase réécrite devient
 * d'origine « auteur » (CA-3.8), une phrase se supprime, une puce retirée faute de place revient. Les
 * phrases se désignent par leur numéro, CV puis lettre, à partir de 1 : celui que l'affichage montre.
 * Ces opérations ne touchent ni l'évaluation ni la mise en page : les refaire est l'affaire de
 * l'appelant, qui doit refuser une correction qui ferait déborder la page (UC-08 E1).
 */
public record Dossier(Cv cv, Lettre lettre, Evaluation evaluation, Rapport rapport) {

    /**
     * @param passes nombre de passes de génération, la première comprise (CA-5.4)
     * @param retirees puces retirées pour tenir sur une page, dans l'ordre où elles l'ont été (RG-26, CA-3.13)
     * @param correctionsEcartees corrections du recruteur écartées parce qu'elles demandaient un fait
     *     absent du profil (RG-07)
     */
    public record Rapport(int passes, List<Retiree> retirees, List<String> avertissements, List<String> correctionsEcartees) {

        public Rapport {
            retirees = List.copyOf(retirees);
            avertissements = List.copyOf(avertissements);
            correctionsEcartees = List.copyOf(correctionsEcartees);
        }

        private Rapport avec(List<Retiree> autres) {
            return new Rapport(passes, autres, avertissements, correctionsEcartees);
        }
    }

    /**
     * Une puce retirée pour tenir sur une page, et le bloc où elle était — indices de section et de
     * contenu : elle peut y revenir si la place se libère (UC-08 A2).
     */
    public record Retiree(Cv.Puce puce, int section, int contenu) {

        public Retiree {
            Objects.requireNonNull(puce);
        }
    }

    public Dossier {
        Objects.requireNonNull(cv);
        Objects.requireNonNull(lettre);
        Objects.requireNonNull(evaluation);
        Objects.requireNonNull(rapport);
    }

    /** Toutes les phrases du dossier, CV puis lettre. */
    public List<Assertion> assertions() {
        List<Assertion> toutes = new ArrayList<>(cv.assertions());
        toutes.addAll(lettre.assertions());
        return toutes;
    }

    /** Combien de phrases sont de la main de l'auteur (CA-3.10). */
    public long assertionsDeLAuteur() {
        return assertions().stream().filter(a -> a.origine() instanceof Origine.Auteur).count();
    }

    /** La phrase n° {@code numero}, CV puis lettre, à partir de 1. */
    public Assertion phrase(int numero) {
        List<Assertion> toutes = assertions();
        if (numero < 1 || numero > toutes.size()) {
            throw new IllegalArgumentException("Le dossier n'a pas de phrase n° " + numero + " : elles vont de 1 à " + toutes.size() + ".");
        }
        return toutes.get(numero - 1);
    }

    /**
     * Le même dossier, la phrase n° {@code numero} réécrite par l'auteur : son origine devient « auteur »
     * (CA-3.8), et aucune génération ne la réécrira (RG-27). Une puce garde son rang de pertinence.
     */
    public Dossier corriger(int numero, String texte) {
        Assertion ancienne = phrase(numero);
        String net = texte == null ? "" : texte.strip();
        if (net.isEmpty()) {
            throw new IllegalArgumentException("Une phrase corrigée ne peut pas être vide ; pour la retirer, supprimez-la.");
        }
        if (net.equals(ancienne.texte())) {
            throw new IllegalArgumentException("La phrase n° " + numero + " dit déjà exactement cela : rien n'a changé.");
        }
        Assertion nouvelle = new Assertion(net, new Origine.Auteur());
        int i = numero - 1;
        int n = cv.assertions().size();
        return i < n
                ? new Dossier(cv.remplacer(i, nouvelle), lettre, evaluation, rapport)
                : new Dossier(cv, lettre.remplacer(i - n, nouvelle), evaluation, rapport);
    }

    /**
     * Le même dossier, la phrase n° {@code numero} — de l'auteur — marquée comme versée au profil dans
     * le fait {@code fait} (UC-04). Son origine reste « auteur » : le fait n'existait pas quand elle a
     * été écrite (RG-23).
     */
    public Dossier marquerVersee(int numero, String fait) {
        Assertion a = phrase(numero);
        if (!(a.origine() instanceof Origine.Auteur auteur)) {
            throw new IllegalArgumentException("La phrase n° " + numero + " n'est pas de votre main : elle s'appuie déjà sur le "
                    + "profil ou sur l'offre. Seule une phrase réécrite à la main se verse au profil (UC-04).");
        }
        if (auteur.verseeDans().isPresent()) {
            throw new IllegalArgumentException("La phrase n° " + numero + " est déjà versée au profil, dans le fait « "
                    + auteur.verseeDans().get() + " ».");
        }
        Assertion versee = new Assertion(a.texte(), new Origine.Auteur(Optional.of(fait)));
        int i = numero - 1;
        int n = cv.assertions().size();
        return i < n
                ? new Dossier(cv.remplacer(i, versee), lettre, evaluation, rapport)
                : new Dossier(cv, lettre.remplacer(i - n, versee), evaluation, rapport);
    }

    /**
     * Le fait sous lequel verser la phrase n° {@code numero}, quand le dossier le dit : celui que cite
     * l'intitulé du bloc d'une puce ou d'une précision du CV. Vide pour une phrase de la lettre.
     */
    public Optional<String> parentPropose(int numero) {
        phrase(numero);
        int i = numero - 1;
        if (i >= cv.assertions().size()) {
            return Optional.empty();
        }
        Cv.Place p = cv.place(i);
        if (p.role() != Cv.Role.PUCE && p.role() != Cv.Role.PRECISION) {
            return Optional.empty();
        }
        Cv.Bloc bloc = (Cv.Bloc) cv.sections().get(p.section()).contenus().get(p.contenu());
        return bloc.intitule().origine() instanceof Origine.Faits f && f.ids().size() == 1 ? Optional.of(f.ids().get(0)) : Optional.empty();
    }

    /**
     * Le même dossier sans la phrase n° {@code numero} (UC-08 A2). Supprimer l'intitulé d'un bloc retire
     * le bloc entier ; les puces qui en avaient été retirées faute de place ne peuvent plus y revenir.
     */
    public Dossier supprimer(int numero) {
        phrase(numero);
        int i = numero - 1;
        int n = cv.assertions().size();
        if (i >= n) {
            return new Dossier(cv, lettre.supprimer(i - n), evaluation, rapport);
        }
        Cv.Place p = cv.place(i);
        List<Retiree> retirees = rapport.retirees();
        if (p.role() == Cv.Role.INTITULE || p.role() == Cv.Role.LIGNE) {
            // un contenu entier disparaît : ceux qui le suivent dans sa section remontent d'un rang
            retirees = retirees.stream()
                    .filter(r -> r.section() != p.section() || r.contenu() != p.contenu())
                    .map(r -> r.section() == p.section() && r.contenu() > p.contenu()
                            ? new Retiree(r.puce(), r.section(), r.contenu() - 1) : r)
                    .toList();
        }
        return new Dossier(cv.supprimer(i), lettre, evaluation, rapport.avec(retirees));
    }

    /** Le même dossier, la puce retirée n° {@code numero} — à partir de 1 — remise dans son bloc (UC-08 A2). */
    public Dossier reintroduire(int numero) {
        List<Retiree> retirees = new ArrayList<>(rapport.retirees());
        if (numero < 1 || numero > retirees.size()) {
            throw new IllegalArgumentException(retirees.isEmpty() ? "Aucune puce n'a été retirée de ce CV."
                    : "Il n'y a pas de puce retirée n° " + numero + " : elles vont de 1 à " + retirees.size() + ".");
        }
        Retiree r = retirees.remove(numero - 1);
        return new Dossier(cv.avec(r.puce(), r.section(), r.contenu()), lettre, evaluation, rapport.avec(retirees));
    }
}
