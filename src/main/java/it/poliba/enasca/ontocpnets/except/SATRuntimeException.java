package it.poliba.enasca.ontocpnets.except;

/**
 * The superclass of runtime exceptions thrown by a {@link it.poliba.enasca.ontocpnets.sat.SAT4JSolver}.
 */
public class SATRuntimeException extends RuntimeException {
    public SATRuntimeException(Throwable cause) {
        super(cause);
    }
}
