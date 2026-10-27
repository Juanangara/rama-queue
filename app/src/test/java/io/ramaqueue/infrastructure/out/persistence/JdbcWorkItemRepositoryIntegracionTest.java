package io.ramaqueue.infrastructure.out.persistence;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.GrupoDeCola;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.WorkItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcWorkItemRepositoryIntegracionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static PostgresTestSupport bd;
    private JdbcWorkItemRepository items;
    private JdbcSearchJobRepository jobs;
    private SearchJob job;

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    @BeforeAll
    static void migrar() {
        bd = new PostgresTestSupport(POSTGRES);
    }

    @BeforeEach
    void preparar() {
        bd.limpiar();
        items = new JdbcWorkItemRepository(bd.jdbc, bd.transacciones);
        jobs = new JdbcSearchJobRepository(bd.jdbc);
        job = SearchJob.nuevo(UUID.randomUUID(), "JUAN GARCIA", TipoPersona.NATURAL, Categoria.PENAL, T0);
        jobs.guardar(job);
    }

    private WorkItem nuevoItem(String codigo) {
        return WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, codigo, "ETIQUETA " + codigo, 1, null);
    }

    // ─── Inserción idempotente ───────────────────────────────────────────────────

    @Test
    void insertarElMismoItemDosVecesNoLoDuplica() {
        WorkItem item = nuevoItem("05");

        assertThat(items.insertarNuevos(List.of(item))).isEqualTo(1);
        assertThat(items.insertarNuevos(List.of(item))).isZero();
        assertThat(bd.jdbc.queryForObject("SELECT count(*) FROM work_item", Long.class)).isEqualTo(1);
        assertThat(items.buscar(item.id())).hasValueSatisfying(guardado -> {
            assertThat(guardado.codigo()).isEqualTo("05");
            assertThat(guardado.etiqueta()).isEqualTo("ETIQUETA 05");
            assertThat(guardado.nivel()).isEqualTo(Nivel.DEPARTAMENTO);
            assertThat(guardado.estado()).isEqualTo(EstadoTrabajo.PENDING);
            assertThat(guardado.pendienteDePublicar()).isTrue();
        });
    }

    @Test
    void losItemsDeDetalleGuardanSuIdDeProcesoYNoTienenNivel() {
        WorkItem detalle = WorkItem.nuevo(job.id(), null, TipoTrabajo.DETALLE, null, "", null, 1, 27598405L);

        items.insertarNuevos(List.of(detalle));

        assertThat(items.buscar(detalle.id())).hasValueSatisfying(g -> {
            assertThat(g.idProceso()).isEqualTo(27598405L);
            assertThat(g.nivel()).isNull();
            assertThat(g.tipo()).isEqualTo(TipoTrabajo.DETALLE);
        });
    }

    // ─── Reclamo ─────────────────────────────────────────────────────────────────

    @Test
    void reclamarPasaAEnCursoYUnSegundoReclamoNoLoToma() {
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));

        assertThat(items.reclamar(item.id(), T0, false, Duration.ofMinutes(5))).hasValueSatisfying(r -> {
            assertThat(r.estado()).isEqualTo(EstadoTrabajo.IN_PROGRESS);
            assertThat(r.bloqueadoEn()).isEqualTo(T0);
        });
        assertThat(items.reclamar(item.id(), T0.plusSeconds(1), false, Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void unaReentregaOUnReclamoObsoletoPermitenTomarUnItemEnCurso() {
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));
        items.reclamar(item.id(), T0, false, Duration.ofMinutes(5));

        assertThat(items.reclamar(item.id(), T0.plusSeconds(1), true, Duration.ofMinutes(5))).isPresent();
        assertThat(items.reclamar(item.id(), T0.plusSeconds(2), false, Duration.ofMinutes(5))).isEmpty();
        assertThat(items.reclamar(item.id(), T0.plus(Duration.ofMinutes(6)), false, Duration.ofMinutes(5))).isPresent();
    }

    @Test
    void unItemTerminalOInexistenteNoSeReclama() {
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));
        WorkItem reclamado = items.reclamar(item.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        assertThat(items.confirmar(reclamado, reclamado.completar(10, 1))).isTrue();

        assertThat(items.reclamar(item.id(), T0.plusSeconds(1), true, Duration.ofMinutes(5))).isEmpty();
        assertThat(items.reclamar(UUID.randomUUID(), T0, true, Duration.ofMinutes(5))).isEmpty();
    }

    // ─── Confirmación condicionada al reclamo ────────────────────────────────────

    @Test
    void confirmarGuardaElResultadoSoloSiElReclamoSigueVigente() {
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));
        WorkItem primero = items.reclamar(item.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        // Otro worker toma el ítem (reentrega): el reclamo del primero ya no vale.
        WorkItem segundo = items.reclamar(item.id(), T0.plusSeconds(10), true, Duration.ofMinutes(5)).orElseThrow();

        assertThat(items.confirmar(primero, primero.completar(1, 1))).isFalse();
        assertThat(items.buscar(item.id()).orElseThrow().estado()).isEqualTo(EstadoTrabajo.IN_PROGRESS);

        assertThat(items.confirmar(segundo, segundo.completar(7, 1))).isTrue();
        assertThat(items.buscar(item.id()).orElseThrow()).satisfies(g -> {
            assertThat(g.estado()).isEqualTo(EstadoTrabajo.DONE);
            assertThat(g.totalRegistros()).isEqualTo(7);
        });
    }

    @Test
    void unReintentoConservaElIntentoYElMensajeDeError() {
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));
        WorkItem reclamado = items.reclamar(item.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();

        assertThat(items.confirmar(reclamado, reclamado.reintentar("timeout"))).isTrue();

        assertThat(items.buscar(item.id()).orElseThrow()).satisfies(g -> {
            assertThat(g.estado()).isEqualTo(EstadoTrabajo.PENDING);
            assertThat(g.intentos()).isEqualTo(1);
            assertThat(g.ultimoError()).isEqualTo("timeout");
            assertThat(g.bloqueadoEn()).isNull();
        });
    }

    // ─── Outbox ──────────────────────────────────────────────────────────────────

    @Test
    void elOutboxSoloDevuelveLosPendientesSinPublicarYLuegoNoLosRepite() {
        WorkItem a = nuevoItem("05");
        WorkItem b = nuevoItem("11");
        WorkItem yaPublicado = nuevoItem("13").marcarPublicado(T0);
        items.insertarNuevos(List.of(a, b, yaPublicado));

        List<WorkItem> lote = new TransactionTemplate(bd.transacciones).execute(
                estado -> candidatos(GrupoDeCola.BUSQUEDA, 100));
        assertThat(lote).extracting(WorkItem::codigo).containsExactlyInAnyOrder("05", "11");

        items.marcarPublicados(lote.stream().map(WorkItem::id).toList(), T0);
        assertThat(items.bloquearCandidatosDePublicacion(GrupoDeCola.BUSQUEDA, 100)).isEmpty();
        assertThat(items.buscar(a.id()).orElseThrow().publicadoEn()).isEqualTo(T0);
    }

    private List<WorkItem> candidatos(GrupoDeCola grupo, int porTrabajo) {
        return items.bloquearCandidatosDePublicacion(grupo, porTrabajo).stream()
                .flatMap(p -> p.items().stream()).toList();
    }

    @Test
    void losCandidatosSeAgrupanPorTrabajoConSuServicioYSuAntiguedad() {
        SearchJob otro = SearchJob.nuevo(UUID.randomUUID(), "MARIA PEREZ", TipoPersona.JURIDICA, Categoria.CIVIL, T0.plusSeconds(60));
        jobs.guardar(otro);

        // El trabajo 1 ya recibió servicio: dos ítems terminados y uno en curso (los pendientes no cuentan, ni los cancelados).
        WorkItem hecho1 = nuevoItem("01").marcarPublicado(T0).iniciar(T0).completar(5, 1);
        WorkItem hecho2 = nuevoItem("02").marcarPublicado(T0).iniciar(T0).completar(5, 1);
        WorkItem enCurso = nuevoItem("03").marcarPublicado(T0).iniciar(T0);
        WorkItem cancelado = nuevoItem("04").cancelar();
        WorkItem pendiente1 = nuevoItem("05");
        WorkItem pendiente2 = WorkItem.nuevo(otro.id(), null, TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, "05", "X", 1, null);
        items.insertarNuevos(List.of(hecho1, hecho2, enCurso, cancelado, pendiente1, pendiente2));

        List<WorkItemRepository.PendientesDeTrabajo> grupos = new TransactionTemplate(bd.transacciones)
                .execute(estado -> items.bloquearCandidatosDePublicacion(GrupoDeCola.BUSQUEDA, 10));

        assertThat(grupos).hasSize(2);
        WorkItemRepository.PendientesDeTrabajo delPrimero = grupos.stream().filter(g -> g.jobId().equals(job.id())).findFirst().orElseThrow();
        WorkItemRepository.PendientesDeTrabajo delOtro = grupos.stream().filter(g -> g.jobId().equals(otro.id())).findFirst().orElseThrow();
        assertThat(delPrimero.servicio()).isEqualTo(3);
        assertThat(delPrimero.items()).extracting(WorkItem::codigo).containsExactly("05");
        assertThat(delPrimero.creadoEn()).isEqualTo(T0);
        assertThat(delOtro.servicio()).isZero();
        assertThat(delOtro.creadoEn()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void dentroDeUnTrabajoSalenPrimeroLosDeMasProfundidadYElTopePorTrabajoSeRespeta() {
        WorkItem raiz = WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", "TODO", 1, null);
        WorkItem municipio = WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.MUNICIPIO, "05001", "M", 1, null);
        WorkItem despacho = WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.DESPACHO, "050013109001", "D", 1, null);
        WorkItem depto = WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, "05", "A", 1, null);
        items.insertarNuevos(List.of(raiz, municipio, despacho, depto));

        List<WorkItem> dos = new TransactionTemplate(bd.transacciones).execute(estado -> candidatos(GrupoDeCola.BUSQUEDA, 2));

        assertThat(dos).extracting(WorkItem::nivel).containsExactly(Nivel.DESPACHO, Nivel.MUNICIPIO);
    }

    @Test
    void cadaGrupoDeColaSoloVeSusTiposYSeCuentaLoPublicadoEnElBroker() {
        WorkItem busqueda = nuevoItem("05");
        WorkItem detalle = WorkItem.nuevo(job.id(), null, TipoTrabajo.DETALLE, null, "", null, 1, 123L);
        WorkItem detallePublicado = WorkItem.nuevo(job.id(), null, TipoTrabajo.SUJETOS, null, "", null, 1, 456L).marcarPublicado(T0);
        WorkItem busquedaPublicada = nuevoItem("11").marcarPublicado(T0);
        WorkItem busquedaEnCurso = nuevoItem("13").marcarPublicado(T0).iniciar(T0); // salió del broker: ya no cuenta
        items.insertarNuevos(List.of(busqueda, detalle, detallePublicado, busquedaPublicada, busquedaEnCurso));

        List<WorkItem> deBusqueda = new TransactionTemplate(bd.transacciones).execute(estado -> candidatos(GrupoDeCola.BUSQUEDA, 10));
        List<WorkItem> deDetalle = new TransactionTemplate(bd.transacciones).execute(estado -> candidatos(GrupoDeCola.DETALLE, 10));

        assertThat(deBusqueda).extracting(WorkItem::id).containsExactly(busqueda.id());
        assertThat(deDetalle).extracting(WorkItem::id).containsExactly(detalle.id());
        assertThat(items.publicadosEnElBroker(GrupoDeCola.BUSQUEDA)).isEqualTo(1);
        assertThat(items.publicadosEnElBroker(GrupoDeCola.DETALLE)).isEqualTo(1);
    }

    @Test
    void dosRelaysConcurrentesNoTomanLosMismosItems() throws Exception {
        List<WorkItem> todos = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            todos.add(nuevoItem("%02d".formatted(i)));
        }
        items.insertarNuevos(todos);

        CountDownLatch ambosTomaron = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<UUID>>> futuros = new java.util.ArrayList<>();
            for (int r = 0; r < 2; r++) {
                futuros.add(pool.submit(() -> new TransactionTemplate(bd.transacciones).execute(estado -> {
                    List<UUID> tomados = candidatos(GrupoDeCola.BUSQUEDA, 25).stream().map(WorkItem::id).toList();
                    ambosTomaron.countDown();
                    try {
                        // Cada relay conserva sus bloqueos hasta que el otro también haya tomado su lote.
                        assertThat(ambosTomaron.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return tomados;
                })));
            }
            List<UUID> primero = futuros.get(0).get(20, TimeUnit.SECONDS);
            List<UUID> segundo = futuros.get(1).get(20, TimeUnit.SECONDS);

            // Ambos relays ven los mismos 25 candidatos (los de mayor prioridad), pero cada fila la bloquea uno solo.
            // El reparto entre los dos relays depende de cómo se intercalen los bloqueos (uno puede quedarse con todo): lo que
            // importa es que ninguna fila la tome dos veces y que entre ambos cubran los 25 candidatos.
            java.util.Set<UUID> repetidos = new java.util.HashSet<>(primero);
            repetidos.retainAll(segundo);
            assertThat(repetidos).as("filas tomadas por los dos relays").isEmpty();
            assertThat(primero.size() + segundo.size()).isEqualTo(25);
        } finally {
            pool.shutdownNow();
        }
    }

    // ─── Watchdog y reconciliación ───────────────────────────────────────────────

    @Test
    void elWatchdogEncuentraLosItemsEnCursoConReclamoViejo() {
        WorkItem viejo = nuevoItem("05");
        WorkItem reciente = nuevoItem("11");
        items.insertarNuevos(List.of(viejo, reciente));
        items.reclamar(viejo.id(), T0, false, Duration.ofMinutes(5));
        items.reclamar(reciente.id(), T0.plus(Duration.ofMinutes(9)), false, Duration.ofMinutes(5));

        List<WorkItem> atascados = new TransactionTemplate(bd.transacciones).execute(
                estado -> items.bloquearAtascados(T0.plus(Duration.ofMinutes(5)), 10));

        assertThat(atascados).extracting(WorkItem::codigo).containsExactly("05");

        items.guardar(atascados.get(0).liberarPorAtasco());
        assertThat(items.buscar(viejo.id()).orElseThrow()).satisfies(g -> {
            assertThat(g.estado()).isEqualTo(EstadoTrabajo.PENDING);
            assertThat(g.pendienteDePublicar()).isTrue();
        });
    }

    @Test
    void laReconciliacionDevuelveAlOutboxSoloLosPendientesPublicadosHaceMucho() {
        WorkItem perdido = nuevoItem("05").marcarPublicado(T0);
        WorkItem reciente = nuevoItem("11").marcarPublicado(T0.plus(Duration.ofMinutes(9)));
        WorkItem detalle = WorkItem.nuevo(job.id(), null, TipoTrabajo.DETALLE, null, "", null, 1, 5L).marcarPublicado(T0);
        items.insertarNuevos(List.of(perdido, reciente, detalle));
        Instant ahora = T0.plus(Duration.ofMinutes(10));

        assertThat(items.marcarParaRepublicar(true, ahora.minus(Duration.ofMinutes(5)), ahora)).isEqualTo(1);

        assertThat(items.buscar(perdido.id()).orElseThrow().pendienteDePublicar()).isTrue();
        assertThat(items.buscar(reciente.id()).orElseThrow().pendienteDePublicar()).isFalse();
        assertThat(items.buscar(detalle.id()).orElseThrow().pendienteDePublicar()).isFalse();
    }

    // ─── Conteos ─────────────────────────────────────────────────────────────────

    @Test
    void losConteosYLaDeteccionDeActivosSalenDeLaBaseDeDatos() {
        WorkItem a = nuevoItem("05");
        WorkItem b = nuevoItem("11");
        items.insertarNuevos(List.of(a, b));
        assertThat(items.hayActivos(job.id())).isTrue();

        WorkItem ra = items.reclamar(a.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        items.confirmar(ra, ra.completar(1, 1));
        WorkItem rb = items.reclamar(b.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        assertThat(items.hayActivos(job.id())).isTrue();
        items.confirmar(rb, rb.excedido("demasiados"));

        assertThat(items.hayActivos(job.id())).isFalse();
        assertThat(items.conteos(job.id())).satisfies(c -> {
            assertThat(c.cantidad(EstadoTrabajo.DONE)).isEqualTo(1);
            assertThat(c.cantidad(EstadoTrabajo.EXCEEDED)).isEqualTo(1);
            assertThat(c.total()).isEqualTo(2);
        });
    }

    // ─── Árbol, cancelación y reencolado (fase 6) ────────────────────────────────

    @Test
    void elArbolAgregaLasPaginasDeCadaBusquedaYOmiteElDetalle() {
        WorkItem raiz = WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", "TODO EL PAÍS", 1, null);
        WorkItem depto = WorkItem.nuevo(job.id(), raiz.id(), TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, "05", "ANTIOQUIA", 1, null);
        WorkItem pagina2 = WorkItem.nuevo(job.id(), depto.id(), TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, "05", "ANTIOQUIA", 2, null);
        WorkItem pagina3 = WorkItem.nuevo(job.id(), depto.id(), TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, "05", "ANTIOQUIA", 3, null);
        WorkItem lista = WorkItem.nuevo(job.id(), depto.id(), TipoTrabajo.LIST, Nivel.MUNICIPIO, "05001", "MEDELLIN", 1, null);
        WorkItem detalle = WorkItem.nuevo(job.id(), depto.id(), TipoTrabajo.DETALLE, null, "", null, 1, 7L);
        items.insertarNuevos(List.of(raiz, depto, pagina2, pagina3, lista, detalle));
        WorkItem enCurso = items.reclamar(pagina2.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        items.confirmar(enCurso, enCurso.completar(20, 3));

        List<io.ramaqueue.application.port.out.WorkItemRepository.NodoDeArbol> arbol = items.arbol(job.id(), 100);

        assertThat(arbol).extracting(n -> n.codigo() + "/" + n.tipo()).containsExactlyInAnyOrder("/SEARCH", "05/SEARCH", "05001/LIST");
        assertThat(arbol).filteredOn(n -> n.codigo().equals("05") && n.tipo() == TipoTrabajo.SEARCH).singleElement()
                .satisfies(n -> {
                    assertThat(n.paginasTerminadas()).isEqualTo(1); // la página 2 terminó y la 3 no
                    assertThat(n.parentId()).isEqualTo(raiz.id());
                    assertThat(n.etiqueta()).isEqualTo("ANTIOQUIA");
                });
        assertThat(items.arbol(job.id(), 2)).hasSize(2);
    }

    @Test
    void cancelarPendientesSoloTocaLosPendientesDelJob() {
        WorkItem a = nuevoItem("05");
        WorkItem b = nuevoItem("11");
        WorkItem hecho = nuevoItem("13");
        items.insertarNuevos(List.of(a, b, hecho));
        WorkItem enCurso = items.reclamar(hecho.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
        items.confirmar(enCurso, enCurso.completar(1, 1));
        SearchJob otro = SearchJob.nuevo(UUID.randomUUID(), "OTRO NOMBRE", TipoPersona.NATURAL, Categoria.CIVIL, T0);
        jobs.guardar(otro);
        WorkItem ajeno = WorkItem.nuevo(otro.id(), null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", null, 1, null);
        items.insertarNuevos(List.of(ajeno));

        assertThat(items.cancelarPendientes(job.id())).isEqualTo(2);

        assertThat(items.buscar(a.id()).orElseThrow().estado()).isEqualTo(EstadoTrabajo.CANCELLED);
        assertThat(items.buscar(hecho.id()).orElseThrow().estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(items.buscar(ajeno.id()).orElseThrow().estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(items.hayActivos(job.id())).isFalse();
    }

    @Test
    void losFallidosSeListanPorJobOGlobalmenteYSeReencolanDeCero() {
        WorkItem a = nuevoItem("05");
        WorkItem b = nuevoItem("11");
        SearchJob otro = SearchJob.nuevo(UUID.randomUUID(), "OTRO NOMBRE", TipoPersona.NATURAL, Categoria.CIVIL, T0);
        jobs.guardar(otro);
        WorkItem ajeno = WorkItem.nuevo(otro.id(), null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", null, 1, null);
        items.insertarNuevos(List.of(a, b, ajeno));
        for (WorkItem w : List.of(a, ajeno)) {
            WorkItem reclamado = items.reclamar(w.id(), T0, false, Duration.ofMinutes(5)).orElseThrow();
            items.confirmar(reclamado, reclamado.fallar("HTTP 404"));
        }

        assertThat(items.fallidos(job.id())).extracting(WorkItem::id).containsExactly(a.id());
        assertThat(items.fallidos(null)).extracting(WorkItem::id).containsExactlyInAnyOrder(a.id(), ajeno.id());

        items.guardar(items.fallidos(job.id()).get(0).reencolar());

        assertThat(items.buscar(a.id()).orElseThrow()).satisfies(g -> {
            assertThat(g.estado()).isEqualTo(EstadoTrabajo.PENDING);
            assertThat(g.intentos()).isZero();
            assertThat(g.pendienteDePublicar()).isTrue();
        });
    }

    @Test
    void elJobSeCancelaSoloSiSeguiaCorriendoYSeReabreSoloSiTerminoConProblemas() {
        assertThat(jobs.reabrirSiTerminadoConProblemas(job.id())).as("un job RUNNING no se reabre").isFalse();
        assertThat(jobs.finalizarSiCorriendo(job.id(), io.ramaqueue.domain.model.JobStatus.PARTIAL, T0, null)).isTrue();

        assertThat(jobs.cancelarSiCorriendo(job.id(), T0)).as("ya terminó").isFalse();
        assertThat(jobs.reabrirSiTerminadoConProblemas(job.id())).isTrue();
        assertThat(jobs.buscar(job.id()).orElseThrow()).satisfies(j -> {
            assertThat(j.estado()).isEqualTo(io.ramaqueue.domain.model.JobStatus.RUNNING);
            assertThat(j.finalizadoEn()).isNull();
        });

        assertThat(jobs.cancelarSiCorriendo(job.id(), T0)).isTrue();
        assertThat(jobs.buscar(job.id()).orElseThrow().estado()).isEqualTo(io.ramaqueue.domain.model.JobStatus.CANCELLED);
        assertThat(jobs.reabrirSiTerminadoConProblemas(job.id())).as("un job cancelado no se reabre").isFalse();
    }

    @Test
    void elReclamoSobreviveAlViajeDeIdaYVueltaConMicrosegundos() {
        // Postgres guarda microsegundos: el reclamo debe sobrevivir al viaje de ida y vuelta sin perder precisión.
        Instant conMicros = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WorkItem item = nuevoItem("05");
        items.insertarNuevos(List.of(item));

        WorkItem reclamado = items.reclamar(item.id(), conMicros, false, Duration.ofMinutes(5)).orElseThrow();

        assertThat(items.confirmar(reclamado, reclamado.completar(null, null))).isTrue();
        assertThat(items.buscar(item.id()).orElseThrow().estado()).isEqualTo(EstadoTrabajo.DONE);
    }
}
