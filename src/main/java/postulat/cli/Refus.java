package postulat.cli;

/** Demande refusée par la ligne de commande elle-même : candidature introuvable, doublon. */
final class Refus extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Refus(String message) {
        super(message);
    }
}
