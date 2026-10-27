package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.Veredicto;

/** Aplica RN-07 (nombre), RN-08 (categoría) y RN-09 (sujetos truncados) a cada proceso encontrado. */
public final class ClasificadorProceso {

    private final NameMatcher nombre;
    private final Categoria categoria;

    public ClasificadorProceso(String nombreBuscado, Categoria categoria) {
        this.nombre = new NameMatcher(nombreBuscado);
        this.categoria = categoria;
    }

    /**
     * Evalúa con lo que muestra la búsqueda. Si los sujetos vienen truncados y lo visible no alcanza para
     * aceptar, devuelve {@link Veredicto#REQUIERE_SUJETOS}: lo oculto podría contener el nombre.
     */
    public Veredicto evaluar(ProcesoEncontrado proceso) {
        if (CategoryMatcher.excluido(categoria, proceso.despacho())) {
            return Veredicto.RECHAZADO;
        }
        if (coincide(proceso.despacho(), proceso.sujetosProcesales())) {
            return Veredicto.ACEPTADO;
        }
        return proceso.sujetosTruncados() ? Veredicto.REQUIERE_SUJETOS : Veredicto.RECHAZADO;
    }

    /** Evalúa con los sujetos ya completos (o un acumulado de páginas): nunca devuelve REQUIERE_SUJETOS. */
    public Veredicto evaluarConSujetos(String despacho, String sujetosCompletos) {
        if (CategoryMatcher.excluido(categoria, despacho)) {
            return Veredicto.RECHAZADO;
        }
        return coincide(despacho, sujetosCompletos) ? Veredicto.ACEPTADO : Veredicto.RECHAZADO;
    }

    private boolean coincide(String despacho, String sujetos) {
        return nombre.coincide(sujetos, despacho) && CategoryMatcher.coincide(categoria, despacho, sujetos);
    }
}
