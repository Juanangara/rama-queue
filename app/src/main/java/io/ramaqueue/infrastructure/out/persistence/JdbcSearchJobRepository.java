package io.ramaqueue.infrastructure.out.persistence;

import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.JobStatus;
import io.ramaqueue.domain.model.SearchJob;
import io.ramaqueue.domain.model.TipoPersona;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcSearchJobRepository implements SearchJobRepository {

    private static final String COLUMNAS = "id, nombre, tipo_persona, categoria, status, error, created_at, finished_at";

    private static final RowMapper<SearchJob> MAPEADOR = (rs, fila) -> new SearchJob(
            rs.getObject("id", UUID.class),
            rs.getString("nombre"),
            TipoPersona.desdeCodigo(rs.getString("tipo_persona")),
            Categoria.desdeCodigo(rs.getString("categoria")),
            JobStatus.valueOf(rs.getString("status")),
            Tiempo.deBd(rs.getObject("created_at", OffsetDateTime.class)),
            Tiempo.deBd(rs.getObject("finished_at", OffsetDateTime.class)),
            rs.getString("error"));

    private final JdbcTemplate jdbc;

    public JdbcSearchJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(SearchJob job) {
        jdbc.update("INSERT INTO search_job (id, nombre, tipo_persona, categoria, status, error, created_at, started_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                job.id(), job.nombre(), job.tipoPersona().codigo(), job.categoria().codigo(), job.estado().name(),
                job.error(), Tiempo.aBd(job.creadoEn()), Tiempo.aBd(job.creadoEn()));
    }

    @Override
    public Optional<SearchJob> buscar(UUID id) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM search_job WHERE id = ?", MAPEADOR, id).stream().findFirst();
    }

    @Override
    public List<SearchJob> recientes(int limite) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM search_job ORDER BY created_at DESC LIMIT ?", MAPEADOR, limite);
    }

    @Override
    public boolean cancelarSiCorriendo(UUID id, Instant ahora) {
        return jdbc.update("UPDATE search_job SET status = 'CANCELLED', finished_at = ? WHERE id = ? AND status = 'RUNNING'",
                Tiempo.aBd(ahora), id) == 1;
    }

    @Override
    public boolean reabrirSiTerminadoConProblemas(UUID id) {
        return jdbc.update("UPDATE search_job SET status = 'RUNNING', finished_at = NULL, error = NULL "
                + "WHERE id = ? AND status IN ('PARTIAL','FAILED')", id) == 1;
    }

    @Override
    public boolean finalizarSiCorriendo(UUID id, JobStatus estado, Instant ahora, String error) {
        return jdbc.update("UPDATE search_job SET status = ?, finished_at = ?, error = ? WHERE id = ? AND status = 'RUNNING'",
                estado.name(), Tiempo.aBd(ahora), error, id) == 1;
    }
}
