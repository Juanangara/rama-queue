package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.ConsultarProgreso;
import io.ramaqueue.application.port.out.ProcesoHitRepository;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.SearchJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ConsultarProgresoService implements ConsultarProgreso {

    private final SearchJobRepository jobs;
    private final WorkItemRepository items;
    private final ProcesoHitRepository hits;

    public ConsultarProgresoService(SearchJobRepository jobs, WorkItemRepository items, ProcesoHitRepository hits) {
        this.jobs = jobs;
        this.items = items;
        this.hits = hits;
    }

    @Override
    public Optional<ProgresoDeJob> progreso(UUID jobId) {
        return jobs.buscar(jobId).map(job -> {
            ProcesoHitRepository.Conteo conteo = hits.contar(jobId);
            return new ProgresoDeJob(job, items.conteos(jobId), conteo.aceptados(), conteo.completos());
        });
    }

    @Override
    public List<SearchJob> recientes(int limite) {
        return jobs.recientes(limite);
    }
}
