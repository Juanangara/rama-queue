package io.ramaqueue.infrastructure.out.redis;

import io.ramaqueue.domain.policy.LimiteAdaptativo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * El «gobernador» de tráfico hacia la Rama Judicial. Cada petición necesita dos cosas, compartidas por todos los
 * workers de todos los procesos:
 * <ol>
 *   <li>un permiso del <b>semáforo</b> de peticiones en vuelo (ZSET con expiración: si un worker muere, su permiso
 *       caduca solo y no retiene a los demás);</li>
 *   <li>su turno en la <b>compuerta</b> de separación mínima entre peticiones.</li>
 * </ol>
 * Escalar de 1 a N workers no cambia la presión sobre el servidor: los que sobran esperan su turno.
 */
public class RedisGovernor {

    static final String CLAVE_SEMAFORO = "rl:inflight";
    static final String CLAVE_COMPUERTA = "rl:gate";

    private static final DefaultRedisScript<Long> SEMAFORO = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local ahora = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ahora)
            if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[1]) then
              redis.call('ZADD', KEYS[1], ahora + tonumber(ARGV[2]), ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPUERTA = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local ahora = t[1] * 1000 + math.floor(t[2] / 1000)
            local siguiente = tonumber(redis.call('GET', KEYS[1]) or '0')
            local turno = math.max(ahora, siguiente)
            redis.call('SET', KEYS[1], turno + tonumber(ARGV[1]), 'PX', 3600000)
            return turno - ahora
            """, Long.class);

    /** Permiso concedido: debe devolverse con {@link #liberar(Permiso)}. */
    public record Permiso(String id, long esperaMs) {
    }

    public static class GovernorSaturadoException extends RuntimeException {
        public GovernorSaturadoException(String mensaje) {
            super(mensaje);
        }
    }

    private final StringRedisTemplate redis;
    private final RedisLimiteAdaptativoStore limites;
    private final Duration vigenciaDelPermiso;
    private final Duration esperaMaxima;

    public RedisGovernor(StringRedisTemplate redis, RedisLimiteAdaptativoStore limites,
                         Duration vigenciaDelPermiso, Duration esperaMaxima) {
        this.redis = redis;
        this.limites = limites;
        this.vigenciaDelPermiso = vigenciaDelPermiso;
        this.esperaMaxima = esperaMaxima;
    }

    /** Bloquea hasta tener permiso y turno. Si el hilo se interrumpe, devuelve el permiso y relanza la interrupción. */
    public Permiso adquirir() {
        String id = UUID.randomUUID().toString();
        long inicio = System.nanoTime();
        long limite = inicio + esperaMaxima.toNanos();
        try {
            while (!tomarPermiso(id)) {
                if (System.nanoTime() > limite) {
                    throw new GovernorSaturadoException("No se obtuvo permiso en " + esperaMaxima);
                }
                dormir(40 + ThreadLocalRandom.current().nextLong(60));
            }
            LimiteAdaptativo actual = limites.leer();
            Long espera = redis.execute(COMPUERTA, List.of(CLAVE_COMPUERTA), String.valueOf(actual.intervaloMs()));
            if (espera != null && espera > 0) {
                dormir(espera);
            }
        } catch (RuntimeException e) {
            liberarPorId(id);
            throw e;
        }
        return new Permiso(id, (System.nanoTime() - inicio) / 1_000_000);
    }

    public void liberar(Permiso permiso) {
        liberarPorId(permiso.id());
    }

    /** Cuántas peticiones están en vuelo ahora mismo (para métricas y el dashboard). */
    public long enVuelo() {
        Long n = redis.opsForZSet().zCard(CLAVE_SEMAFORO);
        return n == null ? 0 : n;
    }

    public void reiniciar() {
        redis.delete(List.of(CLAVE_SEMAFORO, CLAVE_COMPUERTA));
    }

    private boolean tomarPermiso(String id) {
        LimiteAdaptativo actual = limites.leer();
        Long concedido = redis.execute(SEMAFORO, List.of(CLAVE_SEMAFORO),
                String.valueOf(actual.maxEnVuelo()), String.valueOf(vigenciaDelPermiso.toMillis()), id);
        return concedido != null && concedido == 1L;
    }

    private void liberarPorId(String id) {
        redis.opsForZSet().remove(CLAVE_SEMAFORO, id);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido mientras esperaba su turno hacia la Rama Judicial", e);
        }
    }
}
