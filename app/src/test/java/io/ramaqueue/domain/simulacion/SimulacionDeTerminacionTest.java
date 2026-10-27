package io.ramaqueue.domain.simulacion;

import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.JobStatus;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.WorkItem;
import io.ramaqueue.domain.model.WorkItemId;
import io.ramaqueue.domain.policy.JobStatusResolver;
import io.ramaqueue.domain.simulacion.SimuladorDeJob.Config;
import io.ramaqueue.domain.simulacion.SimuladorDeJob.Resultado;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La propiedad central del proyecto: para cualquier árbol y con cualquier mezcla de fallos, el job termina,
 * ningún ítem queda a medias y, si nada falló definitivamente, se obtiene exactamente lo esperado.
 */
class SimulacionDeTerminacionTest {

    private static final int SEMILLAS = 250;
    private static final int LIMITE = 60;

    private static JobStatus estadoDelJob(Resultado r) {
        return JobStatusResolver.resolver(r.conteos(), r.items().get(r.raiz().id()).estado(), false);
    }

    @Test
    void sinFallosCadaItemHaceUnaSolaPeticionYSeObtieneExactamenteLoEsperado() {
        int conExcedidos = 0;
        int conDrillDown = 0;
        for (long semilla = 1; semilla <= SEMILLAS; semilla++) {
            MundoSintetico mundo = new MundoSintetico(semilla, LIMITE);
            MundoSintetico.Esperado esperado = mundo.esperado();

            Resultado r = new SimuladorDeJob(mundo, Config.sinFallos(semilla)).ejecutar();

            String contexto = "semilla " + semilla;
            assertThat(r.todosTerminales()).as(contexto).isTrue();
            assertThat(r.aceptados()).as(contexto).isEqualTo(esperado.aceptados());
            assertThat(r.cantidad(EstadoTrabajo.EXCEEDED)).as(contexto).isEqualTo(esperado.excedidos());
            assertThat(r.cantidad(EstadoTrabajo.FAILED)).as(contexto).isZero();
            assertThat(r.peticiones()).as("1 ítem = 1 petición, " + contexto).isEqualTo(r.items().size());
            assertThat(r.entregasDescartadas()).as(contexto).isZero();
            assertThat(estadoDelJob(r)).as(contexto)
                    .isEqualTo(esperado.excedidos() > 0 ? JobStatus.PARTIAL : JobStatus.COMPLETED);

            for (long idProceso : esperado.aceptados()) {
                for (TipoTrabajo tipo : new TipoTrabajo[]{TipoTrabajo.DETALLE, TipoTrabajo.ACTUACIONES, TipoTrabajo.SUJETOS}) {
                    WorkItem detalle = r.items().get(WorkItemId.de(r.job().id(), tipo, "", 1, idProceso));
                    assertThat(detalle).as("%s de %d, %s", tipo, idProceso, contexto).isNotNull();
                    assertThat(detalle.estado()).isEqualTo(EstadoTrabajo.DONE);
                }
            }
            conExcedidos += esperado.excedidos() > 0 ? 1 : 0;
            conDrillDown += r.cantidad(EstadoTrabajo.EXPANDED) > 0 ? 1 : 0;
        }
        // El generador debe ejercitar de verdad los caminos: si no, el test pasaría sin demostrar nada.
        assertThat(conDrillDown).as("mundos con drill-down").isGreaterThan(SEMILLAS / 2);
        assertThat(conExcedidos).as("mundos con ramas sin respuesta").isGreaterThan(SEMILLAS / 4);
    }

    @Test
    void conBloqueosFallosEntregasDuplicadasYWorkersCaidosElJobSiempreTermina() {
        int liberados = 0;
        int descartadas = 0;
        int fallidos = 0;
        for (long semilla = 1; semilla <= SEMILLAS; semilla++) {
            MundoSintetico mundo = new MundoSintetico(semilla, LIMITE);
            MundoSintetico.Esperado esperado = mundo.esperado();

            Resultado r = new SimuladorDeJob(mundo, new Config(0.15, 0.05, 0.15, 0.05, semilla)).ejecutar();

            String contexto = "semilla " + semilla;
            assertThat(r.todosTerminales()).as(contexto).isTrue();
            assertThat(estadoDelJob(r)).as(contexto).isNotEqualTo(JobStatus.RUNNING);
            assertThat(esperado.aceptados()).as("nada fuera de lo esperado, " + contexto).containsAll(r.aceptados());
            if (r.cantidad(EstadoTrabajo.FAILED) == 0) {
                assertThat(r.aceptados()).as(contexto).isEqualTo(esperado.aceptados());
                assertThat(r.cantidad(EstadoTrabajo.EXCEEDED)).as(contexto).isEqualTo(esperado.excedidos());
            } else {
                fallidos++;
            }
            liberados += r.liberadosPorWatchdog();
            descartadas += r.entregasDescartadas();
        }
        assertThat(liberados).as("el watchdog tuvo que actuar").isPositive();
        assertThat(descartadas).as("hubo entregas duplicadas que el handler ignoró").isPositive();
        assertThat(fallidos).as("casi ningún ítem agota 5 intentos con 5 % de fallos").isLessThan(SEMILLAS / 10);
    }

    @Test
    void siTodasLasPeticionesFallanLaRaizSeAgotaYElJobTerminaFallido() {
        MundoSintetico mundo = new MundoSintetico(7, LIMITE);

        Resultado r = new SimuladorDeJob(mundo, new Config(0, 1.0, 0, 0, 7)).ejecutar();

        assertThat(r.items()).hasSize(1);
        WorkItem raiz = r.items().get(r.raiz().id());
        assertThat(raiz.estado()).isEqualTo(EstadoTrabajo.FAILED);
        assertThat(raiz.intentos()).isEqualTo(4); // el 5.º fallo agota el ítem sin llegar a reintentar
        assertThat(r.peticiones()).isZero();
        assertThat(estadoDelJob(r)).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void unBloqueoNoConsumeIntentosAsiQueNingunItemSeAgotaPorUnaRachaDe403() {
        for (long semilla = 1; semilla <= 50; semilla++) {
            MundoSintetico mundo = new MundoSintetico(semilla, LIMITE);

            Resultado r = new SimuladorDeJob(mundo, new Config(0.6, 0, 0, 0, semilla)).ejecutar();

            assertThat(r.cantidad(EstadoTrabajo.FAILED)).as("semilla " + semilla).isZero();
            assertThat(r.aceptados()).as("semilla " + semilla).isEqualTo(mundo.esperado().aceptados());
            assertThat(r.items().values()).allSatisfy(i -> assertThat(i.intentos()).isZero());
        }
    }

    @Test
    void sinNingunExcesoNoHayDrillDownAunqueHayaMuchasPaginas() {
        MundoSintetico mundo = new MundoSintetico(3, 1_000_000, LIMITE);

        Resultado r = new SimuladorDeJob(mundo, Config.sinFallos(3)).ejecutar();

        assertThat(r.cantidad(EstadoTrabajo.EXPANDED)).isZero();
        assertThat(r.cantidad(EstadoTrabajo.EXCEEDED)).isZero();
        assertThat(r.items().values().stream().filter(i -> i.tipo() == TipoTrabajo.LIST)).isEmpty();
        assertThat(r.aceptados()).isEqualTo(mundo.esperado().aceptados());
        assertThat(estadoDelJob(r)).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void conUnLimiteDeUnoCasiTodoExcedeYAunAsiElJobTermina() {
        for (long semilla = 1; semilla <= 50; semilla++) {
            MundoSintetico mundo = new MundoSintetico(semilla, 1, LIMITE);

            Resultado r = new SimuladorDeJob(mundo, Config.sinFallos(semilla)).ejecutar();

            assertThat(r.todosTerminales()).as("semilla " + semilla).isTrue();
            assertThat(r.aceptados()).as("semilla " + semilla).isEqualTo(mundo.esperado().aceptados());
            assertThat(Set.of(JobStatus.COMPLETED, JobStatus.PARTIAL)).contains(estadoDelJob(r));
        }
    }
}
