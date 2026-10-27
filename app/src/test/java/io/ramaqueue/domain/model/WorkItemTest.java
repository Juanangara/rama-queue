package io.ramaqueue.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    private static WorkItem nuevo() {
        return WorkItem.nuevo(JOB, null, TipoTrabajo.SEARCH, Nivel.MUNICIPIO, "05001", "MEDELLIN", 1, null);
    }

    @Test
    void unItemNuevoEstaPendienteYSinPublicar() {
        WorkItem item = nuevo();

        assertThat(item.estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(item.pendienteDePublicar()).isTrue();
        assertThat(item.intentos()).isZero();
        assertThat(item.prioridad()).isEqualTo(Nivel.MUNICIPIO.profundidad());
    }

    @Test
    void elCicloFelizTerminaEnDone() {
        WorkItem item = nuevo().marcarPublicado(T0).iniciar(T0).completar(40, 2);

        assertThat(item.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(item.esTerminal()).isTrue();
        assertThat(item.totalRegistros()).isEqualTo(40);
        assertThat(item.totalPaginas()).isEqualTo(2);
    }

    @Test
    void reintentarConsumeUnIntentoPeroUnBloqueoNo() {
        WorkItem enCurso = nuevo().iniciar(T0);

        assertThat(enCurso.reintentar("timeout").intentos()).isEqualTo(1);
        assertThat(enCurso.reintentar("timeout").estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(enCurso.devolverPorBloqueo().intentos()).isZero();
        assertThat(enCurso.devolverPorBloqueo().estado()).isEqualTo(EstadoTrabajo.PENDING);
    }

    @Test
    void elWatchdogLiberaUnItemAtascadoYVuelveAPublicarse() {
        WorkItem publicado = nuevo().marcarPublicado(T0);
        WorkItem atascado = publicado.iniciar(T0);

        assertThat(atascado.estaAtascado(T0.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5))).isTrue();
        assertThat(atascado.estaAtascado(T0.plus(Duration.ofMinutes(4)), Duration.ofMinutes(5))).isFalse();
        assertThat(publicado.estaAtascado(T0.plus(Duration.ofHours(1)), Duration.ofMinutes(5))).isFalse();

        WorkItem liberado = atascado.liberarPorAtasco();
        assertThat(liberado.estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(liberado.pendienteDePublicar()).isTrue();
    }

    @Test
    void unaReentregaDeUnItemEnCursoSePermite() {
        WorkItem item = nuevo().iniciar(T0);

        assertThat(item.iniciar(T0.plusSeconds(5)).estado()).isEqualTo(EstadoTrabajo.IN_PROGRESS);
    }

    @Test
    void unItemTerminalNoSePuedeVolverAIniciar() {
        for (WorkItem terminal : new WorkItem[]{
                nuevo().iniciar(T0).completar(null, null),
                nuevo().iniciar(T0).expandir(null, null),
                nuevo().iniciar(T0).excedido("x"),
                nuevo().iniciar(T0).fallar("x"),
                nuevo().cancelar()}) {
            assertThat(terminal.esTerminal()).isTrue();
            assertThatThrownBy(() -> terminal.iniciar(T0)).isInstanceOf(TransicionInvalidaException.class);
        }
    }

    @Test
    void soloUnItemFailedSePuedeReencolarYEmpiezaDeCero() {
        WorkItem fallido = nuevo().iniciar(T0).reintentar("a").iniciar(T0).reintentar("b").iniciar(T0).fallar("agotado");

        WorkItem reencolado = fallido.reencolar();
        assertThat(reencolado.estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(reencolado.intentos()).isZero();
        assertThat(reencolado.pendienteDePublicar()).isTrue();
        assertThat(reencolado.ultimoError()).isNull();

        assertThatThrownBy(() -> nuevo().iniciar(T0).completar(null, null).reencolar())
                .isInstanceOf(TransicionInvalidaException.class);
    }

    @Test
    void noSePuedeCompletarUnItemQueNoEstabaEnCurso() {
        assertThatThrownBy(() -> nuevo().completar(null, null)).isInstanceOf(TransicionInvalidaException.class);
        assertThatThrownBy(() -> nuevo().reintentar("x")).isInstanceOf(TransicionInvalidaException.class);
    }

    @Test
    void laMatrizDeTransicionesDeEstadosEsLaDiseniada() {
        for (EstadoTrabajo e : EstadoTrabajo.values()) {
            assertThat(e.esTerminal()).isEqualTo(e != EstadoTrabajo.PENDING && e != EstadoTrabajo.IN_PROGRESS);
        }
        assertThat(EstadoTrabajo.IN_PROGRESS.puedePasarA(EstadoTrabajo.PENDING)).isTrue();
        assertThat(EstadoTrabajo.PENDING.puedePasarA(EstadoTrabajo.DONE)).isFalse();
        assertThat(EstadoTrabajo.DONE.puedePasarA(EstadoTrabajo.PENDING)).isFalse();
        assertThat(EstadoTrabajo.EXCEEDED.puedePasarA(EstadoTrabajo.PENDING)).isFalse();
        assertThat(EstadoTrabajo.FAILED.puedePasarA(EstadoTrabajo.PENDING)).isTrue();
        assertThat(EstadoTrabajo.FAILED.puedePasarA(EstadoTrabajo.DONE)).isFalse();
    }

    @Test
    void elIdEsDeterministaYDependeDeTodosSusComponentes() {
        UUID base = WorkItemId.de(JOB, TipoTrabajo.SEARCH, "05001", 1, null);

        assertThat(WorkItemId.de(JOB, TipoTrabajo.SEARCH, "05001", 1, null)).isEqualTo(base);
        assertThat(WorkItemId.de(JOB, TipoTrabajo.SEARCH, " 05001  ", 1, null)).isEqualTo(base);
        assertThat(WorkItemId.de(JOB, TipoTrabajo.SEARCH, "05001", 2, null)).isNotEqualTo(base);
        assertThat(WorkItemId.de(JOB, TipoTrabajo.LIST, "05001", 1, null)).isNotEqualTo(base);
        assertThat(WorkItemId.de(JOB, TipoTrabajo.DETALLE, "", 1, 7L))
                .isNotEqualTo(WorkItemId.de(JOB, TipoTrabajo.DETALLE, "", 1, 8L));
        assertThat(WorkItemId.de(UUID.randomUUID(), TipoTrabajo.SEARCH, "05001", 1, null)).isNotEqualTo(base);
    }

    @Test
    void laPaginaDebeSerPositiva() {
        assertThatThrownBy(() -> WorkItem.nuevo(JOB, null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", null, 0, null))
                .isInstanceOf(EntradaInvalidaException.class);
    }
}
