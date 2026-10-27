package io.ramaqueue.infrastructure.in.scheduler;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Mandos de operación sobre los consumidores, guardados en Redis para que valgan en TODOS los procesos: con la API y los
 * workers en servicios distintos, un botón del dashboard debe llegar a los workers. Cada worker los lee en su ciclo del
 * {@link VigilanteDelBreaker}.
 */
@Component
public class ControlDeConsumidores {

    static final String CLAVE_PAUSA = "ctl:pausa";
    static final String CLAVE_CAIDAS = "ctl:caidas";
    private static final String PREFIJO_CONCURRENCIA = "ctl:concurrencia:";

    private final StringRedisTemplate redis;

    public ControlDeConsumidores(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void pausar() {
        redis.opsForValue().set(CLAVE_PAUSA, "1");
    }

    public void reanudar() {
        redis.delete(CLAVE_PAUSA);
    }

    public boolean estaPausado() {
        return Boolean.TRUE.equals(redis.hasKey(CLAVE_PAUSA));
    }

    /** Concurrencia pedida para un listener (p. ej. {@code search-listener}); vacío si nadie la cambió. */
    public Optional<Integer> concurrenciaDeseada(String listenerId) {
        String valor = redis.opsForValue().get(PREFIJO_CONCURRENCIA + listenerId);
        return valor == null ? Optional.empty() : Optional.of(Integer.parseInt(valor));
    }

    public void fijarConcurrencia(String listenerId, int consumidores) {
        redis.opsForValue().set(PREFIJO_CONCURRENCIA + listenerId, String.valueOf(Math.max(1, Math.min(consumidores, 32))));
    }

    /** Demo: deja un «billete» para que exactamente un worker se caiga en su siguiente ciclo. */
    public void solicitarCaidaDeUnWorker() {
        redis.opsForList().rightPush(CLAVE_CAIDAS, "1");
    }

    /** {@code true} para el primer worker que reclama el billete. */
    public boolean reclamarCaida() {
        return redis.opsForList().leftPop(CLAVE_CAIDAS) != null;
    }

    public void reiniciar() {
        redis.delete(CLAVE_PAUSA);
        redis.delete(CLAVE_CAIDAS);
        var claves = redis.keys(PREFIJO_CONCURRENCIA + "*");
        if (claves != null && !claves.isEmpty()) {
            redis.delete(claves);
        }
    }
}
