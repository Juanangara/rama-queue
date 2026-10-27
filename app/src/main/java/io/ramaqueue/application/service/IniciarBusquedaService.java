package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.IniciarBusqueda;
import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.EventPublisher.EventoDeProgreso;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.TipoPersona;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public class IniciarBusquedaService implements IniciarBusqueda {

    private final Clock reloj;
    private final SearchJobRepository jobs;
    private final WorkItemRepository items;
    private final UnidadDeTrabajo unidad;
    private final EventPublisher eventos;

    public IniciarBusquedaService(Clock reloj, SearchJobRepository jobs, WorkItemRepository items,
                                  UnidadDeTrabajo unidad, EventPublisher eventos) {
        this.reloj = reloj;
        this.jobs = jobs;
        this.items = items;
        this.unidad = unidad;
        this.eventos = eventos;
    }

    @Override
    public UUID iniciar(String nombre, TipoPersona tipoPersona, Categoria categoria) {
        SearchJob job = SearchJob.nuevo(UUID.randomUUID(), nombre, tipoPersona, categoria,
                reloj.instant().truncatedTo(ChronoUnit.MICROS));
        unidad.ejecutar(() -> {
            jobs.guardar(job);
            items.insertarNuevos(List.of(job.itemRaiz()));
        });
        eventos.publicar(new EventoDeProgreso("JOB_CREADO", job.id(), null, job.nombre(), job.creadoEn()));
        return job.id();
    }
}
