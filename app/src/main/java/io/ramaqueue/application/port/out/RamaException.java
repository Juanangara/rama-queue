package io.ramaqueue.application.port.out;

import io.ramaqueue.domain.policy.HttpOutcomeClassifier;

/** La Rama Judicial respondió con un error, o no respondió ({@link HttpOutcomeClassifier#SIN_RESPUESTA}). */
public class RamaException extends RuntimeException {

    private final int estado;

    public RamaException(int estado, String mensaje) {
        super(mensaje);
        this.estado = estado;
    }

    public RamaException(int estado, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.estado = estado;
    }

    public int estado() {
        return estado;
    }
}
