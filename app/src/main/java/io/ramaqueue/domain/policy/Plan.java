package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.WorkItem;

import java.util.List;

/** Qué hacer con un ítem ya resuelto: en qué estado queda y qué ítems hijos hay que crear. */
public record Plan(EstadoTrabajo estado, List<WorkItem> hijos, String motivo) {

    public Plan {
        if (estado != EstadoTrabajo.DONE && estado != EstadoTrabajo.EXPANDED && estado != EstadoTrabajo.EXCEEDED) {
            throw new IllegalArgumentException("Un plan termina en DONE, EXPANDED o EXCEEDED, no en " + estado);
        }
        hijos = List.copyOf(hijos);
    }

    static Plan hecho(List<WorkItem> hijos) {
        return new Plan(EstadoTrabajo.DONE, hijos, null);
    }

    static Plan expandido(List<WorkItem> hijos) {
        return new Plan(EstadoTrabajo.EXPANDED, hijos, null);
    }

    static Plan excedido(String motivo) {
        return new Plan(EstadoTrabajo.EXCEEDED, List.of(), motivo);
    }

    /** Lleva el ítem en curso a su estado final según el plan. */
    public WorkItem aplicarA(WorkItem enCurso, Integer totalRegistros, Integer totalPaginas) {
        return switch (estado) {
            case DONE -> enCurso.completar(totalRegistros, totalPaginas);
            case EXPANDED -> enCurso.expandir(totalRegistros, totalPaginas);
            case EXCEEDED -> enCurso.excedido(motivo);
            default -> throw new IllegalStateException("Estado de plan inesperado: " + estado);
        };
    }
}
