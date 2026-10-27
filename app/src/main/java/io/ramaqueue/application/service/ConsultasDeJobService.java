package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.ConsultarArbol;
import io.ramaqueue.application.port.in.ConsultarResultados;
import io.ramaqueue.application.port.out.ProcesoHitRepository;
import io.ramaqueue.application.port.out.ProcesoHitRepository.FiltroDeResultados;
import io.ramaqueue.application.port.out.ProcesoHitRepository.PaginaDeResultados;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.application.port.out.WorkItemRepository.NodoDeArbol;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Las dos consultas de lectura de un job: sus resultados y su árbol de búsqueda. */
public class ConsultasDeJobService implements ConsultarResultados, ConsultarArbol {

    static final int TAMANO_MAXIMO = 200;
    static final int LIMITE_DE_NODOS = 5_000;

    private final SearchJobRepository jobs;
    private final ProcesoHitRepository hits;
    private final WorkItemRepository items;

    public ConsultasDeJobService(SearchJobRepository jobs, ProcesoHitRepository hits, WorkItemRepository items) {
        this.jobs = jobs;
        this.hits = hits;
        this.items = items;
    }

    @Override
    public Optional<PaginaDeResultados> resultados(UUID jobId, FiltroDeResultados filtro, int pagina, int tamano) {
        if (jobs.buscar(jobId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hits.listar(jobId, filtro, Math.max(1, pagina), Math.min(Math.max(1, tamano), TAMANO_MAXIMO)));
    }

    @Override
    public Optional<List<NodoDeArbol>> arbol(UUID jobId) {
        return jobs.buscar(jobId).map(job -> items.arbol(jobId, LIMITE_DE_NODOS));
    }
}
