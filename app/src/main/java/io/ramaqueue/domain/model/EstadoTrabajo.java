package io.ramaqueue.domain.model;

/**
 * Máquina de estados de un ítem de trabajo.
 *
 * <pre>
 * PENDING ──► IN_PROGRESS ──► DONE | EXPANDED | EXCEEDED | FAILED | CANCELLED
 *    ▲            │
 *    └────────────┘ (reintento, bloqueo del servidor o watchdog)
 * FAILED ──► PENDING (solo al reencolar desde la DLQ)
 * </pre>
 */
public enum EstadoTrabajo {
    PENDING,
    IN_PROGRESS,
    /** Terminó y no generó más trabajo (o solo páginas/detalles ya creados). */
    DONE,
    /** El ámbito excedió mil y se delegó en ítems hijos más finos. */
    EXPANDED,
    /** Rama sin respuesta: excedió mil en el último nivel, o un listado vino vacío. */
    EXCEEDED,
    FAILED,
    CANCELLED;

    /** Terminal a efectos de la finalización del job (RN-16). */
    public boolean esTerminal() {
        return this != PENDING && this != IN_PROGRESS;
    }

    public boolean estaActivo() {
        return !esTerminal();
    }

    public boolean puedePasarA(EstadoTrabajo destino) {
        return switch (this) {
            case PENDING -> destino == IN_PROGRESS || destino == CANCELLED || destino == FAILED;
            case IN_PROGRESS -> true;
            case FAILED -> destino == PENDING;
            case DONE, EXPANDED, EXCEEDED, CANCELLED -> false;
        };
    }
}
