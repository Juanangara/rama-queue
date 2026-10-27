package io.ramaqueue.application.service;

import io.ramaqueue.application.port.in.ProcesarTrabajo;
import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.EventPublisher.EventoDeProgreso;
import io.ramaqueue.application.port.out.ProcesoHitRepository;
import io.ramaqueue.application.port.out.RamaException;
import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.application.port.out.RamaJudicialGateway.DetalleProceso;
import io.ramaqueue.application.port.out.RamaJudicialGateway.PaginaDeBusqueda;
import io.ramaqueue.application.port.out.RamaJudicialGateway.PaginaDeSujetos;
import io.ramaqueue.application.port.out.RamaJudicialGateway.UltimaActuacion;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.application.port.out.WorkPublisher;
import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.JobStatus;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.SujetoProceso;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.Veredicto;
import io.ramaqueue.domain.model.WorkItem;
import io.ramaqueue.domain.policy.BackoffPolicy;
import io.ramaqueue.domain.policy.CityDetector;
import io.ramaqueue.domain.policy.ClasificadorProceso;
import io.ramaqueue.domain.policy.ExpansionPlanner;
import io.ramaqueue.domain.policy.HttpOutcomeClassifier;
import io.ramaqueue.domain.policy.JobStatusResolver;
import io.ramaqueue.domain.policy.Plan;
import io.ramaqueue.domain.policy.ResultadoHttp;
import io.ramaqueue.domain.policy.RespuestaBusqueda;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * El handler de cada mensaje (guía §9 y §21). Un mensaje es un puntero a un ítem; la verdad está en Postgres.
 * <ol>
 *   <li>Reclama el ítem (idempotente: si ya es terminal o lo tiene otro worker, no hace nada).</li>
 *   <li>Hace UNA petición a la Rama Judicial y le pide al dominio el plan.</li>
 *   <li>En una sola transacción guarda el estado final del ítem, sus hijos y los procesos hallados.</li>
 *   <li>Si falló, clasifica el error y reintenta, devuelve por bloqueo o marca el ítem como fallido.</li>
 * </ol>
 */
public class ProcesadorDeTrabajo implements ProcesarTrabajo {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorDeTrabajo.class);

    private record Efectos(WorkItem resultado, List<WorkItem> hijos, List<Runnable> persistencias) {
    }

    private static final class ReclamoPerdidoException extends RuntimeException {
        ReclamoPerdidoException() {
            super("El reclamo del ítem ya no está vigente", null, false, false);
        }
    }

    private final Clock reloj;
    private final WorkItemRepository items;
    private final SearchJobRepository jobs;
    private final ProcesoHitRepository hits;
    private final RamaJudicialGateway rama;
    private final ExpansionPlanner planner;
    private final BackoffPolicy backoff;
    private final WorkPublisher publicador;
    private final EventPublisher eventos;
    private final UnidadDeTrabajo unidad;
    private final Duration obsolescenciaDelReclamo;

    public ProcesadorDeTrabajo(Clock reloj, WorkItemRepository items, SearchJobRepository jobs, ProcesoHitRepository hits,
                               RamaJudicialGateway rama, ExpansionPlanner planner, BackoffPolicy backoff,
                               WorkPublisher publicador, EventPublisher eventos, UnidadDeTrabajo unidad,
                               Duration obsolescenciaDelReclamo) {
        this.reloj = reloj;
        this.items = items;
        this.jobs = jobs;
        this.hits = hits;
        this.rama = rama;
        this.planner = planner;
        this.backoff = backoff;
        this.publicador = publicador;
        this.eventos = eventos;
        this.unidad = unidad;
        this.obsolescenciaDelReclamo = obsolescenciaDelReclamo;
    }

    @Override
    public ResultadoDeEntrega procesar(UUID itemId, boolean esReentrega) {
        Optional<WorkItem> reclamado = unidad.ejecutar(
                () -> items.reclamar(itemId, ahora(), esReentrega, obsolescenciaDelReclamo));
        if (reclamado.isEmpty()) {
            return ResultadoDeEntrega.IGNORADO;
        }
        WorkItem item = reclamado.get();
        SearchJob job = jobs.buscar(item.jobId())
                .orElseThrow(() -> new IllegalStateException("El ítem " + item.id() + " apunta a un job inexistente"));
        try {
            if (job.estado() == JobStatus.CANCELLED) {
                guardar(item, new Efectos(item.cancelar(), List.of(), List.of()));
            } else {
                try {
                    guardar(item, ejecutar(item, job));
                } catch (RamaException e) {
                    manejarError(item, e);
                }
            }
        } catch (ReclamoPerdidoException e) {
            log.info("Se perdió el reclamo del ítem {}; otro worker o el watchdog se hizo cargo", item.id());
            return ResultadoDeEntrega.IGNORADO;
        }
        finalizarSiCorresponde(job);
        return ResultadoDeEntrega.PROCESADO;
    }

    // ─── Ejecución según el tipo de ítem ─────────────────────────────────────────

    private Efectos ejecutar(WorkItem item, SearchJob job) {
        return switch (item.tipo()) {
            case SEARCH -> buscar(item, job);
            case LIST -> listar(item);
            case RESOLVE_SUJETOS -> resolverSujetos(item, job);
            case SUJETOS -> sujetos(item, job);
            case DETALLE -> detalle(item, job);
            case ACTUACIONES -> actuaciones(item, job);
        };
    }

    private Efectos buscar(WorkItem item, SearchJob job) {
        PaginaDeBusqueda pagina = null;
        RespuestaBusqueda respuesta;
        try {
            pagina = rama.buscar(job.nombre(), job.tipoPersona(), item.codigo(), item.pagina());
            respuesta = new RespuestaBusqueda.Exitosa(pagina.totalRegistros(), pagina.totalPaginas());
        } catch (RamaException e) {
            if (HttpOutcomeClassifier.clasificar(TipoTrabajo.SEARCH, e.estado()) != ResultadoHttp.EXCEDE_MIL) {
                throw e;
            }
            respuesta = new RespuestaBusqueda.ExcedeMil();
        }

        Plan plan = planner.planificarBusqueda(item, respuesta);
        List<WorkItem> hijos = new ArrayList<>(plan.hijos());
        List<Runnable> persistir = new ArrayList<>();
        Integer registros = null;
        Integer paginas = null;
        if (pagina != null) {
            registros = pagina.totalRegistros();
            paginas = pagina.totalPaginas();
            ClasificadorProceso clasificador = new ClasificadorProceso(job.nombre(), job.categoria());
            for (ProcesoEncontrado proceso : pagina.procesos()) {
                Veredicto veredicto = clasificador.evaluar(proceso);
                hijos.addAll(planner.trabajoParaProceso(item, proceso.idProceso(), veredicto));
                if (veredicto != Veredicto.RECHAZADO) {
                    boolean aceptado = veredicto == Veredicto.ACEPTADO;
                    String ciudad = CityDetector.detectar(proceso.despacho()).orElse(null);
                    persistir.add(() -> hits.guardar(job.id(), proceso, ciudad, job.categoria().codigo(), aceptado));
                }
            }
        }
        return new Efectos(plan.aplicarA(item, registros, paginas), hijos, persistir);
    }

    private Efectos listar(WorkItem item) {
        List<ItemListado> lista;
        try {
            lista = rama.listar(item.nivel(), item.codigo());
        } catch (RamaException e) {
            if (HttpOutcomeClassifier.clasificar(TipoTrabajo.LIST, e.estado()) != ResultadoHttp.LISTA_VACIA) {
                throw e;
            }
            lista = List.of();
        }
        Plan plan = planner.planificarListado(item, lista);
        return new Efectos(plan.aplicarA(item, null, null), plan.hijos(), List.of());
    }

    private Efectos resolverSujetos(WorkItem item, SearchJob job) {
        long id = item.idProceso();
        PaginaDeSujetos pagina = rama.sujetos(id, item.pagina());
        ProcesoEncontrado candidato = hits.buscar(job.id(), id)
                .orElseThrow(() -> new IllegalStateException("No hay candidato guardado para el proceso " + id));

        String acumulado = hits.textoDeSujetos(job.id(), id) + " | " + comoTexto(pagina.sujetos());
        boolean aceptado = new ClasificadorProceso(job.nombre(), job.categoria())
                .evaluarConSujetos(candidato.despacho(), acumulado) == Veredicto.ACEPTADO;

        Plan plan = planner.planificarResolucion(item, pagina.totalPaginas(), aceptado);
        List<Runnable> persistir = new ArrayList<>();
        persistir.add(() -> hits.agregarSujetos(job.id(), id, pagina.sujetos()));
        if (aceptado) {
            persistir.add(() -> hits.aceptar(job.id(), id));
        } else if (item.pagina() >= pagina.totalPaginas()) {
            persistir.add(() -> hits.rechazar(job.id(), id));
        }
        return new Efectos(plan.aplicarA(item, null, pagina.totalPaginas()), plan.hijos(), persistir);
    }

    private Efectos sujetos(WorkItem item, SearchJob job) {
        long id = item.idProceso();
        PaginaDeSujetos pagina = rama.sujetos(id, item.pagina());
        Plan plan = planner.planificarSujetos(item, pagina.totalPaginas());
        return new Efectos(plan.aplicarA(item, null, pagina.totalPaginas()), plan.hijos(),
                List.of(() -> hits.agregarSujetos(job.id(), id, pagina.sujetos())));
    }

    private Efectos detalle(WorkItem item, SearchJob job) {
        long id = item.idProceso();
        DetalleProceso detalle = rama.detalle(id);
        return new Efectos(item.completar(null, null), List.of(),
                List.of(() -> hits.guardarDetalle(job.id(), id, detalle)));
    }

    private Efectos actuaciones(WorkItem item, SearchJob job) {
        long id = item.idProceso();
        UltimaActuacion ultima = rama.ultimaActuacion(id).orElse(null);
        return new Efectos(item.completar(null, null), List.of(),
                List.of(() -> hits.guardarUltimaActuacion(job.id(), id, ultima)));
    }

    private static String comoTexto(List<SujetoProceso> sujetos) {
        return String.join(" | ", sujetos.stream().map(SujetoProceso::comoTexto).toList());
    }

    // ─── Confirmación ────────────────────────────────────────────────────────────

    /** Estado final del ítem + sus hijos + los procesos hallados, todo o nada. */
    private void guardar(WorkItem reclamado, Efectos efectos) {
        unidad.ejecutar(() -> {
            if (!items.confirmar(reclamado, efectos.resultado())) {
                throw new ReclamoPerdidoException();
            }
            if (!efectos.hijos().isEmpty()) {
                items.insertarNuevos(efectos.hijos());
            }
            efectos.persistencias().forEach(Runnable::run);
        });
        eventos.publicar(new EventoDeProgreso("ITEM_" + efectos.resultado().estado(), reclamado.jobId(),
                reclamado.id(), reclamado.tipo() + " " + reclamado.codigo(), ahora()));
    }

    // ─── Errores ─────────────────────────────────────────────────────────────────

    private void manejarError(WorkItem item, RamaException error) {
        ResultadoHttp resultado = HttpOutcomeClassifier.clasificar(item.tipo(), error.estado());
        switch (resultado) {
            case BLOQUEADO -> {
                WorkItem devuelto = item.devolverPorBloqueo();
                guardar(item, new Efectos(devuelto, List.of(), List.of()));
                reenviar(devuelto, 0);
            }
            case TRANSITORIO -> {
                if (backoff.agotado(item.intentos() + 1)) {
                    fallar(item, "Intentos agotados: " + error.getMessage());
                } else {
                    WorkItem reintento = item.reintentar(error.getMessage());
                    guardar(item, new Efectos(reintento, List.of(), List.of()));
                    reenviar(reintento, backoff.indiceNivel(reintento.intentos()));
                }
            }
            default -> fallar(item, "HTTP " + error.estado() + ": " + error.getMessage());
        }
    }

    private void fallar(WorkItem item, String motivo) {
        WorkItem fallido = item.fallar(motivo);
        guardar(item, new Efectos(fallido, List.of(), List.of()));
        try {
            publicador.enviarADlq(fallido, motivo);
        } catch (RuntimeException e) {
            // El ítem ya quedó FAILED en la base de datos, que es la verdad; la copia en la DLQ es solo informativa.
            log.warn("No se pudo copiar el ítem fallido {} a la DLQ: {}", fallido.id(), e.getMessage());
        }
    }

    /** Si no se logra publicar el reintento, el ítem vuelve al outbox y el relay lo publicará. */
    private void reenviar(WorkItem pendiente, int nivelDeReintento) {
        try {
            publicador.publicarReintento(pendiente, nivelDeReintento);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar el reintento del ítem {}; vuelve al outbox: {}", pendiente.id(), e.getMessage());
            unidad.ejecutar(() -> items.guardar(pendiente.paraRepublicar()));
        }
    }

    // ─── Fin del job ─────────────────────────────────────────────────────────────

    private void finalizarSiCorresponde(SearchJob job) {
        if (items.hayActivos(job.id())) {
            return;
        }
        ConteosDeTrabajo conteos = items.conteos(job.id());
        EstadoTrabajo estadoRaiz = items.buscar(job.itemRaiz().id()).map(WorkItem::estado).orElse(EstadoTrabajo.FAILED);
        JobStatus estado = JobStatusResolver.resolver(conteos, estadoRaiz, false);
        if (estado.esFinal() && jobs.finalizarSiCorriendo(job.id(), estado, ahora(), null)) {
            log.info("Job {} finalizado: {} ({} ítems)", job.id(), estado, conteos.total());
            eventos.publicar(new EventoDeProgreso("JOB_FINALIZADO", job.id(), null, estado.name(), ahora()));
        }
    }

    private Instant ahora() {
        return reloj.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
