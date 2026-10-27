package io.ramaqueue.infrastructure.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.out.ProcesoHitRepository.FiltroDeResultados;
import io.ramaqueue.application.port.out.ProcesoHitRepository.PaginaDeResultados;
import io.ramaqueue.application.port.out.RamaJudicialGateway.DetalleProceso;
import io.ramaqueue.application.port.out.RamaJudicialGateway.UltimaActuacion;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.SujetoProceso;
import io.ramaqueue.domain.model.TipoPersona;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcProcesoHitRepositoryIntegracionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static PostgresTestSupport bd;
    private JdbcProcesoHitRepository hits;
    private SearchJob job;

    @BeforeAll
    static void migrar() {
        bd = new PostgresTestSupport(POSTGRES);
    }

    @BeforeEach
    void preparar() {
        bd.limpiar();
        hits = new JdbcProcesoHitRepository(bd.jdbc, new ObjectMapper());
        job = SearchJob.nuevo(UUID.randomUUID(), "JUAN GARCIA", TipoPersona.NATURAL, Categoria.PENAL,
                Instant.parse("2026-10-26T12:00:00Z"));
        new JdbcSearchJobRepository(bd.jdbc).guardar(job);
    }

    private static ProcesoEncontrado proceso(long id, String despacho, String departamento, String sujetos) {
        return new ProcesoEncontrado(id, "%023d".formatted(id), despacho, departamento, sujetos,
                "2020-01-01T00:00:00", "2021-01-01T00:00:00");
    }

    private void aceptadoCompleto(long id, String despacho, String departamento, String ciudad) {
        hits.guardar(job.id(), proceso(id, despacho, departamento, "Demandante: JUAN GARCIA | Demandado: X "), ciudad, "penal", true);
        hits.guardarDetalle(job.id(), id, new DetalleProceso("Penal", "Ley 906", "Sin subclase", "JUEZ"));
        hits.guardarUltimaActuacion(job.id(), id, new UltimaActuacion("2021-01-01T00:00:00", "Auto", "nota", "2021-01-02T00:00:00"));
        hits.agregarSujetos(job.id(), id, List.of(new SujetoProceso("Demandante", "JUAN GARCIA")));
    }

    // ─── Candidatos y aceptación ─────────────────────────────────────────────────

    @Test
    void guardarEsIdempotenteYSeRecuperaElProceso() {
        ProcesoEncontrado p = proceso(1L, "JUZGADO 001 PENAL DE CALI", "VALLE", "Demandante: JUAN GARCIA | Demandado: X ");

        hits.guardar(job.id(), p, "cali", "penal", true);
        hits.guardar(job.id(), p, "otra", "civil", false);

        assertThat(hits.buscar(job.id(), 1L)).contains(p);
        assertThat(bd.jdbc.queryForObject("SELECT count(*) FROM proceso_hit", Long.class)).isEqualTo(1);
        assertThat(bd.jdbc.queryForObject("SELECT ciudad FROM proceso_hit", String.class)).isEqualTo("cali");
    }

    @Test
    void unCandidatoSeAceptaOSeRechazaUnaSolaVez() {
        hits.guardar(job.id(), proceso(1L, "D", "V", "Demandante: X | ..."), null, "penal", false);
        hits.guardar(job.id(), proceso(2L, "D", "V", "Demandante: X | ..."), null, "penal", false);

        hits.aceptar(job.id(), 1L);
        hits.rechazar(job.id(), 2L);
        hits.rechazar(job.id(), 1L); // ya aceptado: no cambia

        assertThat(estado(1L)).isEqualTo("PENDIENTE");
        assertThat(estado(2L)).isEqualTo("RECHAZADO");
        assertThat(hits.contar(job.id()).aceptados()).isEqualTo(1);
    }

    // ─── Sujetos (defecto hallado en la revisión de la fase 5) ───────────────────

    @Test
    void losSujetosRecibidosDosVecesNoSeDuplicanYConservanElOrden() {
        hits.guardar(job.id(), proceso(1L, "D", "V", "Demandante: A | ..."), null, "penal", false);
        List<SujetoProceso> paginaUno = List.of(new SujetoProceso("Demandante", "ANA"), new SujetoProceso("Demandado", "BEATRIZ"));
        List<SujetoProceso> paginaDos = List.of(new SujetoProceso("Tercero", "CARLOS"));

        // Primero llegan al resolver los sujetos truncados…
        hits.agregarSujetos(job.id(), 1L, paginaUno);
        hits.agregarSujetos(job.id(), 1L, paginaDos);
        // …y luego otra vez en el detalle del proceso ya aceptado.
        hits.agregarSujetos(job.id(), 1L, paginaUno);
        hits.agregarSujetos(job.id(), 1L, paginaDos);

        assertThat(hits.textoDeSujetos(job.id(), 1L)).isEqualTo("Demandante: ANA | Demandado: BEATRIZ | Tercero: CARLOS");
        assertThat(bd.jdbc.queryForObject("SELECT jsonb_array_length(sujetos_completos) FROM proceso_hit", Integer.class)).isEqualTo(3);
    }

    @Test
    void unProcesoSinSujetosTieneTextoVacio() {
        hits.guardar(job.id(), proceso(1L, "D", "V", "x"), null, "penal", true);

        assertThat(hits.textoDeSujetos(job.id(), 1L)).isEmpty();
    }

    @Test
    void losNombresConEspaciosRarosSeNormalizan() {
        hits.guardar(job.id(), proceso(1L, "D", "V", "x"), null, "penal", true);

        hits.agregarSujetos(job.id(), 1L, List.of(new SujetoProceso(" Demandante ", "GUIDO ALFONSO\r\n\t\t\tCABALLERO")));

        assertThat(hits.textoDeSujetos(job.id(), 1L)).isEqualTo("Demandante: GUIDO ALFONSO CABALLERO");
    }

    // ─── Enriquecimiento ─────────────────────────────────────────────────────────

    @Test
    void elProcesoPasaACompletoSoloCuandoTieneLasTresPartes() {
        hits.guardar(job.id(), proceso(1L, "D", "V", "x"), null, "penal", true);

        hits.guardarDetalle(job.id(), 1L, new DetalleProceso("Penal", "Ley 906", "Sin subclase", "JUEZ"));
        assertThat(estado(1L)).isEqualTo("PENDIENTE");
        hits.guardarUltimaActuacion(job.id(), 1L, null); // un proceso sin actuaciones también cuenta como resuelto
        assertThat(estado(1L)).isEqualTo("PENDIENTE");
        hits.agregarSujetos(job.id(), 1L, List.of(new SujetoProceso("Demandante", "ANA")));

        assertThat(estado(1L)).isEqualTo("COMPLETO");
        assertThat(hits.contar(job.id())).isEqualTo(new io.ramaqueue.application.port.out.ProcesoHitRepository.Conteo(1, 1));
    }

    // ─── Consulta de resultados ──────────────────────────────────────────────────

    @Test
    void losResultadosSoloIncluyenProcesosAceptados() {
        aceptadoCompleto(1L, "JUZGADO 001 PENAL DE CALI", "VALLE", "cali");
        hits.guardar(job.id(), proceso(2L, "D", "V", "x"), null, "penal", false); // candidato
        hits.guardar(job.id(), proceso(3L, "D", "V", "x"), null, "penal", false);
        hits.rechazar(job.id(), 3L);

        PaginaDeResultados pagina = hits.listar(job.id(), FiltroDeResultados.sinFiltros(), 1, 20);

        assertThat(pagina.total()).isEqualTo(1);
        assertThat(pagina.elementos()).hasSize(1).first().satisfies(r -> {
            assertThat(r.idProceso()).isEqualTo(1L);
            assertThat(r.estadoDetalle()).isEqualTo("COMPLETO");
            assertThat(r.detalleJson()).contains("Ley 906");
            assertThat(r.ultimaActuacionJson()).contains("Auto");
            assertThat(r.sujetosCompletosJson()).contains("JUAN GARCIA");
        });
    }

    @Test
    void losResultadosSeFiltranPorCiudadDepartamentoYTexto() {
        aceptadoCompleto(1L, "JUZGADO 001 PENAL DE CALI", "VALLE", "cali");
        aceptadoCompleto(2L, "JUZGADO 002 PENAL DE MEDELLIN", "ANTIOQUIA", "medellin");
        hits.guardar(job.id(), proceso(3L, "JUZGADO 003 PENAL DE MEDELLIN", "ANTIOQUIA", "Demandante: JUAN GARCIA | x "), "medellin", "penal", true);

        assertThat(ids(new FiltroDeResultados("CALI", null, null, false))).containsExactly(1L);
        assertThat(ids(new FiltroDeResultados(null, "antioquia", null, false))).containsExactlyInAnyOrder(2L, 3L);
        assertThat(ids(new FiltroDeResultados(null, "antioquia", null, true))).containsExactly(2L);
        assertThat(ids(new FiltroDeResultados(null, null, "medellin", false))).containsExactlyInAnyOrder(2L, 3L);
        assertThat(ids(new FiltroDeResultados(null, null, "0000000000000000000002", false))).containsExactly(2L);
        assertThat(ids(new FiltroDeResultados("bogota", null, null, false))).isEmpty();
    }

    @Test
    void elTextoDeBusquedaNoInterpretaComodinesDeSql() {
        aceptadoCompleto(1L, "JUZGADO 001 PENAL DE CALI", "VALLE", "cali");

        assertThat(ids(new FiltroDeResultados(null, null, "%", false))).isEmpty();
        assertThat(ids(new FiltroDeResultados(null, null, "_", false))).isEmpty();
        assertThat(ids(new FiltroDeResultados(null, null, "' OR 1=1 --", false))).isEmpty();
    }

    @Test
    void losResultadosSePaginan() {
        for (long id = 1; id <= 7; id++) {
            aceptadoCompleto(id, "JUZGADO PENAL", "V", "cali");
        }

        PaginaDeResultados primera = hits.listar(job.id(), FiltroDeResultados.sinFiltros(), 1, 3);
        PaginaDeResultados ultima = hits.listar(job.id(), FiltroDeResultados.sinFiltros(), 3, 3);

        assertThat(primera.total()).isEqualTo(7);
        assertThat(primera.elementos()).hasSize(3);
        assertThat(ultima.elementos()).hasSize(1);
        assertThat(primera.elementos()).extracting(r -> r.idProceso()).doesNotContainAnyElementsOf(
                ultima.elementos().stream().map(r -> r.idProceso()).toList());
    }

    private List<Long> ids(FiltroDeResultados filtro) {
        return hits.listar(job.id(), filtro, 1, 50).elementos().stream().map(r -> r.idProceso()).toList();
    }

    private String estado(long id) {
        return bd.jdbc.queryForObject("SELECT detail_status FROM proceso_hit WHERE job_id = ? AND id_proceso = ?",
                String.class, job.id(), id);
    }
}
