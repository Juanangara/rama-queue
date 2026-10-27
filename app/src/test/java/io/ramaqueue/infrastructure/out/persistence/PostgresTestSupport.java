package io.ramaqueue.infrastructure.out.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/** Postgres real (Testcontainers) con las migraciones aplicadas, sin levantar el contexto de Spring. */
final class PostgresTestSupport {

    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager transacciones;

    PostgresTestSupport(PostgreSQLContainer<?> contenedor) {
        this.dataSource = new DriverManagerDataSource(contenedor.getJdbcUrl(), contenedor.getUsername(), contenedor.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        this.jdbc = new JdbcTemplate(dataSource);
        this.transacciones = new DataSourceTransactionManager(dataSource);
    }

    void limpiar() {
        jdbc.execute("TRUNCATE request_log, proceso_hit, work_item, search_job CASCADE");
    }
}
