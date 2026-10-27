package io.ramaqueue.domain.simulacion;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.model.Veredicto;
import io.ramaqueue.domain.model.WorkItem;
import io.ramaqueue.domain.policy.BackoffPolicy;
import io.ramaqueue.domain.policy.ClasificadorProceso;
import io.ramaqueue.domain.policy.ExpansionPlanner;
import io.ramaqueue.domain.policy.Plan;
import io.ramaqueue.domain.policy.RespuestaBusqueda;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Modelo en memoria del motor completo: cola, handler, outbox, reintentos y watchdog. Usa solo clases del dominio
 * y un gateway falso, así que demuestra que las reglas por sí solas garantizan que un job termina.
 */
final class SimuladorDeJob {

    record Config(double pBloqueo, double pTransitorio, double pDuplicado, double pCaida, long semilla) {
        static Config sinFallos(long semilla) {
            return new Config(0, 0, 0, 0, semilla);
        }
    }

    record Resultado(SearchJob job, WorkItem raiz, Map<UUID, WorkItem> items, Set<Long> aceptados,
                     int peticiones, int entregasDescartadas, int liberadosPorWatchdog) {

        boolean todosTerminales() {
            return items.values().stream().allMatch(WorkItem::esTerminal);
        }

        long cantidad(EstadoTrabajo estado) {
            return items.values().stream().filter(i -> i.estado() == estado).count();
        }

        ConteosDeTrabajo conteos() {
            Map<EstadoTrabajo, Long> m = new EnumMap<>(EstadoTrabajo.class);
            items.values().forEach(i -> m.merge(i.estado(), 1L, Long::sum));
            return new ConteosDeTrabajo(m);
        }
    }

    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");
    private static final int MAXIMO_DE_PASOS = 1_000_000;

    private final MundoSintetico mundo;
    private final Config config;
    private final Random azar;
    private final BackoffPolicy backoff = BackoffPolicy.porDefecto();
    private final ExpansionPlanner planner;
    private final ClasificadorProceso clasificador =
            new ClasificadorProceso(MundoSintetico.NOMBRE_BUSCADO, Categoria.PENAL);

    private final Map<UUID, WorkItem> items = new LinkedHashMap<>();
    private final Deque<UUID> cola = new ArrayDeque<>();
    private final Set<Long> aceptados = new HashSet<>();
    private final Map<Long, StringBuilder> sujetosAcumulados = new HashMap<>();
    private int peticiones;
    private int entregasDescartadas;
    private int liberadosPorWatchdog;

    SimuladorDeJob(MundoSintetico mundo, Config config) {
        this.mundo = mundo;
        this.config = config;
        this.azar = new Random(config.semilla());
        this.planner = new ExpansionPlanner(mundo.territorio());
    }

    Resultado ejecutar() {
        SearchJob job = SearchJob.nuevo(UUID.randomUUID(), MundoSintetico.NOMBRE_BUSCADO, TipoPersona.NATURAL,
                Categoria.PENAL, AHORA);
        WorkItem raiz = job.itemRaiz();
        publicar(raiz);

        int pasos = 0;
        while (true) {
            while (!cola.isEmpty()) {
                if (++pasos > MAXIMO_DE_PASOS) {
                    throw new IllegalStateException("La simulación no terminó en " + MAXIMO_DE_PASOS + " pasos");
                }
                procesar(cola.poll());
            }
            // Watchdog: lo que quedó EN CURSO porque un worker cayó se libera y se vuelve a publicar.
            boolean liberado = false;
            for (WorkItem item : new ArrayList<>(items.values())) {
                if (item.estado() == EstadoTrabajo.IN_PROGRESS) {
                    items.put(item.id(), item.liberarPorAtasco().marcarPublicado(AHORA));
                    cola.add(item.id());
                    liberadosPorWatchdog++;
                    liberado = true;
                }
            }
            if (!liberado) {
                break;
            }
        }
        return new Resultado(job, raiz, items, aceptados, peticiones, entregasDescartadas, liberadosPorWatchdog);
    }

    /** Equivale al outbox: el ítem entra una sola vez aunque se intente publicar varias. */
    private void publicar(WorkItem item) {
        if (!items.containsKey(item.id())) {
            items.put(item.id(), item.marcarPublicado(AHORA));
            cola.add(item.id());
        }
    }

    private void procesar(UUID id) {
        WorkItem item = items.get(id);
        if (item.esTerminal()) {
            entregasDescartadas++; // entrega duplicada o tardía: el handler es idempotente
            return;
        }
        item = item.iniciar(AHORA);
        items.put(id, item);

        if (azar.nextDouble() < config.pCaida()) {
            return; // el worker murió sin hacer ack: el ítem queda EN CURSO hasta que actúe el watchdog
        }
        double dado = azar.nextDouble();
        if (dado < config.pBloqueo()) {
            items.put(id, item.devolverPorBloqueo());
            cola.add(id);
            return;
        }
        if (dado < config.pBloqueo() + config.pTransitorio()) {
            if (backoff.agotado(item.intentos() + 1)) {
                items.put(id, item.fallar("Intentos agotados"));
            } else {
                items.put(id, item.reintentar("Fallo transitorio"));
                cola.add(id);
            }
            return;
        }

        peticiones++;
        ejecutar(item);
        if (azar.nextDouble() < config.pDuplicado()) {
            cola.add(id);
        }
    }

    private void ejecutar(WorkItem item) {
        switch (item.tipo()) {
            case SEARCH -> buscar(item);
            case LIST -> cerrar(item, planner.planificarListado(item, mundo.listar(item.codigo())), List.of(), null, null);
            case RESOLVE_SUJETOS -> resolver(item);
            case SUJETOS -> cerrar(item, planner.planificarSujetos(item, mundo.paginasSujetos(item.idProceso())), List.of(), null, null);
            case DETALLE, ACTUACIONES -> items.put(item.id(), item.completar(null, null));
        }
    }

    private void buscar(WorkItem item) {
        MundoSintetico.ResultadoPagina r = mundo.buscar(item.codigo(), item.pagina());
        Plan plan = planner.planificarBusqueda(item, r.respuesta());
        List<WorkItem> extra = new ArrayList<>();
        Integer registros = null;
        Integer paginas = null;
        if (r.respuesta() instanceof RespuestaBusqueda.Exitosa exitosa) {
            registros = exitosa.totalRegistros();
            paginas = exitosa.totalPaginas();
            for (ProcesoEncontrado p : r.procesos()) {
                Veredicto veredicto = clasificador.evaluar(p);
                if (veredicto == Veredicto.ACEPTADO) {
                    aceptados.add(p.idProceso());
                }
                extra.addAll(planner.trabajoParaProceso(item, p.idProceso(), veredicto));
            }
        }
        cerrar(item, plan, extra, registros, paginas);
    }

    private void resolver(WorkItem item) {
        long id = item.idProceso();
        sujetosAcumulados.computeIfAbsent(id, k -> new StringBuilder())
                .append(" | ").append(mundo.sujetosPagina(id, item.pagina()));
        Veredicto veredicto = clasificador.evaluarConSujetos(
                mundo.proceso(id).despacho(), sujetosAcumulados.get(id).toString());
        if (veredicto == Veredicto.ACEPTADO) {
            aceptados.add(id);
        }
        cerrar(item, planner.planificarResolucion(item, mundo.paginasSujetos(id), veredicto == Veredicto.ACEPTADO),
                List.of(), null, null);
    }

    /** Una transacción: el ítem pasa a su estado final y sus hijos quedan registrados (y publicados por el outbox). */
    private void cerrar(WorkItem item, Plan plan, List<WorkItem> extra, Integer registros, Integer paginas) {
        items.put(item.id(), plan.aplicarA(item, registros, paginas));
        plan.hijos().forEach(this::publicar);
        extra.forEach(this::publicar);
    }
}
