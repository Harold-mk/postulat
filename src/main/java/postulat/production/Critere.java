package postulat.production;

/**
 * La grille du recruteur simulé (CA-5.2) : cinq critères explicites, chacun noté de 0 à 5. La grille est
 * fixe et documentée, pour que deux avis se comparent et qu'une note se discute critère par critère.
 */
public enum Critere {
    ADEQUATION("Adéquation", "Les exigences obligatoires de l'offre sont-elles démontrées par le dossier ?"),
    PREUVES("Preuves", "Les affirmations s'appuient-elles sur des réalisations précises, chiffrées quand c'est possible ?"),
    CIBLAGE("Ciblage", "Le dossier parle-t-il de ce poste et de cette entreprise, plutôt que de tout ?"),
    CLARTE("Clarté", "Se lit-il vite : phrases courtes, l'essentiel d'abord, sans remplissage ?"),
    LANGUE("Langue", "L'orthographe, la syntaxe et le registre sont-ils irréprochables ?");

    private final String libelle;
    private final String question;

    Critere(String libelle, String question) {
        this.libelle = libelle;
        this.question = question;
    }

    public String libelle() {
        return libelle;
    }

    public String question() {
        return question;
    }
}
