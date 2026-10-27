package io.ramaqueue.infrastructure.in.scheduler;

import io.ramaqueue.application.port.in.MantenerCola;
import io.ramaqueue.application.port.in.PublicarPendientes;
import io.ramaqueue.infrastructure.config.RamaProperties;
import io.ramaqueue.infrastructure.in.web.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Tareas que mantienen vivo el sistema. En el rol {@code api}: el relay del outbox y el watchdog. En los roles de worker:
 * el vigilante del circuit breaker. Un fallo en una tarea se registra y la tarea sigue.
 */
@Configuration
@EnableScheduling
class TareasPeriodicas implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TareasPeriodicas.class);

    private final Environment entorno;
    private final RamaProperties propiedades;
    private final PublicarPendientes relay;
    private final MantenerCola mantenimiento;
    private final ObjectProvider<VigilanteDelBreaker> vigilante;
    private final ObjectProvider<SseHub> hub;
    private final ObjectProvider<MetricasDeCola> metricas;

    TareasPeriodicas(Environment entorno, RamaProperties propiedades, PublicarPendientes relay,
                     MantenerCola mantenimiento, ObjectProvider<VigilanteDelBreaker> vigilante, ObjectProvider<SseHub> hub,
                     ObjectProvider<MetricasDeCola> metricas) {
        this.metricas = metricas;
        this.entorno = entorno;
        this.propiedades = propiedades;
        this.relay = relay;
        this.mantenimiento = mantenimiento;
        this.vigilante = vigilante;
        this.hub = hub;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registro) {
        if (entorno.acceptsProfiles(Profiles.of("api"))) {
            registro.addFixedDelayTask(seguro("relay del outbox", relay::publicarLote), propiedades.outbox().interval().toMillis());
            registro.addFixedDelayTask(seguro("watchdog", mantenimiento::liberarAtascados), propiedades.watchdog().interval().toMillis());
            registro.addFixedDelayTask(seguro("reconciliación", mantenimiento::reconciliar),
                    propiedades.watchdog().interval().multipliedBy(2).toMillis());
        }
        SseHub sse = hub.getIfAvailable();
        if (sse != null) {
            registro.addFixedDelayTask(seguro("avance en vivo", sse::emitirProgreso), 500);
            registro.addFixedDelayTask(seguro("latido SSE", sse::latido), 15_000);
        }
        MetricasDeCola m = metricas.getIfAvailable();
        if (m != null) {
            registro.addFixedDelayTask(seguro("métricas de colas", m::refrescar), 5_000);
        }
        VigilanteDelBreaker v = vigilante.getIfAvailable();
        if (v != null) {
            registro.addFixedDelayTask(seguro("vigilante del breaker", v::revisar), 1_000);
        }
    }

    private static Runnable seguro(String nombre, Runnable tarea) {
        return () -> {
            try {
                tarea.run();
            } catch (RuntimeException e) {
                log.warn("Falló la tarea periódica «{}»: {}", nombre, e.toString());
            }
        };
    }
}
