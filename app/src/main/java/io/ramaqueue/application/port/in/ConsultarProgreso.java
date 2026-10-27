package io.ramaqueue.application.port.in;

import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.SearchJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultarProgreso {

    record ProgresoDeJob(SearchJob job, ConteosDeTrabajo conteos, long procesosAceptados, long procesosCompletos) {
    }

    Optional<ProgresoDeJob> progreso(UUID jobId);

    List<SearchJob> recientes(int limite);
}
