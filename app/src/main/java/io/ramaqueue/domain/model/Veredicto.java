package io.ramaqueue.domain.model;

/** Decisión sobre un proceso encontrado, antes de gastar peticiones en su detalle. */
public enum Veredicto {
    ACEPTADO,
    RECHAZADO,
    /** Sujetos truncados: hay que pedir los sujetos completos para decidir. */
    REQUIERE_SUJETOS
}
