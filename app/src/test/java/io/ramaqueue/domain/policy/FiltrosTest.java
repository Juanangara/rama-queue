package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.Veredicto;
import io.ramaqueue.domain.text.TextNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltrosTest {

    private static ProcesoEncontrado proceso(String despacho, String sujetos) {
        return new ProcesoEncontrado(1L, "05001310900120200000100", despacho, "ANTIOQUIA", sujetos,
                "2020-01-01T00:00:00", "2020-02-01T00:00:00");
    }

    // ─── Normalización ───────────────────────────────────────────────────────────

    @Test
    void normalizaTildesMayusculasYEspacios() {
        assertThat(TextNormalizer.normalizar("  EXTINCIÓN   de\tDominio ")).isEqualTo("extincion de dominio");
        assertThat(TextNormalizer.normalizar(null)).isEmpty();
        assertThat(TextNormalizer.palabras("Peña-Núñez, S.A.S")).containsExactly("pena", "nunez", "s", "a");
    }

    // ─── Nombre ──────────────────────────────────────────────────────────────────

    @Test
    void elNombreSeExigeComoPalabrasCompletasEnCualquierOrden() {
        NameMatcher matcher = new NameMatcher("Juan García");

        assertThat(matcher.coincide("Demandante: JUAN CARLOS GARCIA PEREZ | Demandado: X ")).isTrue();
        assertThat(matcher.coincide("Demandante: GARCIA PEREZ JUAN")).isTrue();
        assertThat(matcher.coincide("Demandante: JUANITA GARCIA")).isFalse();
        assertThat(matcher.coincide("Demandante: JUAN LOPEZ")).isFalse();
        assertThat(matcher.coincide("nada", "Demandado: JUAN GARCIA")).isTrue();
        assertThat(matcher.coincide((String) null)).isFalse();
    }

    @Test
    void unNombreSinPalabrasEsInvalido() {
        assertThatThrownBy(() -> new NameMatcher("  ... ")).isInstanceOf(IllegalArgumentException.class);
    }

    // ─── Categoría ───────────────────────────────────────────────────────────────

    @Test
    void cadaCategoriaBuscaSusPalabrasClaveEnDespachoOSujetos() {
        assertThat(CategoryMatcher.coincide(Categoria.PENAL, "JUZGADO 003 PENAL DEL CIRCUITO", "")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.CIVIL, "JUZGADO 001 CIVIL MUNICIPAL", "")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.LABORAL, "JUZGADO 005 LABORAL", "")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.FAMILIAR, "JUZGADO 002 DE FAMILIA", "")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.EXTINCION_DOMINIO, "PENAL ESPECIALIZADO DE EXTINCIÓN DE DOMINIO", "")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.LABORAL, "JUZGADO 001 CIVIL", "Demandante: X | Demandado: LABORAL S.A")).isTrue();
        assertThat(CategoryMatcher.coincide(Categoria.CIVIL, "JUZGADO 001 PENAL", "Demandante: X")).isFalse();
    }

    @Test
    void laExclusionDeEjecucionDePenasSoloAplicaALaCategoriaPenal() {
        String despacho = "JUZGADO 001 PENAL DE EJECUCIÓN DE PENAS Y MEDIDAS DE SEGURIDAD";

        assertThat(CategoryMatcher.excluido(Categoria.PENAL, despacho)).isTrue();
        assertThat(CategoryMatcher.excluido(Categoria.CIVIL, despacho)).isFalse();
        assertThat(CategoryMatcher.excluido(Categoria.PENAL, "JUZGADO 001 PENAL DEL CIRCUITO")).isFalse();
    }

    // ─── Clasificador ────────────────────────────────────────────────────────────

    private final ClasificadorProceso penal = new ClasificadorProceso("JUAN GARCIA", Categoria.PENAL);

    @Test
    void aceptaCuandoElNombreYLaCategoriaEstanVisibles() {
        assertThat(penal.evaluar(proceso("JUZGADO 001 PENAL DE CALI", "Demandante: JUAN GARCIA | Demandado: X ")))
                .isEqualTo(Veredicto.ACEPTADO);
    }

    @Test
    void rechazaSiFallaElNombreOLaCategoria() {
        assertThat(penal.evaluar(proceso("JUZGADO 001 PENAL DE CALI", "Demandante: ANA LOPEZ | Demandado: X ")))
                .isEqualTo(Veredicto.RECHAZADO);
        assertThat(penal.evaluar(proceso("JUZGADO 001 CIVIL DE CALI", "Demandante: JUAN GARCIA | Demandado: X ")))
                .isEqualTo(Veredicto.RECHAZADO);
    }

    @Test
    void conSujetosTruncadosYLoVisibleInsuficientePideLosSujetosCompletos() {
        ProcesoEncontrado truncado = proceso("JUZGADO 001 PENAL DE CALI", "Demandante: ANA LOPEZ | Demandado: X | ...");

        assertThat(penal.evaluar(truncado)).isEqualTo(Veredicto.REQUIERE_SUJETOS);
    }

    @Test
    void conSujetosTruncadosPeroLoVisibleBastaNoGastaUnaPeticionDeMas() {
        ProcesoEncontrado truncado = proceso("JUZGADO 001 PENAL DE CALI", "Demandante: JUAN GARCIA | Demandado: X | ...");

        assertThat(penal.evaluar(truncado)).isEqualTo(Veredicto.ACEPTADO);
    }

    @Test
    void unDespachoExcluidoSeRechazaAunqueLosSujetosVengaTruncados() {
        ProcesoEncontrado ejecucion = proceso("JUZGADO 001 PENAL DE EJECUCIÓN DE PENAS", "Demandante: ANA | ...");

        assertThat(penal.evaluar(ejecucion)).isEqualTo(Veredicto.RECHAZADO);
    }

    @Test
    void conLosSujetosCompletosLaDecisionEsSiempreDefinitiva() {
        String despacho = "JUZGADO 001 PENAL DE CALI";

        assertThat(penal.evaluarConSujetos(despacho, "Demandante: ANA | Demandado: JUAN CARLOS GARCIA"))
                .isEqualTo(Veredicto.ACEPTADO);
        assertThat(penal.evaluarConSujetos(despacho, "Demandante: ANA | Demandado: PEDRO")).isEqualTo(Veredicto.RECHAZADO);
        assertThat(penal.evaluarConSujetos("JUZGADO PENAL DE EJECUCIÓN DE PENAS", "JUAN GARCIA")).isEqualTo(Veredicto.RECHAZADO);
    }

    // ─── Ciudad ──────────────────────────────────────────────────────────────────

    @Test
    void detectaLaCiudadPorPalabrasCompletas() {
        assertThat(CityDetector.detectar("JUZGADO 001 CIVIL DE MEDELLÍN ")).contains("medellin");
        assertThat(CityDetector.detectar("DESPACHO 050 - JUZGADO MUNICIPAL - BOGOTÁ D.C. *")).contains("bogota");
        assertThat(CityDetector.detectar("JUZGADO PENAL DE SANTA MARTA")).contains("santa marta");
        assertThat(CityDetector.detectar("JUZGADO CIVIL DE CALIMA")).isEmpty();
        assertThat(CityDetector.detectar("JUZGADO CIVIL DE TURBO")).isEmpty();
        assertThat(CityDetector.detectar(null)).isEmpty();
    }
}
