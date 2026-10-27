package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.ReencolarFallidos;
import io.ramaqueue.application.port.out.DlqInspector;
import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.EventPublisher.EventoDeProgreso;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.WorkItem;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * D-05. Los ítems FAILED vuelven a PENDING con los intentos en cero y sin publicar (el relay los publica de nuevo), los
 * jobs que habían terminado con problemas vuelven a RUNNING, y las copias de esos ítems se retiran de las DLQ.
 */
public class ReencolarFallidosService implements ReencolarFallidos {

    private final Clock reloj;
    private final WorkItemRepository items;
    private final SearchJobRepository jobs;
    private final UnidadDeTrabajo unidad;
    private final DlqInspector dlq;
    private final EventPublisher eventos;

    public ReencolarFallidosService(Clock reloj, WorkItemRepository items, SearchJobRepository jobs, UnidadDeTrabajo unidad,
                                    DlqInspector dlq, EventPublisher eventos) {
        this.reloj = reloj;
        this.items = items;
        this.jobs = jobs;
        this.unidad = unidad;
        this.dlq = dlq;
        this.eventos = eventos;
    }

    @Override
    public Resultado reencolar(UUID jobIdOTodos) {
        List<WorkItem> fallidos = items.fallidos(jobIdOTodos);
        Set<UUID> idsReencolados = new HashSet<>();
        Set<UUID> jobsAfectados = new HashSet<>();
        unidad.ejecutar(() -> {
            for (WorkItem fallido : fallidos) {
                items.guardar(fallido.reencolar());
                idsReencolados.add(fallido.id());
                jobsAfectados.add(fallido.jobId());
            }
            jobsAfectados.forEach(jobs::reabrirSiTerminadoConProblemas);
        });
        int retirados = idsReencolados.isEmpty() ? 0 : dlq.depurar(idsReencolados::contains);
        for (UUID job : jobsAfectados) {
            eventos.publicar(new EventoDeProgreso("JOB_REABIERTO", job, null, "ítems fallidos reencolados", reloj.instant()));
        }
        return new Resultado(idsReencolados.size(), retirados);
    }
}
