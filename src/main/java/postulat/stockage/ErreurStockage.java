package postulat.stockage;

/** Échec technique du stockage. Le message est destiné au candidat et dit ce qui a été préservé. */
public class ErreurStockage extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ErreurStockage(String message) {
        super(message);
    }

    public ErreurStockage(String message, Throwable cause) {
        super(message, cause);
    }
}
