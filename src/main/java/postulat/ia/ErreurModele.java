package postulat.ia;

import java.util.Optional;

/**
 * Un appel au modèle qui n'a pas abouti. Le message est destiné au candidat et dit quoi faire.
 *
 * <p>La nature distingue ce qui se réessaie plus tard de ce qui se corrige : un modèle indisponible ne
 * fait rien perdre — l'offre reste conservée, analysable plus tard (UC-05 E2) —, une clé absente se
 * corrige une fois pour toutes.
 */
public class ErreurModele extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Nature {
        /** Réseau, surcharge, limite de débit : réessayer plus tard. */
        INDISPONIBLE,
        /** Clé d'API absente ou refusée, crédit épuisé, modèle inconnu : corriger la configuration. */
        CONFIGURATION,
        /** Le modèle a décliné la demande (classifieurs de sûreté). */
        REFUS,
        /** La réponse a atteint sa longueur maximale : coupée, donc inutilisable. */
        TRONQUEE,
        /** La réponse ne respecte pas la forme demandée, ou la demande a été rejetée par l'API. */
        INVALIDE
    }

    private final Nature nature;
    private final transient Consommation consommation;

    public ErreurModele(Nature nature, String message, Consommation consommation, Throwable cause) {
        super(message, cause);
        this.nature = nature;
        this.consommation = consommation;
    }

    public Nature nature() {
        return nature;
    }

    public boolean reessayable() {
        return nature == Nature.INDISPONIBLE;
    }

    /** Ce qui a été consommé malgré l'échec : un refus ou une réponse tronquée se paient aussi. */
    public Optional<Consommation> consommation() {
        return Optional.ofNullable(consommation);
    }
}
