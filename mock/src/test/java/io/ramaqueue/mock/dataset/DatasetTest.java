package io.ramaqueue.mock.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetTest {

    private static final Territorio TERRITORIO = Territorio.cargar();

    @Test
    void conLaMismaSemillaGeneraElMismoUniverso() {
        Dataset a = new Dataset(TERRITORIO, 42L, true);
        Dataset b = new Dataset(TERRITORIO, 42L, true);
        Dataset otro = new Dataset(TERRITORIO, 43L, true);

        assertThat(a.despachos()).hasSameSizeAs(b.despachos());
        assertThat(a.totalProcesos()).isEqualTo(b.totalProcesos());
        assertThat(a.proceso(1234).llave()).isEqualTo(b.proceso(1234).llave());
        assertThat(a.proceso(1234).sujetosProcesales()).isEqualTo(b.proceso(1234).sujetosProcesales());
        assertThat(otro.proceso(1234).llave()).isNotEqualTo(a.proceso(1234).llave());
    }

    @Test
    void unApellidoComunExcedeMilEnLaRaizYUnoInexistenteNoTieneResultados() {
        Dataset dataset = new Dataset(TERRITORIO, 42L, true);

        assertThat(dataset.buscar(List.of("GARCIA"), true, "").length).isGreaterThan(Dataset.MAXIMO_RESULTADOS);
        assertThat(dataset.buscar(List.of("ZZZINEXISTENTE"), true, "")).isEmpty();
    }

    @Test
    void variosTokensSeCombinanConY() {
        Dataset dataset = new Dataset(TERRITORIO, 42L, true);

        int solo = dataset.buscar(List.of("GARCIA"), true, "").length;
        int combinado = dataset.buscar(List.of("GARCIA", "RODRIGUEZ"), true, "").length;

        assertThat(combinado).isPositive().isLessThan(solo);
    }

    @Test
    void elPrefijoDeDespachoAcotaLaBusqueda() {
        Dataset dataset = new Dataset(TERRITORIO, 42L, true);

        int raiz = dataset.buscar(List.of("GARCIA"), true, "").length;
        int antioquia = dataset.buscar(List.of("GARCIA"), true, "05").length;
        int medellin = dataset.buscar(List.of("GARCIA"), true, "05001").length;

        assertThat(medellin).isLessThanOrEqualTo(antioquia);
        assertThat(antioquia).isLessThan(raiz);
    }

    @Test
    void losDespachosCongestionadosNuncaPuedenResponder() {
        Dataset congestionado = new Dataset(TERRITORIO, 42L, true);
        Dataset normal = new Dataset(TERRITORIO, 42L, false);

        @SuppressWarnings("unchecked")
        List<String> conCongestion = (List<String>) congestionado.resumen(List.of("GARCIA"), true).get("despachosSinRespuesta");
        @SuppressWarnings("unchecked")
        List<String> sinCongestion = (List<String>) normal.resumen(List.of("GARCIA"), true).get("despachosSinRespuesta");

        assertThat(conCongestion).containsExactly("050013109001", "110013109001", "760013109001");
        assertThat(sinCongestion).isEmpty();
    }

    @Test
    void elResumenCuentaLosNodosQueExcedenPorNivel() {
        Dataset dataset = new Dataset(TERRITORIO, 42L, true);

        Map<String, Object> resumen = dataset.resumen(List.of("GARCIA"), true);

        assertThat(resumen.get("excedeMil")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> nodos = (Map<String, Object>) resumen.get("nodosQueExceden");
        assertThat(nodos).containsKeys("departamento", "municipio", "entidad", "especialidad", "despacho");
        assertThat((Long) nodos.get("departamento")).isPositive();
    }

    @Test
    void tokenizaSinTildesYDescartaPalabrasCortas() {
        assertThat(Dataset.tokenizar("José Ñandú de la Cruz")).containsExactly("JOSE", "NANDU", "CRUZ");
        assertThat(Dataset.tokenizar("AB")).isEmpty();
        assertThat(Dataset.tokenizar("")).isEmpty();
    }
}
