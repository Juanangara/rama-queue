package io.ramaqueue.domain.model;

import java.util.EnumMap;
import java.util.Map;

/** Cuántos ítems de un job hay en cada estado (lo que devuelve un {@code GROUP BY status}). */
public record ConteosDeTrabajo(Map<EstadoTrabajo, Long> porEstado) {

    public ConteosDeTrabajo {
        EnumMap<EstadoTrabajo, Long> copia = new EnumMap<>(EstadoTrabajo.class);
        copia.putAll(porEstado);
        porEstado = java.util.Collections.unmodifiableMap(copia);
    }

    public long cantidad(EstadoTrabajo estado) {
        return porEstado.getOrDefault(estado, 0L);
    }

    public long activos() {
        return cantidad(EstadoTrabajo.PENDING) + cantidad(EstadoTrabajo.IN_PROGRESS);
    }

    public long total() {
        return porEstado.values().stream().mapToLong(Long::longValue).sum();
    }

    public long conProblema() {
        return cantidad(EstadoTrabajo.EXCEEDED) + cantidad(EstadoTrabajo.FAILED);
    }
}
