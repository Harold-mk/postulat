package postulat.suivi;

/** Opération refusée parce qu'elle violerait une règle de gestion. Le message est destiné au candidat. */
public class RefusMetier extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefusMetier(String message) {
        super(message);
    }
}
