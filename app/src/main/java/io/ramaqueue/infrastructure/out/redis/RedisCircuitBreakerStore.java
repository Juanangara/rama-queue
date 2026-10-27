package io.ramaqueue.infrastructure.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.domain.policy.CircuitBreakerState;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Persiste en Redis el estado del circuit breaker global; la lógica de transición es del dominio. */
public class RedisCircuitBreakerStore {

    static final String CLAVE = "cb:rama";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisCircuitBreakerStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public CircuitBreakerState leer() {
        String valor = redis.opsForValue().get(CLAVE);
        if (valor == null) {
            return CircuitBreakerState.cerrado();
        }
        try {
            return json.readValue(valor, CircuitBreakerState.class);
        } catch (JsonProcessingException e) {
            return CircuitBreakerState.cerrado();
        }
    }

    public void guardar(CircuitBreakerState estado) {
        try {
            redis.opsForValue().set(CLAVE, json.writeValueAsString(estado));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el estado del breaker", e);
        }
    }

    public void reiniciar() {
        redis.delete(CLAVE);
    }
}
