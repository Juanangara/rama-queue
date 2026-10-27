package io.ramaqueue.infrastructure.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.domain.policy.AdaptiveLimitPolicy;
import io.ramaqueue.domain.policy.LimiteAdaptativo;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Guarda en Redis el estado del límite adaptativo (AIMD). La política es pura (dominio); aquí solo se persiste.
 * La lectura-modificación-escritura no es atómica a propósito: un par de éxitos perdidos no importa y evita un lock.
 */
public class RedisLimiteAdaptativoStore {

    static final String CLAVE = "aimd:state";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AdaptiveLimitPolicy politica;

    public RedisLimiteAdaptativoStore(StringRedisTemplate redis, ObjectMapper json, AdaptiveLimitPolicy politica) {
        this.redis = redis;
        this.json = json;
        this.politica = politica;
    }

    public LimiteAdaptativo leer() {
        String valor = redis.opsForValue().get(CLAVE);
        if (valor == null) {
            return politica.inicial();
        }
        try {
            return politica.acotar(json.readValue(valor, LimiteAdaptativo.class));
        } catch (JsonProcessingException e) {
            return politica.inicial();
        }
    }

    public void alExito() {
        guardar(politica.alExito(leer()));
    }

    public void alBloqueo() {
        guardar(politica.alBloqueo(leer()));
    }

    public void reiniciar() {
        redis.delete(CLAVE);
    }

    private void guardar(LimiteAdaptativo limite) {
        try {
            redis.opsForValue().set(CLAVE, json.writeValueAsString(limite));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el límite adaptativo", e);
        }
    }
}
