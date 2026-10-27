package io.ramaqueue.infrastructure.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.out.ProcesoHitRepository;
import io.ramaqueue.application.port.out.RamaJudicialGateway.DetalleProceso;
import io.ramaqueue.application.port.out.RamaJudicialGateway.UltimaActuacion;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SujetoProceso;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcProcesoHitRepository implements ProcesoHitRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcProcesoHitRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void guardar(UUID jobId, ProcesoEncontrado p, String ciudad, String categoria, boolean aceptado) {
        jdbc.update("INSERT INTO proceso_hit (job_id, id_proceso, llave_proceso, fecha_proceso, fecha_ultima_actuacion, "
                        + "despacho, departamento, sujetos_procesales, ciudad, categoria, sujetos_truncados, detail_status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (job_id, id_proceso) DO NOTHING",
                jobId, p.idProceso(), p.llaveProceso(), p.fechaProceso(), p.fechaUltimaActuacion(), p.despacho(),
                p.departamento(), p.sujetosProcesales(), ciudad, categoria, p.sujetosTruncados(),
                aceptado ? "PENDIENTE" : "CANDIDATO");
    }

    @Override
    public Optional<ProcesoEncontrado> buscar(UUID jobId, long idProceso) {
        return jdbc.query("SELECT id_proceso, llave_proceso, despacho, departamento, sujetos_procesales, fecha_proceso, "
                        + "fecha_ultima_actuacion FROM proceso_hit WHERE job_id = ? AND id_proceso = ?",
                (rs, fila) -> new ProcesoEncontrado(rs.getLong("id_proceso"), rs.getString("llave_proceso"),
                        rs.getString("despacho"), rs.getString("departamento"), rs.getString("sujetos_procesales"),
                        rs.getString("fecha_proceso"), rs.getString("fecha_ultima_actuacion")),
                jobId, idProceso).stream().findFirst();
    }

    @Override
    public void aceptar(UUID jobId, long idProceso) {
        jdbc.update("UPDATE proceso_hit SET detail_status = 'PENDIENTE' WHERE job_id = ? AND id_proceso = ? "
                + "AND detail_status = 'CANDIDATO'", jobId, idProceso);
    }

    @Override
    public void rechazar(UUID jobId, long idProceso) {
        jdbc.update("UPDATE proceso_hit SET detail_status = 'RECHAZADO' WHERE job_id = ? AND id_proceso = ? "
                + "AND detail_status = 'CANDIDATO'", jobId, idProceso);
    }

    /**
     * Une los sujetos nuevos con los ya guardados SIN repetir: un proceso con sujetos truncados los recibe dos veces (al
     * resolverlos y luego en el detalle) y las páginas pueden llegar en cualquier orden. {@code FOR UPDATE} serializa
     * las páginas concurrentes del mismo proceso.
     */
    @Override
    public void agregarSujetos(UUID jobId, long idProceso, List<SujetoProceso> sujetos) {
        LinkedHashSet<SujetoProceso> union = new LinkedHashSet<>(leerSujetos(jobId, idProceso, true));
        union.addAll(sujetos);
        jdbc.update("UPDATE proceso_hit SET sujetos_completos = ?::jsonb WHERE job_id = ? AND id_proceso = ?",
                aJson(new ArrayList<>(union)), jobId, idProceso);
        completarSiCorresponde(jobId, idProceso);
    }

    @Override
    public String textoDeSujetos(UUID jobId, long idProceso) {
        return String.join(" | ", leerSujetos(jobId, idProceso, false).stream().map(SujetoProceso::comoTexto).toList());
    }

    private List<SujetoProceso> leerSujetos(UUID jobId, long idProceso, boolean bloquear) {
        List<String> filas = jdbc.query("SELECT sujetos_completos::text FROM proceso_hit WHERE job_id = ? AND id_proceso = ?"
                + (bloquear ? " FOR UPDATE" : ""), (rs, fila) -> rs.getString(1), jobId, idProceso);
        if (filas.isEmpty() || filas.get(0) == null) {
            return List.of();
        }
        try {
            return json.readValue(filas.get(0), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sujetos_completos ilegible para el proceso " + idProceso, e);
        }
    }

    @Override
    public void guardarDetalle(UUID jobId, long idProceso, DetalleProceso detalle) {
        jdbc.update("UPDATE proceso_hit SET detalle = ?::jsonb WHERE job_id = ? AND id_proceso = ?",
                aJson(detalle), jobId, idProceso);
        completarSiCorresponde(jobId, idProceso);
    }

    @Override
    public void guardarUltimaActuacion(UUID jobId, long idProceso, UltimaActuacion actuacion) {
        jdbc.update("UPDATE proceso_hit SET ultima_actuacion = ?::jsonb WHERE job_id = ? AND id_proceso = ?",
                actuacion == null ? "{}" : aJson(actuacion), jobId, idProceso);
        completarSiCorresponde(jobId, idProceso);
    }

    @Override
    public Conteo contar(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE detail_status IN ('PENDIENTE','COMPLETO')), "
                        + "count(*) FILTER (WHERE detail_status = 'COMPLETO') FROM proceso_hit WHERE job_id = ?",
                (rs, fila) -> new Conteo(rs.getLong(1), rs.getLong(2)), jobId);
    }

    @Override
    public PaginaDeResultados listar(UUID jobId, FiltroDeResultados filtro, int pagina, int tamano) {
        StringBuilder donde = new StringBuilder("job_id = ? AND detail_status IN ('PENDIENTE','COMPLETO')");
        List<Object> parametros = new ArrayList<>();
        parametros.add(jobId);
        if (filtro.soloConDetalleCompleto()) {
            donde.append(" AND detail_status = 'COMPLETO'");
        }
        if (filtro.ciudad() != null && !filtro.ciudad().isBlank()) {
            donde.append(" AND lower(ciudad) = lower(?)");
            parametros.add(filtro.ciudad().trim());
        }
        if (filtro.departamento() != null && !filtro.departamento().isBlank()) {
            donde.append(" AND lower(departamento) = lower(?)");
            parametros.add(filtro.departamento().trim());
        }
        if (filtro.texto() != null && !filtro.texto().isBlank()) {
            String patron = "%" + filtro.texto().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            donde.append(" AND (despacho ILIKE ? OR sujetos_procesales ILIKE ? OR llave_proceso ILIKE ? "
                    + "OR sujetos_completos::text ILIKE ?)");
            for (int i = 0; i < 4; i++) {
                parametros.add(patron);
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM proceso_hit WHERE " + donde, Long.class, parametros.toArray());

        List<Object> conPagina = new ArrayList<>(parametros);
        conPagina.add(tamano);
        conPagina.add((long) (pagina - 1) * tamano);
        List<ResultadoDeProceso> elementos = jdbc.query(
                "SELECT id_proceso, llave_proceso, despacho, departamento, ciudad, categoria, sujetos_procesales, "
                        + "sujetos_truncados, fecha_proceso, fecha_ultima_actuacion, detail_status, detalle::text AS detalle, "
                        + "ultima_actuacion::text AS ultima_actuacion, sujetos_completos::text AS sujetos_completos "
                        + "FROM proceso_hit WHERE " + donde + " ORDER BY found_at, id_proceso LIMIT ? OFFSET ?",
                (rs, fila) -> new ResultadoDeProceso(rs.getLong("id_proceso"), rs.getString("llave_proceso"),
                        rs.getString("despacho"), rs.getString("departamento"), rs.getString("ciudad"),
                        rs.getString("categoria"), rs.getString("sujetos_procesales"), rs.getBoolean("sujetos_truncados"),
                        rs.getString("fecha_proceso"), rs.getString("fecha_ultima_actuacion"), rs.getString("detail_status"),
                        rs.getString("detalle"), rs.getString("ultima_actuacion"), rs.getString("sujetos_completos")),
                conPagina.toArray());
        return new PaginaDeResultados(elementos, total == null ? 0 : total, pagina, tamano);
    }

    private void completarSiCorresponde(UUID jobId, long idProceso) {
        jdbc.update("UPDATE proceso_hit SET detail_status = 'COMPLETO' WHERE job_id = ? AND id_proceso = ? "
                + "AND detail_status = 'PENDIENTE' AND detalle IS NOT NULL AND ultima_actuacion IS NOT NULL "
                + "AND sujetos_completos IS NOT NULL", jobId, idProceso);
    }

    private String aJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar " + valor, e);
        }
    }
}
