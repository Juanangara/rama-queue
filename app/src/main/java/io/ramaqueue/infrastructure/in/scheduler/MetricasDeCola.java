package io.ramaqueue.infrastructure.in.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.ramaqueue.application.port.out.BrokerInspector;
import io.ramaqueue.domain.model.EstadoTrabajo;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas agregadas para Prometheus: ítems por estado (Postgres) y estado de cada cola (API de gestión de RabbitMQ).
 * Se refrescan en una tarea periódica y los gauges leen el último valor: un scrape nunca toca la base ni el broker.
 */
@Component
@Profile("api")
public class MetricasDeCola {

    private final MeterRegistry registro;
    private final BrokerInspector broker;
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> items = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> colas = new ConcurrentHashMap<>();

    public MetricasDeCola(MeterRegistry registro, BrokerInspector broker, JdbcTemplate jdbc) {
        this.registro = registro;
        this.broker = broker;
        this.jdbc = jdbc;
        for (EstadoTrabajo estado : EstadoTrabajo.values()) {
            valor(items, "rama.workitems", "estado", estado.name(), "Ítems de trabajo por estado");
        }
    }

    public void refrescar() {
        List<Map<String, Object>> filas = jdbc.queryForList("SELECT status, count(*) AS n FROM work_item GROUP BY status");
        items.values().forEach(v -> v.set(0));
        for (Map<String, Object> fila : filas) {
            valor(items, "rama.workitems", "estado", String.valueOf(fila.get("status")), "Ítems de trabajo por estado")
                    .set(((Number) fila.get("n")).longValue());
        }
        for (BrokerInspector.ResumenDeCola cola : broker.colas()) {
            valor(colas, "rama.queue.ready", "cola", cola.nombre(), "Mensajes listos para entregar").set(cola.listos());
            valor(colas, "rama.queue.unacked", "cola", cola.nombre(), "Mensajes entregados sin confirmar").set(cola.sinConfirmar());
            valor(colas, "rama.queue.consumers", "cola", cola.nombre(), "Consumidores conectados").set(cola.consumidores());
        }
    }

    private AtomicLong valor(Map<String, AtomicLong> almacen, String metrica, String etiqueta, String valor, String descripcion) {
        return almacen.computeIfAbsent(metrica + "|" + valor, k -> {
            AtomicLong v = new AtomicLong();
            Gauge.builder(metrica, v, AtomicLong::get).tag(etiqueta, valor).description(descripcion).register(registro);
            return v;
        });
    }
}
