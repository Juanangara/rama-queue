package io.ramaqueue.domain.policy;

/** Lo que importa a la planificación de una búsqueda; los procesos se evalúan aparte. */
public sealed interface RespuestaBusqueda {

    /** HTTP 200. Con cero resultados {@code totalPaginas} es 0. */
    record Exitosa(int totalRegistros, int totalPaginas) implements RespuestaBusqueda {
    }

    /** HTTP 400: más de mil registros para el ámbito. */
    record ExcedeMil() implements RespuestaBusqueda {
    }
}
