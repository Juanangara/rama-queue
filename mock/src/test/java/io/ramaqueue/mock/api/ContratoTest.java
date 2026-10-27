package io.ramaqueue.mock.api;

import io.ramaqueue.mock.dataset.Dataset;
import io.ramaqueue.mock.dataset.Dataset.Despacho;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mock.latency-ms=0", "mock.jitter-ms=0", "mock.limit-per-minute=100000"})
@AutoConfigureMockMvc
class ContratoTest {

    private static final String BUSQUEDA = "/api/v2/Procesos/Consulta/NombreRazonSocial";

    @Autowired
    MockMvc mvc;

    @Autowired
    Dataset dataset;

    @BeforeEach
    void reiniciar() throws Exception {
        mvc.perform(post("/__admin/reset")).andExpect(status().isOk());
    }

    private ResultActions buscar(String nombre, String tipo, String codigo, int pagina) throws Exception {
        return mvc.perform(get(BUSQUEDA)
                .param("nombre", nombre).param("tipoPersona", tipo).param("SoloActivos", "false")
                .param("codificacionDespacho", codigo).param("pagina", String.valueOf(pagina)));
    }

    // ─── Validación de entrada ───────────────────────────────────────────────────

    @Test
    void nombreVacioDevuelve404() throws Exception {
        buscar("", "jur", "", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.Message", containsString("NombreRazonSocial")));
    }

    @Test
    void nombreDeMenosDeTresCaracteresDevuelve404() throws Exception {
        buscar("AB", "jur", "", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.Message", containsString("full-text")));
    }

    // ─── Más de mil registros ────────────────────────────────────────────────────

    @Test
    void masDeMilRegistrosDevuelve400ConElMensajeReal() throws Exception {
        buscar("GARCIA", "nat", "", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.StatusCode").value(400))
                .andExpect(jsonPath("$.Message", containsString("Hay más de mil registros")));
    }

    @Test
    void unDespachoCongestionadoSigueExcediendoMilEnElUltimoNivel() throws Exception {
        buscar("GARCIA", "nat", "110013109001", 1).andExpect(status().isBadRequest());
    }

    // ─── Paginación ──────────────────────────────────────────────────────────────

    @Test
    void sinResultadosDevuelve200ConCeroPaginas() throws Exception {
        buscar("ZZZINEXISTENTE", "jur", "", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paginacion.cantidadRegistros").value(0))
                .andExpect(jsonPath("$.paginacion.cantidadPaginas").value(0))
                .andExpect(jsonPath("$.procesos.length()").value(0));
    }

    @Test
    void paginaDeVeinteEnVeinteConLaUltimaIncompleta() throws Exception {
        Despacho despacho = dataset.despachos().stream()
                .filter(d -> {
                    int n = dataset.buscar(List.of("GARCIA"), true, d.codigo()).length;
                    return n > 20 && n <= Dataset.MAXIMO_RESULTADOS;
                })
                .findFirst().orElseThrow();
        int total = dataset.buscar(List.of("GARCIA"), true, despacho.codigo()).length;
        int paginas = (total + 19) / 20;
        int enUltima = total - (paginas - 1) * 20;

        buscar("GARCIA", "nat", despacho.codigo(), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paginacion.cantidadRegistros").value(total))
                .andExpect(jsonPath("$.paginacion.registrosPagina").value(20))
                .andExpect(jsonPath("$.paginacion.cantidadPaginas").value(paginas))
                .andExpect(jsonPath("$.procesos.length()").value(20))
                .andExpect(jsonPath("$.procesos[0].llaveProceso").isString());
        buscar("GARCIA", "nat", despacho.codigo(), paginas)
                .andExpect(jsonPath("$.procesos.length()").value(enUltima));
    }

    @Test
    void cadaProcesoTraeLosCamposObservadosEnLaApiReal() throws Exception {
        String codigo = dataset.despachos().stream()
                .filter(d -> dataset.buscar(List.of("GARCIA"), true, d.codigo()).length > 0)
                .findFirst().orElseThrow().codigo();

        buscar("GARCIA", "nat", codigo, 1)
                .andExpect(jsonPath("$.tipoConsulta").value("NombreRazonSocial"))
                .andExpect(jsonPath("$.procesos[0].idProceso").isNumber())
                .andExpect(jsonPath("$.procesos[0].llaveProceso").value(org.hamcrest.Matchers.matchesPattern("\\d{23}")))
                .andExpect(jsonPath("$.procesos[0].fechaProceso").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T00:00:00")))
                .andExpect(jsonPath("$.procesos[0].despacho").isString())
                .andExpect(jsonPath("$.procesos[0].departamento").isString())
                .andExpect(jsonPath("$.procesos[0].sujetosProcesales", containsString("Demandante: ")))
                .andExpect(jsonPath("$.procesos[0].esPrivado").value(false))
                .andExpect(jsonPath("$.procesos[0].cantFilas").value(-1));
    }

    // ─── Listados jerárquicos ────────────────────────────────────────────────────

    @Test
    void losListadosEmpiezanConSeleccioneYSeRecorrenHastaElDespacho() throws Exception {
        mvc.perform(get("/api/v2/Lista/Entidades/11001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value(""))
                .andExpect(jsonPath("$[0].nombre").value("Seleccione ..."))
                .andExpect(jsonPath("$.length()", greaterThan(1)))
                .andExpect(jsonPath("$[1].codigo", org.hamcrest.Matchers.matchesPattern("11001\\d{2}")));

        mvc.perform(get("/api/v2/Lista/Especialidades/1100131"))
                .andExpect(jsonPath("$[0].codigo").value(""))
                .andExpect(jsonPath("$[1].codigo", org.hamcrest.Matchers.matchesPattern("1100131\\d{2}")));

        mvc.perform(get("/api/v2/Lista/Despachos/110013109"))
                .andExpect(jsonPath("$[0].codigo").value(""))
                .andExpect(jsonPath("$[1].codigo", endsWith("  ")))
                .andExpect(jsonPath("$[1].nombre", containsString("PENAL")));
    }

    @Test
    void unMunicipioSinDespachosSoloDevuelveSeleccione() throws Exception {
        mvc.perform(get("/api/v2/Lista/Entidades/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ─── Detalle de un proceso ───────────────────────────────────────────────────

    @Test
    void detalleActuacionesYSujetosTienenLaFormaObservada() throws Exception {
        long id = dataset.proceso(10).id();

        mvc.perform(get("/api/v2/Proceso/Detalle/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llaveProceso").isString())
                .andExpect(jsonPath("$.tipoProceso").isString())
                .andExpect(jsonPath("$.claseProceso").isString())
                .andExpect(jsonPath("$.subclaseProceso").isString())
                .andExpect(jsonPath("$.ponente").isString());

        mvc.perform(get("/api/v2/Proceso/Actuaciones/" + id).param("pagina", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actuaciones[0].consActuacion").isNumber())
                .andExpect(jsonPath("$.paginacion.registrosPagina").value(40));

        mvc.perform(get("/api/v2/Proceso/Sujetos/" + id).param("pagina", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sujetos[0].nombreRazonSocial").isString())
                .andExpect(jsonPath("$.sujetos[0].identificacion").doesNotExist());
    }

    @Test
    void losSujetosTruncadosSeResuelvenConElEndpointDeSujetos() throws Exception {
        Dataset.Proceso truncado = null;
        for (int i = 0; i < dataset.totalProcesos() && truncado == null; i++) {
            if (dataset.proceso(i).truncado()) {
                truncado = dataset.proceso(i);
            }
        }
        assertThat(truncado).isNotNull();

        assertThat(truncado.sujetosProcesales()).contains("...");
        mvc.perform(get("/api/v2/Proceso/Sujetos/" + truncado.id()))
                .andExpect(jsonPath("$.sujetos.length()").value(truncado.sujetos().size()));
    }

    @Test
    void unProcesoInexistenteDevuelve404() throws Exception {
        mvc.perform(get("/api/v2/Proceso/Detalle/1")).andExpect(status().isNotFound());
    }

    // ─── Caos ────────────────────────────────────────────────────────────────────

    @Test
    void unBloqueoManualDevuelve403HastaQueSeReinicie() throws Exception {
        mvc.perform(post("/__admin/block").param("seconds", "30")).andExpect(status().isOk());

        buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/Lista/Entidades/11001")).andExpect(status().isForbidden());

        mvc.perform(post("/__admin/reset")).andExpect(status().isOk());
        buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isOk());
    }

    @Test
    void superarElLimitePorMinutoBloqueaAutomaticamente() throws Exception {
        mvc.perform(post("/__admin/limit").param("perMinute", "3")).andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isOk());
        }
        buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isForbidden());
        // Sigue bloqueado aunque ya no se supere el límite: es un bloqueo temporal, no solo un contador.
        mvc.perform(post("/__admin/limit").param("perMinute", "1000"));
        buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isForbidden());
    }

    @Test
    void lasEstadisticasRegistranPicoDeConcurrenciaYEstados() throws Exception {
        buscar("ZZZINEXISTENTE", "jur", "", 1).andExpect(status().isOk());
        buscar("", "jur", "", 1).andExpect(status().isNotFound());

        mvc.perform(get("/__admin/stats"))
                .andExpect(jsonPath("$.totalPeticiones").value(2))
                .andExpect(jsonPath("$.porEstadoHttp['200']").value(1))
                .andExpect(jsonPath("$.porEstadoHttp['404']").value(1))
                .andExpect(jsonPath("$.picoEnVuelo").value(1));
    }

    @Test
    void elEndpointDeUniversoResumeElArbolEsperado() throws Exception {
        mvc.perform(get("/__admin/dataset").param("nombre", "GARCIA").param("tipoPersona", "nat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excedeMil").value(true))
                .andExpect(jsonPath("$.despachosSinRespuesta.length()").value(3));
    }
}
