package postulat.cas;

/**
 * Une demande refusée avant d'avoir rien changé : candidature introuvable, doublon, précondition non
 * remplie. Le message parle au candidat et dit quoi faire ; la ligne de commande et la fenêtre le
 * montrent tel quel.
 */
public final class Refus extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Refus(String message) {
        super(message);
    }
}
