package io.ramaqueue.application.port.out;

import io.ramaqueue.application.port.out.RamaJudicialGateway.DetalleProceso;
import io.ramaqueue.application.port.out.RamaJudicialGateway.UltimaActuacion;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SujetoProceso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Procesos hallados por un job. Un proceso con sujetos truncados queda como CANDIDATO hasta que la cadena de
 * {@code RESOLVE_SUJETOS} lo acepte (PENDIENTE de detalle) o lo rechace (RECHAZADO).
 */
public interface ProcesoHitRepository {

    record Conteo(long aceptados, long completos) {
    }

    /** Idempotente: si el proceso ya estaba guardado no cambia nada. */
    void guardar(UUID jobId, ProcesoEncontrado proceso, String ciudad, String categoria, boolean aceptado);

    Optional<ProcesoEncontrado> buscar(UUID jobId, long idProceso);

    void aceptar(UUID jobId, long idProceso);

    void rechazar(UUID jobId, long idProceso);

    void agregarSujetos(UUID jobId, long idProceso, List<SujetoProceso> sujetos);

    /** Sujetos acumulados hasta ahora, en el formato {@code «Rol: NOMBRE | Rol: NOMBRE»}. */
    String textoDeSujetos(UUID jobId, long idProceso);

    void guardarDetalle(UUID jobId, long idProceso, DetalleProceso detalle);

    /** {@code null} registra que el proceso no tiene actuaciones. */
    void guardarUltimaActuacion(UUID jobId, long idProceso, UltimaActuacion actuacion);

    Conteo contar(UUID jobId);

    /** Filtros opcionales de la consulta de resultados; los {@code null} no filtran. */
    record FiltroDeResultados(String ciudad, String departamento, String texto, boolean soloConDetalleCompleto) {
        public static FiltroDeResultados sinFiltros() {
            return new FiltroDeResultados(null, null, null, false);
        }
    }

    /** Un proceso aceptado tal como se muestra al usuario. Los campos {@code *Json} son JSON crudo (o {@code null}). */
    record ResultadoDeProceso(long idProceso, String llaveProceso, String despacho, String departamento, String ciudad,
                              String categoria, String sujetosProcesales, boolean sujetosTruncados, String fechaProceso,
                              String fechaUltimaActuacion, String estadoDetalle, String detalleJson,
                              String ultimaActuacionJson, String sujetosCompletosJson) {
    }

    record PaginaDeResultados(List<ResultadoDeProceso> elementos, long total, int pagina, int tamano) {
    }

    /** Solo los procesos aceptados (PENDIENTE de detalle o COMPLETO); nunca candidatos ni rechazados. */
    PaginaDeResultados listar(UUID jobId, FiltroDeResultados filtro, int pagina, int tamano);
}
