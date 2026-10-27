package io.ramaqueue.application.port.in;

import io.ramaqueue.application.port.out.ProcesoHitRepository.FiltroDeResultados;
import io.ramaqueue.application.port.out.ProcesoHitRepository.PaginaDeResultados;

import java.util.Optional;
import java.util.UUID;

public interface ConsultarResultados {

    /** Vacío si el job no existe. El tamaño de página se acota a 1..200. */
    Optional<PaginaDeResultados> resultados(UUID jobId, FiltroDeResultados filtro, int pagina, int tamano);
}
