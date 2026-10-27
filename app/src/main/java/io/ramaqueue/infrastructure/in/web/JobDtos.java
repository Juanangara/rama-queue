package io.ramaqueue.infrastructure.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.ramaqueue.application.port.in.ConsultarProgreso.ProgresoDeJob;
import io.ramaqueue.application.port.out.ProcesoHitRepository.PaginaDeResultados;
import io.ramaqueue.application.port.out.ProcesoHitRepository.ResultadoDeProceso;
import io.ramaqueue.application.port.out.WorkItemRepository.NodoDeArbol;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.SearchJob;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Formas JSON que expone la API (separadas del dominio a propósito). */
public final class JobDtos {

    private JobDtos() {
    }

    public record CrearJob(String nombre, String tipoPersona, String categoria) {
    }

    public record JobResumen(UUID id, String nombre, String tipoPersona, String categoria, String estado, Instant creadoEn,
                             Instant finalizadoEn) {
        static JobResumen de(SearchJob j) {
            return new JobResumen(j.id(), j.nombre(), j.tipoPersona().codigo(), j.categoria().codigo(), j.estado().name(),
                    j.creadoEn(), j.finalizadoEn());
        }
    }

    public record JobProgreso(JobResumen job, Map<String, Long> itemsPorEstado, long itemsTotales, long itemsPendientes,
                              double avance, long procesosAceptados, long procesosCompletos) {
        public static JobProgreso de(ProgresoDeJob p) {
            Map<String, Long> porEstado = new LinkedHashMap<>();
            for (EstadoTrabajo e : EstadoTrabajo.values()) {
                porEstado.put(e.name(), p.conteos().cantidad(e));
            }
            long total = p.conteos().total();
            long activos = p.conteos().activos();
            return new JobProgreso(JobResumen.de(p.job()), porEstado, total, activos,
                    total == 0 ? 0 : (double) (total - activos) / total, p.procesosAceptados(), p.procesosCompletos());
        }
    }

    public record Nodo(UUID id, UUID padre, String tipo, String nivel, String codigo, String etiqueta, String estado, int intentos,
                       Integer registros, Integer paginas, long paginasTerminadas, String error) {
        static Nodo de(NodoDeArbol n) {
            return new Nodo(n.id(), n.parentId(), n.tipo().name(), n.nivel() == null ? null : n.nivel().name(), n.codigo(),
                    n.etiqueta(), n.estado().name(), n.intentos(), n.totalRegistros(), n.totalPaginas(), n.paginasTerminadas(),
                    n.ultimoError());
        }
    }

    public record ProcesoDto(long idProceso, String llaveProceso, String despacho, String departamento, String ciudad,
                             String categoria, String sujetosProcesales, boolean sujetosTruncados, String fechaProceso,
                             String fechaUltimaActuacion, String estadoDetalle, JsonNode detalle, JsonNode ultimaActuacion,
                             JsonNode sujetos) {
        static ProcesoDto de(ResultadoDeProceso r, com.fasterxml.jackson.databind.ObjectMapper json) {
            return new ProcesoDto(r.idProceso(), r.llaveProceso(), r.despacho(), r.departamento(), r.ciudad(), r.categoria(),
                    r.sujetosProcesales(), r.sujetosTruncados(), r.fechaProceso(), r.fechaUltimaActuacion(), r.estadoDetalle(),
                    leer(json, r.detalleJson()), leer(json, r.ultimaActuacionJson()), leer(json, r.sujetosCompletosJson()));
        }

        private static JsonNode leer(com.fasterxml.jackson.databind.ObjectMapper json, String texto) {
            try {
                return texto == null ? null : json.readTree(texto);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return null;
            }
        }
    }

    public record PaginaDeProcesos(List<ProcesoDto> elementos, long total, int pagina, int tamano, long paginas) {
        static PaginaDeProcesos de(PaginaDeResultados p, com.fasterxml.jackson.databind.ObjectMapper json) {
            return new PaginaDeProcesos(p.elementos().stream().map(r -> ProcesoDto.de(r, json)).toList(), p.total(), p.pagina(),
                    p.tamano(), (p.total() + p.tamano() - 1) / p.tamano());
        }
    }
}
