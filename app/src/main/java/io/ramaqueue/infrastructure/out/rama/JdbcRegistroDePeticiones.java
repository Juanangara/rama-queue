package io.ramaqueue.infrastructure.out.rama;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Inserta en {@code request_log}. Toma el job y el ítem del MDC que fija el consumidor. Nunca interrumpe una petición. */
public class JdbcRegistroDePeticiones implements RegistroDePeticiones {

    private static final Logger log = LoggerFactory.getLogger(JdbcRegistroDePeticiones.class);

    private final JdbcTemplate jdbc;

    public JdbcRegistroDePeticiones(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(String endpoint, Integer estadoHttp, String resultado, long duracionMs) {
        try {
            jdbc.update("INSERT INTO request_log (job_id, work_item_id, endpoint, http_status, outcome, duration_ms) "
                            + "VALUES (?,?,?,?,?,?)",
                    uuid(MDC.get("jobId")), uuid(MDC.get("itemId")), endpoint, estadoHttp, resultado, (int) duracionMs);
        } catch (RuntimeException e) {
            log.debug("No se pudo registrar la petición a {}: {}", endpoint, e.getMessage());
        }
    }

    private static UUID uuid(String valor) {
        try {
            return valor == null || valor.isBlank() ? null : UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
