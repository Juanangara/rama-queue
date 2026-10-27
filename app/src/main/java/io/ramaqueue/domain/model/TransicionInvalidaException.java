package io.ramaqueue.domain.model;

public class TransicionInvalidaException extends IllegalStateException {

    public TransicionInvalidaException(EstadoTrabajo desde, EstadoTrabajo hacia) {
        super("Transición inválida de ítem de trabajo: " + desde + " → " + hacia);
    }
}
