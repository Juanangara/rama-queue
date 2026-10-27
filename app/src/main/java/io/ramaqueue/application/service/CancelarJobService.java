package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.CancelarJob;
import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.EventPublisher.EventoDeProgreso;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class CancelarJobService implements CancelarJob {

    private final Clock reloj;
    private final SearchJobRepository jobs;
    private final WorkItemRepository items;
    private final UnidadDeTrabajo unidad;
    private final EventPublisher eventos;

    public CancelarJobService(Clock reloj, SearchJobRepository jobs, WorkItemRepository items, UnidadDeTrabajo unidad,
                              EventPublisher eventos) {
        this.reloj = reloj;
        this.jobs = jobs;
        this.items = items;
        this.unidad = unidad;
        this.eventos = eventos;
    }

    @Override
    public Resultado cancelar(UUID jobId) {
        if (jobs.buscar(jobId).isEmpty()) {
            return Resultado.NO_EXISTE;
        }
        Instant ahora = reloj.instant().truncatedTo(ChronoUnit.MICROS);
        boolean cancelado = unidad.ejecutar(() -> {
            if (!jobs.cancelarSiCorriendo(jobId, ahora)) {
                return false;
            }
            items.cancelarPendientes(jobId);
            return true;
        });
        if (!cancelado) {
            return Resultado.YA_TERMINO;
        }
        eventos.publicar(new EventoDeProgreso("JOB_CANCELADO", jobId, null, "cancelado por el usuario", ahora));
        return Resultado.CANCELADO;
    }
}
