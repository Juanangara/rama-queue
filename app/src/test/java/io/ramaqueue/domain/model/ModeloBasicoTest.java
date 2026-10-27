package io.ramaqueue.domain.model;

import io.ramaqueue.domain.policy.JobStatusResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeloBasicoTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    // ─── Nivel ───────────────────────────────────────────────────────────────────

    @Test
    void losNivelesSeRecorrenDeLaRaizAlDespacho() {
        assertThat(Nivel.RAIZ.siguiente()).contains(Nivel.DEPARTAMENTO);
        assertThat(Nivel.ESPECIALIDAD.siguiente()).contains(Nivel.DESPACHO);
        assertThat(Nivel.DESPACHO.siguiente()).isEmpty();
        assertThat(Nivel.DESPACHO.esUltimo()).isTrue();
        assertThat(Nivel.DEPARTAMENTO.hijosPorListado()).isFalse();
        assertThat(Nivel.MUNICIPIO.hijosPorListado()).isTrue();
        assertThat(Nivel.ENTIDAD.hijosPorListado()).isTrue();
        assertThat(Nivel.ESPECIALIDAD.hijosPorListado()).isTrue();
        assertThat(Nivel.DESPACHO.hijosPorListado()).isFalse();
    }

    @Test
    void elNivelSeDeduceDeLaLongitudDelCodigo() {
        assertThat(Nivel.deCodigo("")).isEqualTo(Nivel.RAIZ);
        assertThat(Nivel.deCodigo("05")).isEqualTo(Nivel.DEPARTAMENTO);
        assertThat(Nivel.deCodigo("11001")).isEqualTo(Nivel.MUNICIPIO);
        assertThat(Nivel.deCodigo("1100133")).isEqualTo(Nivel.ENTIDAD);
        assertThat(Nivel.deCodigo("110013305")).isEqualTo(Nivel.ESPECIALIDAD);
        assertThat(Nivel.deCodigo("110013305517  ")).isEqualTo(Nivel.DESPACHO);
        assertThatThrownBy(() -> Nivel.deCodigo("123")).isInstanceOf(IllegalArgumentException.class);
    }

    // ─── SearchJob ───────────────────────────────────────────────────────────────

    @Test
    void elNombreNecesitaAlMenosUnaPalabraDeTresLetras() {
        for (String invalido : new String[]{null, "", "   ", "AB", "JO PE", "A B C", "12"}) {
            assertThatThrownBy(() -> SearchJob.validarNombre(invalido))
                    .as("nombre '%s'", invalido).isInstanceOf(EntradaInvalidaException.class);
        }
        assertThat(SearchJob.validarNombre("ABC")).isEqualTo("ABC");
        assertThat(SearchJob.validarNombre("  JOSE   PE ")).isEqualTo("JOSE PE");
        assertThat(SearchJob.validarNombre("María José")).isEqualTo("María José");
    }

    @Test
    void unJobNuevoEstaCorriendoYTieneUnaRaiz() {
        SearchJob job = SearchJob.nuevo(UUID.randomUUID(), " Juan  García ", TipoPersona.NATURAL, Categoria.PENAL, T0);

        assertThat(job.estado()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.nombre()).isEqualTo("Juan García");

        WorkItem raiz = job.itemRaiz();
        assertThat(raiz.tipo()).isEqualTo(TipoTrabajo.SEARCH);
        assertThat(raiz.nivel()).isEqualTo(Nivel.RAIZ);
        assertThat(raiz.codigo()).isEmpty();
        assertThat(raiz.pagina()).isEqualTo(1);
        assertThat(raiz.estado()).isEqualTo(EstadoTrabajo.PENDING);
        assertThat(raiz.parentId()).isNull();
        assertThat(job.itemRaiz().id()).isEqualTo(raiz.id());
    }

    @Test
    void unJobSoloSeFinalizaConUnEstadoFinal() {
        SearchJob job = SearchJob.nuevo(UUID.randomUUID(), "GARCIA", TipoPersona.NATURAL, Categoria.CIVIL, T0);

        assertThat(job.finalizar(JobStatus.PARTIAL, T0, null).estado()).isEqualTo(JobStatus.PARTIAL);
        assertThatThrownBy(() -> job.finalizar(JobStatus.RUNNING, T0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losCodigosDeTipoYCategoriaSeConvierten() {
        assertThat(TipoPersona.desdeCodigo("nat")).isEqualTo(TipoPersona.NATURAL);
        assertThat(TipoPersona.JURIDICA.codigo()).isEqualTo("jur");
        assertThat(Categoria.desdeCodigo("extincionDominio")).isEqualTo(Categoria.EXTINCION_DOMINIO);
        assertThatThrownBy(() -> TipoPersona.desdeCodigo("xyz")).isInstanceOf(EntradaInvalidaException.class);
        assertThatThrownBy(() -> Categoria.desdeCodigo("tributario")).isInstanceOf(EntradaInvalidaException.class);
    }

    // ─── Listados ────────────────────────────────────────────────────────────────

    @Test
    void unListadoCrudoSeLimpia() {
        List<ItemListado> limpio = ItemListado.limpiar(List.of(
                new ItemListado("", "Seleccione ..."),
                new ItemListado("110013305517  ", " JUZGADO 517 "),
                new ItemListado("110013305517", "repetido"),
                new ItemListado("   ", "vacío"),
                new ItemListado("110013305518  ", "JUZGADO 518")));

        assertThat(limpio).containsExactly(
                new ItemListado("110013305517", "JUZGADO 517"),
                new ItemListado("110013305518", "JUZGADO 518"));
    }

    // ─── Estado del job ──────────────────────────────────────────────────────────

    private static ConteosDeTrabajo conteos(Object... paresEstadoCantidad) {
        Map<EstadoTrabajo, Long> m = new EnumMap<>(EstadoTrabajo.class);
        for (int i = 0; i < paresEstadoCantidad.length; i += 2) {
            m.put((EstadoTrabajo) paresEstadoCantidad[i], ((Number) paresEstadoCantidad[i + 1]).longValue());
        }
        return new ConteosDeTrabajo(m);
    }

    @Test
    void elEstadoDelJobSeDeduceDeLosConteos() {
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.PENDING, 1), EstadoTrabajo.EXPANDED, false))
                .isEqualTo(JobStatus.RUNNING);
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.IN_PROGRESS, 1), EstadoTrabajo.DONE, false))
                .isEqualTo(JobStatus.RUNNING);
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.EXPANDED, 2), EstadoTrabajo.EXPANDED, false))
                .isEqualTo(JobStatus.COMPLETED);
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.EXCEEDED, 1), EstadoTrabajo.EXPANDED, false))
                .isEqualTo(JobStatus.PARTIAL);
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.FAILED, 1), EstadoTrabajo.EXPANDED, false))
                .isEqualTo(JobStatus.PARTIAL);
        assertThat(JobStatusResolver.resolver(conteos(EstadoTrabajo.FAILED, 1), EstadoTrabajo.FAILED, false))
                .isEqualTo(JobStatus.FAILED);
        assertThat(JobStatusResolver.resolver(
                conteos(EstadoTrabajo.DONE, 5, EstadoTrabajo.PENDING, 3), EstadoTrabajo.EXPANDED, true))
                .isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void losConteosSonInmutablesYTotalizan() {
        ConteosDeTrabajo c = conteos(EstadoTrabajo.DONE, 4, EstadoTrabajo.EXCEEDED, 1, EstadoTrabajo.PENDING, 2);

        assertThat(c.total()).isEqualTo(7);
        assertThat(c.activos()).isEqualTo(2);
        assertThat(c.conProblema()).isEqualTo(1);
        assertThat(c.cantidad(EstadoTrabajo.FAILED)).isZero();
        assertThatThrownBy(() -> c.porEstado().put(EstadoTrabajo.DONE, 9L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
