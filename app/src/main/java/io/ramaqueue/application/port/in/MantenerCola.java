package io.ramaqueue.application.port.in;

/** Tareas periódicas que impiden que un job se quede colgado. */
public interface MantenerCola {

    /** Ítems IN_PROGRESS de un worker que cayó: vuelven al outbox. Devuelve cuántos. */
    int liberarAtascados();

    /** Si el broker está vacío pero quedan PENDING «ya publicados», esos mensajes se perdieron: vuelven al outbox. */
    int reconciliar();
}
