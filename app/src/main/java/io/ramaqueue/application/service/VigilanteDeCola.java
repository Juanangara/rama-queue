package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.MantenerCola;
import io.ramaqueue.application.port.out.BrokerInspector;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.WorkItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Watchdog: recupera lo que un worker caído dejó en curso y lo que el broker perdió. */
public class VigilanteDeCola implements MantenerCola {

    private static final Logger log = LoggerFactory.getLogger(VigilanteDeCola.class);

    /** Colas cuyo vaciado indica que no hay mensajes en tránsito para ese grupo de ítems. */
    public record Colas(String trabajo, List<String> esperas) {
    }

    private final Clock reloj;
    private final WorkItemRepository items;
    private final UnidadDeTrabajo unidad;
    private final BrokerInspector broker;
    private final Duration atascoDespuesDe;
    private final Duration graciaDeRepublicacion;
    private final int maximoPorCiclo;
    private final Colas colasDeBusqueda;
    private final Colas colasDeDetalle;

    public VigilanteDeCola(Clock reloj, WorkItemRepository items, UnidadDeTrabajo unidad, BrokerInspector broker,
                           Duration atascoDespuesDe, Duration graciaDeRepublicacion, int maximoPorCiclo,
                           Colas colasDeBusqueda, Colas colasDeDetalle) {
        this.reloj = reloj;
        this.items = items;
        this.unidad = unidad;
        this.broker = broker;
        this.atascoDespuesDe = atascoDespuesDe;
        this.graciaDeRepublicacion = graciaDeRepublicacion;
        this.maximoPorCiclo = maximoPorCiclo;
        this.colasDeBusqueda = colasDeBusqueda;
        this.colasDeDetalle = colasDeDetalle;
    }

    @Override
    public int liberarAtascados() {
        Instant ahora = reloj.instant().truncatedTo(ChronoUnit.MICROS);
        int liberados = unidad.ejecutar(() -> {
            List<WorkItem> atascados = items.bloquearAtascados(ahora.minus(atascoDespuesDe), maximoPorCiclo);
            atascados.forEach(i -> items.guardar(i.liberarPorAtasco()));
            return atascados.size();
        });
        if (liberados > 0) {
            log.warn("Watchdog: {} ítems llevaban más de {} en curso y vuelven al outbox", liberados, atascoDespuesDe);
        }
        return liberados;
    }

    @Override
    public int reconciliar() {
        Instant ahora = reloj.instant().truncatedTo(ChronoUnit.MICROS);
        Instant publicadosAntesDe = ahora.minus(graciaDeRepublicacion);
        return reconciliar(true, colasDeBusqueda, publicadosAntesDe, ahora)
                + reconciliar(false, colasDeDetalle, publicadosAntesDe, ahora);
    }

    private int reconciliar(boolean deBusqueda, Colas colas, Instant publicadosAntesDe, Instant ahora) {
        // Solo se actúa si el broker está completamente vacío para este grupo: una cola larga es normal, no una pérdida.
        if (!broker.estadoDe(colas.trabajo()).vacia()) {
            return 0;
        }
        for (String espera : colas.esperas()) {
            if (!broker.estadoDe(espera).vacia()) {
                return 0;
            }
        }
        int republicados = unidad.ejecutar(() -> items.marcarParaRepublicar(deBusqueda, publicadosAntesDe, ahora));
        if (republicados > 0) {
            log.warn("Reconciliación: {} ítems figuraban como publicados pero el broker está vacío; vuelven al outbox",
                    republicados);
        }
        return republicados;
    }
}
