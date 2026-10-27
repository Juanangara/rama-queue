package io.ramaqueue.infrastructure.out.persistence;

import io.ramaqueue.application.port.out.WorkPublisher;
import io.ramaqueue.application.service.PublicadorDeOutbox;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.WorkItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** El relay completo contra Postgres real: reparto justo entre trabajos y tope de mensajes en el broker. */
@Testcontainers(disabledWithoutDocker = true)
class PublicadorDeOutboxIntegracionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    private static PostgresTestSupport bd;
    private JdbcWorkItemRepository items;
    private JdbcSearchJobRepository jobs;
    private final List<WorkItem> publicados = new ArrayList<>();
    private final WorkPublisher publicadorFalso = new WorkPublisher() {
        @Override
        public void publicar(Collection<WorkItem> lote) {
            publicados.addAll(lote);
        }

        @Override
        public void publicarReintento(WorkItem item, int nivelDeReintento) {
            throw new UnsupportedOperationException("el relay no usa reintentos");
        }

        @Override
        public void enviarADlq(WorkItem item, String motivo) {
            throw new UnsupportedOperationException("el relay no usa la DLQ");
        }
    };

    private SearchJob grande;
    private SearchJob pequeno;

    @BeforeAll
    static void migrar() {
        bd = new PostgresTestSupport(POSTGRES);
    }

    @BeforeEach
    void preparar() {
        bd.limpiar();
        publicados.clear();
        items = new JdbcWorkItemRepository(bd.jdbc, bd.transacciones);
        jobs = new JdbcSearchJobRepository(bd.jdbc);
        grande = SearchJob.nuevo(UUID.randomUUID(), "EMPRESA GRANDE", TipoPersona.JURIDICA, Categoria.LABORAL, T0);
        pequeno = SearchJob.nuevo(UUID.randomUUID(), "JUAN PEQUENO", TipoPersona.NATURAL, Categoria.PENAL, T0.plusSeconds(300));
        jobs.guardar(grande);
        jobs.guardar(pequeno);
    }

    private PublicadorDeOutbox relay(int maxEnBroker, double cuota) {
        return new PublicadorDeOutbox(Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC), items, publicadorFalso,
                new SpringUnidadDeTrabajo(bd.transacciones), 100, maxEnBroker, cuota);
    }

    private WorkItem busqueda(SearchJob job, String codigo) {
        return WorkItem.nuevo(job.id(), null, TipoTrabajo.SEARCH, Nivel.DEPARTAMENTO, codigo, "E" + codigo, 1, null);
    }

    /** El trabajo grande ya recibió servicio: {@code n} ítems terminados. */
    private void darServicio(SearchJob job, int n) {
        List<WorkItem> hechos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            hechos.add(busqueda(job, "H%03d".formatted(i)).marcarPublicado(T0).iniciar(T0).completar(5, 1));
        }
        items.insertarNuevos(hechos);
    }

    private void pendientes(SearchJob job, int n, String prefijo) {
        List<WorkItem> lista = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lista.add(busqueda(job, "%s%03d".formatted(prefijo, i)));
        }
        items.insertarNuevos(lista);
    }

    private long de(SearchJob job) {
        return publicados.stream().filter(i -> i.jobId().equals(job.id())).count();
    }

    @Test
    void elTrabajoPequenoNoEsperaDetrasDelGrandeAunqueLlegueDespues() {
        darServicio(grande, 80);
        pendientes(grande, 200, "G");   // 200 ítems del grande, creados antes
        pendientes(pequeno, 10, "P");   // 10 del pequeño, creados después

        int publicadosAhora = relay(20, 0.25).publicarLote();

        assertThat(publicadosAhora).isEqualTo(20);
        assertThat(de(pequeno)).isEqualTo(10);  // todos los del pequeño salen ya
        assertThat(de(grande)).isEqualTo(10);   // y el grande sigue avanzando (no se pierde ni se aplaza para siempre)
        // Con FIFO puro habrían salido 20 del grande y 0 del pequeño.
    }

    @Test
    void noSePublicaMasDelTopeDelBrokerYSeReanudaCuandoLosConsumidoresLoVacian() {
        pendientes(grande, 100, "G");
        PublicadorDeOutbox relay = relay(15, 0.25);

        assertThat(relay.publicarLote()).isEqualTo(15);
        assertThat(relay.publicarLote()).isZero(); // el broker está "lleno": 15 publicados y pendientes
        assertThat(items.publicadosEnElBroker(io.ramaqueue.domain.model.GrupoDeCola.BUSQUEDA)).isEqualTo(15);

        // Los consumidores toman 5: salen de PENDING, y quedan 5 cupos.
        publicados.stream().limit(5).forEach(i -> items.guardar(i.marcarPublicado(T0).iniciar(T0)));
        assertThat(relay.publicarLote()).isEqualTo(5);
        assertThat(publicados).hasSize(20).doesNotHaveDuplicates();
    }

    /**
     * Regresión de la demostración en vivo: en régimen normal el broker está lleno y cada ciclo del relay publica UN mensaje
     * (se libera un hueco cada vez que un consumidor toma uno). Con la cuota garantizada calculada con {@code ceil}, ese único
     * mensaje era siempre del trabajo más antiguo y el pequeño no recibía ningún turno.
     */
    @Test
    void conCiclosDeUnSoloMensajeElTrabajoPequenoNoSeQuedaSinTurnos() {
        darServicio(grande, 80);
        pendientes(grande, 100, "G");
        pendientes(pequeno, 30, "P");
        PublicadorDeOutbox relay = relay(1, 0.25); // el broker admite 1 mensaje: cada ciclo publica exactamente 1

        for (int ciclo = 0; ciclo < 40; ciclo++) {
            assertThat(relay.publicarLote()).as("ciclo %d", ciclo).isEqualTo(1);
            WorkItem ultimo = publicados.get(publicados.size() - 1);
            items.guardar(ultimo.marcarPublicado(T0).iniciar(T0).completar(1, 1)); // el consumidor lo toma y lo termina
        }

        assertThat(de(pequeno)).as("el pequeño recibe ~75 % de los turnos: sus 30 ítems salen en 40 ciclos").isEqualTo(30);
        assertThat(de(grande)).as("el grande no se queda sin avanzar (cuota garantizada ~25 %)").isBetween(9L, 11L);
    }

    @Test
    void conTrabajosParejosReparteAPartesIguales() {
        pendientes(grande, 50, "G");
        pendientes(pequeno, 50, "P");

        relay(20, 0.0).publicarLote();

        assertThat(de(grande)).isEqualTo(10);
        assertThat(de(pequeno)).isEqualTo(10);
    }

    @Test
    void sinNadaPendienteNoPublicaNada() {
        assertThat(relay(20, 0.25).publicarLote()).isZero();
        assertThat(publicados).isEmpty();
    }
}
