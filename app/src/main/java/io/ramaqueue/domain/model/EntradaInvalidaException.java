package io.ramaqueue.domain.model;

public class EntradaInvalidaException extends IllegalArgumentException {

    public EntradaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
