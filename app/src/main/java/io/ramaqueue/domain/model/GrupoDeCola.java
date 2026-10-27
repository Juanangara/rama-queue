package io.ramaqueue.domain.model;

import java.util.List;

/** Las dos colas de trabajo: cada una se llena (y se vigila) por separado. */
public enum GrupoDeCola {
    BUSQUEDA(List.of(TipoTrabajo.SEARCH, TipoTrabajo.LIST)),
    DETALLE(List.of(TipoTrabajo.RESOLVE_SUJETOS, TipoTrabajo.DETALLE, TipoTrabajo.ACTUACIONES, TipoTrabajo.SUJETOS));

    private final List<TipoTrabajo> tipos;

    GrupoDeCola(List<TipoTrabajo> tipos) {
        this.tipos = tipos;
    }

    public List<TipoTrabajo> tipos() {
        return tipos;
    }

    public static GrupoDeCola de(TipoTrabajo tipo) {
        return tipo.esDeBusqueda() ? BUSQUEDA : DETALLE;
    }
}
