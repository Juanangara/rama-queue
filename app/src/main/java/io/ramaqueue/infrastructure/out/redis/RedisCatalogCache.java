package io.ramaqueue.infrastructure.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Caché de los listados de la Rama (entidades, especialidades y despachos): casi no cambian y cada uno ahorra una petición. */
public class RedisCatalogCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration vigencia;
    private final Counter aciertos;
    private final Counter fallos;

    public RedisCatalogCache(StringRedisTemplate redis, ObjectMapper json, Duration vigencia, MeterRegistry metricas) {
        this.redis = redis;
        this.json = json;
        this.vigencia = vigencia;
        this.aciertos = Counter.builder("rama.catalog.cache").tag("resultado", "acierto").register(metricas);
        this.fallos = Counter.builder("rama.catalog.cache").tag("resultado", "fallo").register(metricas);
    }

    public Optional<List<ItemListado>> obtener(Nivel nivelPadre, String codigoPadre) {
        String valor = redis.opsForValue().get(clave(nivelPadre, codigoPadre));
        if (valor == null) {
            fallos.increment();
            return Optional.empty();
        }
        try {
            List<ItemListado> lista = json.readValue(valor, new TypeReference<>() {
            });
            aciertos.increment();
            return Optional.of(lista);
        } catch (JsonProcessingException e) {
            fallos.increment();
            return Optional.empty();
        }
    }

    public void guardar(Nivel nivelPadre, String codigoPadre, List<ItemListado> lista) {
        try {
            redis.opsForValue().set(clave(nivelPadre, codigoPadre), json.writeValueAsString(lista), vigencia);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el listado", e);
        }
    }

    private static String clave(Nivel nivelPadre, String codigoPadre) {
        return "cat:" + nivelPadre.name() + ":" + codigoPadre.trim();
    }
}
