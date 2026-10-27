package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.Territorio;
import io.ramaqueue.domain.model.Territorio.Departamento;
import io.ramaqueue.domain.model.Territorio.Municipio;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.Veredicto;
import io.ramaqueue.domain.model.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpansionPlannerTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    private final Territorio territorio = new Territorio(List.of(
            new Departamento("ANTIOQUIA", "05", List.of(new Municipio("MEDELLIN", "05001"), new Municipio("ABEJORRAL", "05002"))),
            new Departamento("BOGOTA", "11", List.of(new Municipio("BOGOTA, D.C.", "11001"))),
            new Departamento("SIN MUNICIPIOS", "99", List.of())));
    private final ExpansionPlanner planner = new ExpansionPlanner(territorio);

    private static WorkItem busqueda(Nivel nivel, String codigo, int pagina) {
        return WorkItem.nuevo(JOB, null, TipoTrabajo.SEARCH, nivel, codigo, "etiqueta", pagina, null).iniciar(T0);
    }

    private static final RespuestaBusqueda EXCEDE = new RespuestaBusqueda.ExcedeMil();

    // ─── Búsqueda con resultados ─────────────────────────────────────────────────

    @Test
    void unaBusquedaConVariasPaginasCreaLasPaginasRestantesDesdeLaPrimera() {
        WorkItem raiz = busqueda(Nivel.DEPARTAMENTO, "05", 1);

        Plan plan = planner.planificarBusqueda(raiz, new RespuestaBusqueda.Exitosa(45, 3));

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(plan.hijos()).extracting(WorkItem::pagina).containsExactly(2, 3);
        assertThat(plan.hijos()).allSatisfy(h -> {
            assertThat(h.tipo()).isEqualTo(TipoTrabajo.SEARCH);
            assertThat(h.codigo()).isEqualTo("05");
            assertThat(h.nivel()).isEqualTo(Nivel.DEPARTAMENTO);
            assertThat(h.parentId()).isEqualTo(raiz.id());
        });
    }

    @Test
    void unaBusquedaDeUnaSolaPaginaOSinResultadosNoCreaNada() {
        WorkItem item = busqueda(Nivel.DEPARTAMENTO, "05", 1);

        assertThat(planner.planificarBusqueda(item, new RespuestaBusqueda.Exitosa(7, 1)).hijos()).isEmpty();
        Plan vacio = planner.planificarBusqueda(item, new RespuestaBusqueda.Exitosa(0, 0));
        assertThat(vacio.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(vacio.hijos()).isEmpty();
    }

    @Test
    void unaPaginaPosteriorNuncaCreaMasPaginas() {
        WorkItem pagina2 = busqueda(Nivel.DEPARTAMENTO, "05", 2);

        assertThat(planner.planificarBusqueda(pagina2, new RespuestaBusqueda.Exitosa(45, 3)).hijos()).isEmpty();
    }

    // ─── Exceso de mil: el drill-down ────────────────────────────────────────────

    @Test
    void siLaRaizExcedeSeBajaATodosLosDepartamentos() {
        WorkItem raiz = busqueda(Nivel.RAIZ, "", 1);

        Plan plan = planner.planificarBusqueda(raiz, EXCEDE);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXPANDED);
        assertThat(plan.hijos()).extracting(WorkItem::codigo).containsExactly("05", "11", "99");
        assertThat(plan.hijos()).allSatisfy(h -> {
            assertThat(h.tipo()).isEqualTo(TipoTrabajo.SEARCH);
            assertThat(h.nivel()).isEqualTo(Nivel.DEPARTAMENTO);
            assertThat(h.pagina()).isEqualTo(1);
            assertThat(h.parentId()).isEqualTo(raiz.id());
            assertThat(h.prioridad()).isEqualTo(Nivel.DEPARTAMENTO.profundidad());
        });
    }

    @Test
    void siUnDepartamentoExcedeSeBajaASusMunicipios() {
        Plan plan = planner.planificarBusqueda(busqueda(Nivel.DEPARTAMENTO, "05", 1), EXCEDE);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXPANDED);
        assertThat(plan.hijos()).extracting(WorkItem::codigo).containsExactly("05001", "05002");
        assertThat(plan.hijos()).extracting(WorkItem::nivel).containsOnly(Nivel.MUNICIPIO);
        assertThat(plan.hijos()).extracting(WorkItem::etiqueta).containsExactly("MEDELLIN", "ABEJORRAL");
    }

    @Test
    void unDepartamentoQueExcedeSinMunicipiosMapeadosQuedaSinRespuesta() {
        Plan plan = planner.planificarBusqueda(busqueda(Nivel.DEPARTAMENTO, "99", 1), EXCEDE);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXCEEDED);
        assertThat(plan.hijos()).isEmpty();
        assertThat(plan.motivo()).contains("99");
    }

    @Test
    void siMunicipioEntidadOEspecialidadExcedenSePideUnListadoRemoto() {
        for (Nivel nivel : new Nivel[]{Nivel.MUNICIPIO, Nivel.ENTIDAD, Nivel.ESPECIALIDAD}) {
            String codigo = "0".repeat(nivel.longitudCodigo());
            WorkItem padre = busqueda(nivel, codigo, 1);

            Plan plan = planner.planificarBusqueda(padre, EXCEDE);

            assertThat(plan.estado()).as("nivel %s", nivel).isEqualTo(EstadoTrabajo.EXPANDED);
            assertThat(plan.hijos()).hasSize(1);
            WorkItem lista = plan.hijos().get(0);
            assertThat(lista.tipo()).isEqualTo(TipoTrabajo.LIST);
            assertThat(lista.nivel()).isEqualTo(nivel);
            assertThat(lista.codigo()).isEqualTo(codigo);
            assertThat(lista.parentId()).isEqualTo(padre.id());
        }
    }

    @Test
    void unDespachoQueExcedeEsElFinalDelArbolYNoDevuelveNada() {
        Plan plan = planner.planificarBusqueda(busqueda(Nivel.DESPACHO, "110013109001", 1), EXCEDE);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXCEEDED);
        assertThat(plan.hijos()).isEmpty();
        assertThat(plan.motivo()).contains("110013109001");
    }

    @Test
    void unExcesoEnUnaPaginaPosteriorALaPrimeraEsInconsistenteYNoSeExpande() {
        Plan plan = planner.planificarBusqueda(busqueda(Nivel.DEPARTAMENTO, "05", 2), EXCEDE);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXCEEDED);
        assertThat(plan.hijos()).isEmpty();
    }

    // ─── Listados ────────────────────────────────────────────────────────────────

    @Test
    void unListadoCreaUnaBusquedaPorElementoDelNivelSiguiente() {
        WorkItem lista = WorkItem.nuevo(JOB, null, TipoTrabajo.LIST, Nivel.MUNICIPIO, "11001", "BOGOTA", 1, null).iniciar(T0);

        Plan plan = planner.planificarListado(lista, List.of(
                new ItemListado("", "Seleccione ..."),
                new ItemListado("1100133", "JUZGADO ADMINISTRATIVO"),
                new ItemListado("1100131", "JUZGADO DE CIRCUITO ")));

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(plan.hijos()).extracting(WorkItem::codigo).containsExactly("1100133", "1100131");
        assertThat(plan.hijos()).extracting(WorkItem::nivel).containsOnly(Nivel.ENTIDAD);
        assertThat(plan.hijos()).extracting(WorkItem::tipo).containsOnly(TipoTrabajo.SEARCH);
        assertThat(plan.hijos()).extracting(WorkItem::etiqueta).containsExactly("JUZGADO ADMINISTRATIVO", "JUZGADO DE CIRCUITO");
    }

    @Test
    void losDespachosDelListadoSeRecortanYLlegaAlUltimoNivel() {
        WorkItem lista = WorkItem.nuevo(JOB, null, TipoTrabajo.LIST, Nivel.ESPECIALIDAD, "110013305", null, 1, null).iniciar(T0);

        Plan plan = planner.planificarListado(lista, List.of(new ItemListado("110013305517  ", "JUZGADO 517")));

        assertThat(plan.hijos()).hasSize(1);
        assertThat(plan.hijos().get(0).codigo()).isEqualTo("110013305517");
        assertThat(plan.hijos().get(0).nivel()).isEqualTo(Nivel.DESPACHO);
    }

    @Test
    void unListadoVacioDejaElPropioItemListadoSinRespuesta() {
        WorkItem lista = WorkItem.nuevo(JOB, null, TipoTrabajo.LIST, Nivel.ENTIDAD, "1100133", null, 1, null).iniciar(T0);

        Plan plan = planner.planificarListado(lista, List.of(new ItemListado("", "Seleccione ...")));

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.EXCEEDED);
        assertThat(plan.hijos()).isEmpty();
        assertThat(plan.aplicarA(lista, null, null).estado()).isEqualTo(EstadoTrabajo.EXCEEDED);
    }

    @Test
    void losElementosRepetidosDeUnListadoNoDuplicanBusquedas() {
        WorkItem lista = WorkItem.nuevo(JOB, null, TipoTrabajo.LIST, Nivel.MUNICIPIO, "11001", null, 1, null).iniciar(T0);

        Plan plan = planner.planificarListado(lista, List.of(
                new ItemListado("1100133", "A"), new ItemListado("1100133 ", "A otra vez")));

        assertThat(plan.hijos()).hasSize(1);
    }

    // ─── Procesos encontrados ────────────────────────────────────────────────────

    @Test
    void unProcesoAceptadoGeneraTresPeticionesDeDetalle() {
        WorkItem origen = busqueda(Nivel.DESPACHO, "110013109001", 1);

        List<WorkItem> trabajo = planner.trabajoParaProceso(origen, 27598405L, Veredicto.ACEPTADO);

        assertThat(trabajo).extracting(WorkItem::tipo)
                .containsExactly(TipoTrabajo.DETALLE, TipoTrabajo.ACTUACIONES, TipoTrabajo.SUJETOS);
        assertThat(trabajo).extracting(WorkItem::idProceso).containsOnly(27598405L);
        assertThat(trabajo).extracting(WorkItem::parentId).containsOnly(origen.id());
        assertThat(trabajo).extracting(WorkItem::nivel).containsOnlyNulls();
    }

    @Test
    void unProcesoRechazadoNoGeneraTrabajoYUnoTruncadoPideSusSujetos() {
        WorkItem origen = busqueda(Nivel.DESPACHO, "110013109001", 1);

        assertThat(planner.trabajoParaProceso(origen, 1L, Veredicto.RECHAZADO)).isEmpty();
        List<WorkItem> resolver = planner.trabajoParaProceso(origen, 1L, Veredicto.REQUIERE_SUJETOS);
        assertThat(resolver).hasSize(1);
        assertThat(resolver.get(0).tipo()).isEqualTo(TipoTrabajo.RESOLVE_SUJETOS);
        assertThat(resolver.get(0).pagina()).isEqualTo(1);
    }

    @Test
    void elMismoProcesoHalladoDosVecesProduceLosMismosIds() {
        WorkItem origen = busqueda(Nivel.DESPACHO, "110013109001", 1);
        WorkItem otroOrigen = busqueda(Nivel.DESPACHO, "110013109001", 2);

        assertThat(planner.trabajoParaProceso(origen, 5L, Veredicto.ACEPTADO))
                .extracting(WorkItem::id)
                .containsExactlyElementsOf(planner.trabajoParaProceso(otroOrigen, 5L, Veredicto.ACEPTADO)
                        .stream().map(WorkItem::id).toList());
    }

    @Test
    void losSujetosDeUnProcesoAceptadoSePaginanDesdeLaPrimera() {
        WorkItem sujetos = WorkItem.nuevo(JOB, null, TipoTrabajo.SUJETOS, null, "", null, 1, 9L).iniciar(T0);

        Plan plan = planner.planificarSujetos(sujetos, 3);

        assertThat(plan.hijos()).extracting(WorkItem::pagina).containsExactly(2, 3);
        assertThat(plan.hijos()).extracting(WorkItem::tipo).containsOnly(TipoTrabajo.SUJETOS);
        assertThat(plan.hijos()).extracting(WorkItem::idProceso).containsOnly(9L);
    }

    // ─── Resolución de sujetos truncados ─────────────────────────────────────────

    private WorkItem resolver(int pagina) {
        return WorkItem.nuevo(JOB, null, TipoTrabajo.RESOLVE_SUJETOS, null, "", null, pagina, 9L).iniciar(T0);
    }

    @Test
    void siLosSujetosAcumuladosAceptanElProcesoSeCreaSuDetalleYSeCortaLaCadena() {
        Plan plan = planner.planificarResolucion(resolver(1), 3, true);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(plan.hijos()).extracting(WorkItem::tipo)
                .containsExactly(TipoTrabajo.DETALLE, TipoTrabajo.ACTUACIONES, TipoTrabajo.SUJETOS);
    }

    @Test
    void siNoAceptaYQuedanPaginasPideLaSiguiente() {
        Plan plan = planner.planificarResolucion(resolver(1), 3, false);

        assertThat(plan.hijos()).hasSize(1);
        assertThat(plan.hijos().get(0).tipo()).isEqualTo(TipoTrabajo.RESOLVE_SUJETOS);
        assertThat(plan.hijos().get(0).pagina()).isEqualTo(2);
    }

    @Test
    void siNoAceptaEnLaUltimaPaginaElProcesoSeDescarta() {
        Plan plan = planner.planificarResolucion(resolver(3), 3, false);

        assertThat(plan.estado()).isEqualTo(EstadoTrabajo.DONE);
        assertThat(plan.hijos()).isEmpty();
    }

    // ─── Invariantes ─────────────────────────────────────────────────────────────

    @Test
    void losHijosSonDeterministasYDependenDelJob() {
        WorkItem raiz = busqueda(Nivel.RAIZ, "", 1);
        WorkItem raizOtroJob = WorkItem.nuevo(UUID.randomUUID(), null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", null, 1, null).iniciar(T0);

        List<UUID> primera = planner.planificarBusqueda(raiz, EXCEDE).hijos().stream().map(WorkItem::id).toList();
        List<UUID> segunda = planner.planificarBusqueda(raiz, EXCEDE).hijos().stream().map(WorkItem::id).toList();
        List<UUID> otroJob = planner.planificarBusqueda(raizOtroJob, EXCEDE).hijos().stream().map(WorkItem::id).toList();

        assertThat(primera).isEqualTo(segunda);
        assertThat(otroJob).doesNotContainAnyElementsOf(primera);
    }

    @Test
    void elPlanLlevaElItemEnCursoASuEstadoFinal() {
        WorkItem raiz = busqueda(Nivel.RAIZ, "", 1);
        WorkItem despacho = busqueda(Nivel.DESPACHO, "110013109001", 1);

        assertThat(planner.planificarBusqueda(raiz, EXCEDE).aplicarA(raiz, null, null).estado())
                .isEqualTo(EstadoTrabajo.EXPANDED);
        assertThat(planner.planificarBusqueda(raiz, new RespuestaBusqueda.Exitosa(5, 1)).aplicarA(raiz, 5, 1).totalRegistros())
                .isEqualTo(5);
        assertThat(planner.planificarBusqueda(despacho, EXCEDE).aplicarA(despacho, null, null).estado())
                .isEqualTo(EstadoTrabajo.EXCEEDED);
    }

    @Test
    void unItemDeOtroTipoSeRechaza() {
        WorkItem lista = WorkItem.nuevo(JOB, null, TipoTrabajo.LIST, Nivel.MUNICIPIO, "11001", null, 1, null);

        assertThatThrownBy(() -> planner.planificarBusqueda(lista, EXCEDE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.planificarListado(busqueda(Nivel.RAIZ, "", 1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unPlanSoloPuedeTerminarEnDoneExpandedOExceeded() {
        assertThatThrownBy(() -> new Plan(EstadoTrabajo.FAILED, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
