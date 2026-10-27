package io.ramaqueue.infrastructure.out.persistence;

import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.GrupoDeCola;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.WorkItem;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class JdbcWorkItemRepository implements WorkItemRepository {

    private static final String COLUMNAS = "id, job_id, parent_id, type, level, codigo, codigo_label, pagina, id_proceso, "
            + "status, attempts, priority, published_at, locked_at, last_error, total_registros, total_paginas";


    private static final RowMapper<WorkItem> MAPEADOR = (rs, fila) -> new WorkItem(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            TipoTrabajo.valueOf(rs.getString("type")),
            rs.getString("level") == null ? null : Nivel.valueOf(rs.getString("level")),
            rs.getString("codigo"),
            rs.getString("codigo_label"),
            rs.getInt("pagina"),
            (Long) rs.getObject("id_proceso"),
            EstadoTrabajo.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("priority"),
            Tiempo.deBd(rs.getObject("published_at", OffsetDateTime.class)),
            Tiempo.deBd(rs.getObject("locked_at", OffsetDateTime.class)),
            rs.getString("last_error"),
            (Integer) rs.getObject("total_registros"),
            (Integer) rs.getObject("total_paginas"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;

    public JdbcWorkItemRepository(JdbcTemplate jdbc, PlatformTransactionManager administrador) {
        this.jdbc = jdbc;
        this.transaccion = new TransactionTemplate(administrador);
    }

    @Override
    public int insertarNuevos(Collection<WorkItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        List<WorkItem> lista = List.copyOf(items);
        int[] filas = jdbc.batchUpdate(
                "INSERT INTO work_item (" + COLUMNAS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        WorkItem w = lista.get(i);
                        ps.setObject(1, w.id());
                        ps.setObject(2, w.jobId());
                        ps.setObject(3, w.parentId());
                        ps.setString(4, w.tipo().name());
                        ps.setString(5, w.nivel() == null ? null : w.nivel().name());
                        ps.setString(6, w.codigo());
                        ps.setString(7, w.etiqueta());
                        ps.setInt(8, w.pagina());
                        if (w.idProceso() == null) {
                            ps.setNull(9, Types.BIGINT);
                        } else {
                            ps.setLong(9, w.idProceso());
                        }
                        ps.setString(10, w.estado().name());
                        ps.setInt(11, w.intentos());
                        ps.setInt(12, w.prioridad());
                        ps.setObject(13, Tiempo.aBd(w.publicadoEn()));
                        ps.setObject(14, Tiempo.aBd(w.bloqueadoEn()));
                        ps.setString(15, w.ultimoError());
                        setEntero(ps, 16, w.totalRegistros());
                        setEntero(ps, 17, w.totalPaginas());
                    }

                    @Override
                    public int getBatchSize() {
                        return lista.size();
                    }
                });
        int nuevos = 0;
        for (int f : filas) {
            // Con ON CONFLICT DO NOTHING el driver puede informar SUCCESS_NO_INFO (-2) cuando sí insertó.
            if (f > 0 || f == java.sql.Statement.SUCCESS_NO_INFO) {
                nuevos++;
            }
        }
        return nuevos;
    }

    @Override
    public Optional<WorkItem> buscar(UUID id) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM work_item WHERE id = ?", MAPEADOR, id).stream().findFirst();
    }

    @Override
    public Optional<WorkItem> reclamar(UUID id, Instant ahora, boolean esReentrega, Duration obsolescencia) {
        return transaccion.execute(estado -> {
            Optional<WorkItem> actual = jdbc.query(
                    "SELECT " + COLUMNAS + " FROM work_item WHERE id = ? FOR UPDATE", MAPEADOR, id).stream().findFirst();
            if (actual.isEmpty() || actual.get().esTerminal()) {
                return Optional.empty();
            }
            WorkItem item = actual.get();
            if (item.estado() == EstadoTrabajo.IN_PROGRESS) {
                boolean reclamoObsoleto = item.bloqueadoEn() == null
                        || item.bloqueadoEn().isBefore(ahora.minus(obsolescencia));
                if (!esReentrega && !reclamoObsoleto) {
                    return Optional.empty(); // otro worker lo está procesando ahora mismo
                }
            }
            WorkItem reclamado = item.iniciar(ahora);
            jdbc.update("UPDATE work_item SET status = 'IN_PROGRESS', locked_at = ?, updated_at = ? WHERE id = ?",
                    Tiempo.aBd(ahora), Tiempo.aBd(ahora), id);
            return Optional.of(reclamado);
        });
    }

    @Override
    public boolean confirmar(WorkItem reclamado, WorkItem resultado) {
        int filas = jdbc.update(
                "UPDATE work_item SET status = ?, attempts = ?, published_at = ?, locked_at = ?, last_error = ?, "
                        + "total_registros = ?, total_paginas = ?, updated_at = now() "
                        + "WHERE id = ? AND status = 'IN_PROGRESS' AND locked_at = ?",
                resultado.estado().name(), resultado.intentos(), Tiempo.aBd(resultado.publicadoEn()),
                Tiempo.aBd(resultado.bloqueadoEn()), resultado.ultimoError(), resultado.totalRegistros(),
                resultado.totalPaginas(), resultado.id(), Tiempo.aBd(reclamado.bloqueadoEn()));
        return filas == 1;
    }

    @Override
    public void guardar(WorkItem w) {
        jdbc.update(
                "UPDATE work_item SET status = ?, attempts = ?, published_at = ?, locked_at = ?, last_error = ?, "
                        + "total_registros = ?, total_paginas = ?, updated_at = now() WHERE id = ?",
                w.estado().name(), w.intentos(), Tiempo.aBd(w.publicadoEn()), Tiempo.aBd(w.bloqueadoEn()),
                w.ultimoError(), w.totalRegistros(), w.totalPaginas(), w.id());
    }

    @Override
    public List<PendientesDeTrabajo> bloquearCandidatosDePublicacion(GrupoDeCola grupo, int porTrabajo) {
        // FOR UPDATE no se permite junto a una función de ventana: primero se eligen los ids por trabajo y luego se bloquean.
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY job_id ORDER BY priority DESC, created_at, id) AS rn "
                        + "FROM work_item WHERE status = 'PENDING' AND published_at IS NULL AND type IN " + tipos(grupo) + ") t "
                        + "WHERE rn <= ?", UUID.class, porTrabajo);
        if (ids.isEmpty()) {
            return List.of();
        }
        // Se vuelve a comprobar el estado: otro relay pudo publicarlos entre una consulta y otra.
        List<WorkItem> bloqueados = jdbc.query(conexion -> {
            PreparedStatement ps = conexion.prepareStatement("SELECT " + COLUMNAS + " FROM work_item WHERE id = ANY (?) "
                    + "AND status = 'PENDING' AND published_at IS NULL ORDER BY priority DESC, created_at, id FOR UPDATE SKIP LOCKED");
            ps.setArray(1, conexion.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, MAPEADOR);
        if (bloqueados.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<WorkItem>> porTrabajoMapa = new java.util.LinkedHashMap<>();
        for (WorkItem item : bloqueados) {
            porTrabajoMapa.computeIfAbsent(item.jobId(), k -> new ArrayList<>()).add(item);
        }
        Object[] jobIds = porTrabajoMapa.keySet().toArray();
        Map<UUID, Long> servicio = new java.util.HashMap<>();
        jdbc.query(conexion -> {
            PreparedStatement ps = conexion.prepareStatement("SELECT job_id, count(*) AS n FROM work_item WHERE job_id = ANY (?) "
                    + "AND status IN ('IN_PROGRESS','DONE','EXPANDED','EXCEEDED','FAILED') GROUP BY job_id");
            ps.setArray(1, conexion.createArrayOf("uuid", jobIds));
            return ps;
        }, rs -> {
            servicio.put(rs.getObject("job_id", UUID.class), rs.getLong("n"));
        });
        Map<UUID, Instant> creados = new java.util.HashMap<>();
        jdbc.query(conexion -> {
            PreparedStatement ps = conexion.prepareStatement("SELECT id, created_at FROM search_job WHERE id = ANY (?)");
            ps.setArray(1, conexion.createArrayOf("uuid", jobIds));
            return ps;
        }, rs -> {
            creados.put(rs.getObject("id", UUID.class), Tiempo.deBd(rs.getObject("created_at", OffsetDateTime.class)));
        });

        List<PendientesDeTrabajo> resultado = new ArrayList<>();
        porTrabajoMapa.forEach((jobId, lista) -> resultado.add(
                new PendientesDeTrabajo(jobId, creados.get(jobId), servicio.getOrDefault(jobId, 0L), lista)));
        return resultado;
    }

    @Override
    public long publicadosEnElBroker(GrupoDeCola grupo) {
        Long cuenta = jdbc.queryForObject("SELECT count(*) FROM work_item WHERE status = 'PENDING' AND published_at IS NOT NULL "
                + "AND type IN " + tipos(grupo), Long.class);
        return cuenta == null ? 0 : cuenta;
    }

    private static String tipos(GrupoDeCola grupo) {
        return grupo.tipos().stream().map(t -> "'" + t.name() + "'").collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    @Override
    public void marcarPublicados(Collection<UUID> ids, Instant ahora) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE work_item SET published_at = ?, updated_at = now() WHERE id = ANY (?)");
            ps.setObject(1, Tiempo.aBd(ahora));
            ps.setArray(2, conexion.createArrayOf("uuid", ids.toArray()));
            return ps;
        });
    }

    @Override
    public List<WorkItem> bloquearAtascados(Instant reclamadosAntesDe, int limite) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM work_item WHERE status = 'IN_PROGRESS' AND locked_at < ? "
                + "ORDER BY locked_at LIMIT ? FOR UPDATE SKIP LOCKED", MAPEADOR, Tiempo.aBd(reclamadosAntesDe), limite);
    }

    @Override
    public int marcarParaRepublicar(boolean deBusqueda, Instant publicadosAntesDe, Instant ahora) {
        return jdbc.update("UPDATE work_item SET published_at = NULL, updated_at = ? WHERE status = 'PENDING' "
                + "AND published_at < ? AND type IN " + tipos(deBusqueda ? GrupoDeCola.BUSQUEDA : GrupoDeCola.DETALLE),
                Tiempo.aBd(ahora), Tiempo.aBd(publicadosAntesDe));
    }

    @Override
    public ConteosDeTrabajo conteos(UUID jobId) {
        Map<EstadoTrabajo, Long> porEstado = new EnumMap<>(EstadoTrabajo.class);
        jdbc.query("SELECT status, count(*) AS cuantos FROM work_item WHERE job_id = ? GROUP BY status",
                rs -> {
                    porEstado.put(EstadoTrabajo.valueOf(rs.getString("status")), rs.getLong("cuantos"));
                }, jobId);
        return new ConteosDeTrabajo(porEstado);
    }

    @Override
    public boolean hayActivos(UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM work_item WHERE job_id = ? AND status IN ('PENDING','IN_PROGRESS'))",
                Boolean.class, jobId));
    }

    @Override
    public List<NodoDeArbol> arbol(UUID jobId, int limite) {
        // Las páginas 2..N de una búsqueda cuelgan de su página 1; aquí se agregan como «páginas terminadas» del nodo.
        return jdbc.query("SELECT n.id, n.parent_id, n.type, n.level, n.codigo, n.codigo_label, n.status, n.attempts, "
                        + "n.total_registros, n.total_paginas, n.last_error, "
                        + "(SELECT count(*) FROM work_item p WHERE p.parent_id = n.id AND p.type = n.type AND p.pagina > 1 "
                        + "AND p.status NOT IN ('PENDING','IN_PROGRESS')) AS paginas_terminadas "
                        + "FROM work_item n WHERE n.job_id = ? AND n.type IN ('SEARCH','LIST') AND n.pagina = 1 "
                        + "ORDER BY n.created_at, n.id LIMIT ?",
                (rs, fila) -> new NodoDeArbol(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                        TipoTrabajo.valueOf(rs.getString("type")),
                        rs.getString("level") == null ? null : Nivel.valueOf(rs.getString("level")),
                        rs.getString("codigo"), rs.getString("codigo_label"), EstadoTrabajo.valueOf(rs.getString("status")),
                        rs.getInt("attempts"), (Integer) rs.getObject("total_registros"), (Integer) rs.getObject("total_paginas"),
                        rs.getLong("paginas_terminadas"), rs.getString("last_error")),
                jobId, limite);
    }

    @Override
    public int cancelarPendientes(UUID jobId) {
        return jdbc.update("UPDATE work_item SET status = 'CANCELLED', updated_at = now() WHERE job_id = ? AND status = 'PENDING'",
                jobId);
    }

    @Override
    public List<WorkItem> fallidos(UUID jobId) {
        if (jobId == null) {
            return jdbc.query("SELECT " + COLUMNAS + " FROM work_item WHERE status = 'FAILED' ORDER BY created_at", MAPEADOR);
        }
        return jdbc.query("SELECT " + COLUMNAS + " FROM work_item WHERE status = 'FAILED' AND job_id = ? ORDER BY created_at",
                MAPEADOR, jobId);
    }

    private static void setEntero(PreparedStatement ps, int indice, Integer valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.INTEGER);
        } else {
            ps.setInt(indice, valor);
        }
    }

    /** Solo para pruebas: todos los ítems de un job. */
    List<WorkItem> todosDe(UUID jobId) {
        return new ArrayList<>(jdbc.query("SELECT " + COLUMNAS + " FROM work_item WHERE job_id = ?", MAPEADOR, jobId));
    }
}
