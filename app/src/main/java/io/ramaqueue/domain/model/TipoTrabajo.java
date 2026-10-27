package io.ramaqueue.domain.model;

/** Cada ítem de trabajo equivale a exactamente una petición HTTP a la Rama Judicial (salvo listados en caché). */
public enum TipoTrabajo {
    /** Una página de la búsqueda por nombre en un ámbito territorial. */
    SEARCH,
    /** Listado de entidades, especialidades o despachos hijos de un ámbito que excedió mil. */
    LIST,
    /** Una página de los sujetos de un proceso con {@code sujetosProcesales} truncado, para decidir si aplica. */
    RESOLVE_SUJETOS,
    DETALLE,
    ACTUACIONES,
    /** Una página de los sujetos completos de un proceso aceptado. */
    SUJETOS;

    public boolean esDeBusqueda() {
        return this == SEARCH || this == LIST;
    }

    public boolean esDeDetalle() {
        return !esDeBusqueda();
    }

    /** Tipos cuyo resultado puede venir en varias páginas que se piden como ítems aparte. */
    public boolean paginado() {
        return this == SEARCH || this == SUJETOS || this == RESOLVE_SUJETOS;
    }
}
