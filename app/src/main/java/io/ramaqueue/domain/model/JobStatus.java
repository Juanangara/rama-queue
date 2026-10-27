package io.ramaqueue.domain.model;

public enum JobStatus {
    RUNNING,
    COMPLETED,
    /** Terminó, pero alguna rama quedó sin respuesta (EXCEEDED) o falló. */
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean esFinal() {
        return this != RUNNING;
    }
}
