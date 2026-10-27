package io.ramaqueue.application.port.out;

import io.ramaqueue.domain.model.JobStatus;
import io.ramaqueue.domain.model.SearchJob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SearchJobRepository {

    void guardar(SearchJob job);

    Optional<SearchJob> buscar(UUID id);

    List<SearchJob> recientes(int limite);

    /** Pasa el job a un estado final solo si seguía RUNNING. Devuelve {@code true} para quien lo logró primero. */
    boolean finalizarSiCorriendo(UUID id, JobStatus estado, Instant ahora, String error);

    /** Marca el job como cancelado solo si seguía RUNNING. */
    boolean cancelarSiCorriendo(UUID id, Instant ahora);

    /** Un job ya terminado con ramas fallidas vuelve a RUNNING al reencolar sus ítems. */
    boolean reabrirSiTerminadoConProblemas(UUID id);
}
