package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.PublicarPendientes;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.application.port.out.WorkItemRepository.PendientesDeTrabajo;
import io.ramaqueue.application.port.out.WorkPublisher;
import io.ramaqueue.domain.model.GrupoDeCola;
import io.ramaqueue.domain.model.WorkItem;
import io.ramaqueue.domain.policy.CuotaGarantizada;
import io.ramaqueue.domain.policy.RepartoJusto;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relay del outbox con reparto justo. En cada ciclo y para cada cola de trabajo: calcula cuánto cabe en el broker
 * ({@code maxEnBroker} menos lo ya publicado y pendiente), elige qué ítems publicar con {@link RepartoJusto} (el trabajo que menos
 * servicio ha recibido va primero, con una cuota garantizada para el más antiguo) y los publica esperando los confirms.
 *
 * <p>Todo ocurre en una transacción que toma los candidatos con {@code SKIP LOCKED}. Si el broker falla, se deshace y se reintenta en
 * el siguiente ciclo; si el proceso muere tras publicar y antes del commit, el lote se publica otra vez (los handlers son idempotentes).
 * Mantener poca cola en el broker es lo que hace efectiva la justicia: un trabajo nuevo espera detrás de pocos mensajes, no de miles.
 */
public class PublicadorDeOutbox implements PublicarPendientes {

    private final Clock reloj;
    private final WorkItemRepository items;
    private final WorkPublisher publicador;
    private final UnidadDeTrabajo unidad;
    private final int tamanoDeLote;
    private final int maxEnBroker;
    /** Una cuota por cola: el crédito acumulado de una no se gasta en la otra. */
    private final Map<GrupoDeCola, CuotaGarantizada> cuotas = new EnumMap<>(GrupoDeCola.class);

    public PublicadorDeOutbox(Clock reloj, WorkItemRepository items, WorkPublisher publicador, UnidadDeTrabajo unidad,
                              int tamanoDeLote, int maxEnBroker, double cuotaGarantizada) {
        this.reloj = reloj;
        this.items = items;
        this.publicador = publicador;
        this.unidad = unidad;
        this.tamanoDeLote = tamanoDeLote;
        this.maxEnBroker = maxEnBroker;
        for (GrupoDeCola grupo : GrupoDeCola.values()) {
            cuotas.put(grupo, new CuotaGarantizada(cuotaGarantizada, Math.max(1, maxEnBroker * cuotaGarantizada)));
        }
    }

    @Override
    public int publicarLote() {
        return unidad.ejecutar(() -> {
            int total = 0;
            for (GrupoDeCola grupo : GrupoDeCola.values()) {
                total += publicarGrupo(grupo);
            }
            return total;
        });
    }

    private int publicarGrupo(GrupoDeCola grupo) {
        long yaEnElBroker = items.publicadosEnElBroker(grupo);
        int cupo = (int) Math.min(tamanoDeLote, maxEnBroker - yaEnElBroker);
        if (cupo <= 0) {
            return 0; // el broker ya tiene suficiente: no se publica más hasta que los consumidores lo vacíen
        }
        List<PendientesDeTrabajo> candidatos = items.bloquearCandidatosDePublicacion(grupo, cupo);
        if (candidatos.isEmpty()) {
            return 0;
        }

        Map<UUID, WorkItem> porId = new HashMap<>();
        List<RepartoJusto.Trabajo> trabajos = new ArrayList<>();
        for (PendientesDeTrabajo pendientes : candidatos) {
            pendientes.items().forEach(item -> porId.put(item.id(), item));
            trabajos.add(new RepartoJusto.Trabajo(pendientes.jobId(), pendientes.creadoEn(), pendientes.servicio(),
                    pendientes.items().stream().map(WorkItem::id).toList()));
        }
        // El crédito de la cuota solo se acumula por lo que realmente se puede publicar en este ciclo.
        int disponibles = Math.min(cupo, porId.size());
        int garantizados = cuotas.get(grupo).asignar(disponibles);
        List<WorkItem> lote = RepartoJusto.elegir(trabajos, cupo, garantizados).stream().map(porId::get).toList();
        if (lote.isEmpty()) {
            return 0;
        }
        publicador.publicar(lote);
        items.marcarPublicados(lote.stream().map(WorkItem::id).toList(), reloj.instant().truncatedTo(ChronoUnit.MICROS));
        return lote.size();
    }
}
