package postulat.rendu;

/** Un document impossible à rendre sur une page, même au plus serré. Le message dit de combien il déborde. */
public class ErreurRendu extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ErreurRendu(String message) {
        super(message);
    }

    public ErreurRendu(String message, Throwable cause) {
        super(message, cause);
    }
}
