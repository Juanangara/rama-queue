package io.ramaqueue.infrastructure.in.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.ramaqueue.application.port.in.ReencolarFallidos;
import io.ramaqueue.application.port.out.BrokerInspector;
import io.ramaqueue.application.port.out.BrokerInspector.ResumenDeCola;
import io.ramaqueue.application.port.out.DlqInspector;
import io.ramaqueue.application.port.out.DlqInspector.EntradaDlq;
import io.ramaqueue.domain.policy.CircuitBreakerState;
import io.ramaqueue.domain.policy.LimiteAdaptativo;
import io.ramaqueue.infrastructure.config.RamaProperties;
import io.ramaqueue.infrastructure.in.scheduler.ControlDeConsumidores;
import io.ramaqueue.infrastructure.out.redis.RedisCircuitBreakerStore;
import io.ramaqueue.infrastructure.out.redis.RedisGovernor;
import io.ramaqueue.infrastructure.out.redis.RedisLimiteAdaptativoStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que el dashboard muestra de la infraestructura: colas, gobernador, circuit breaker, DLQ y la huella de peticiones.
 * Es un modelo de lectura operativo: lee directamente los adaptadores en lugar de pasar por casos de uso.
 */
@RestController
@RequestMapping("/api/infra")
@Profile("api")
class InfraController {

    private final BrokerInspector broker;
    private final DlqInspector dlq;
    private final ReencolarFallidos reencolar;
    private final RedisGovernor governor;
    private final RedisLimiteAdaptativoStore limites;
    private final RedisCircuitBreakerStore breaker;
    private final ControlDeConsumidores control;
    private final MeterRegistry metricas;
    private final JdbcTemplate jdbc;
    private final RamaProperties propiedades;
    private final SseHub hub;
    private final Clock reloj;

    InfraController(BrokerInspector broker, DlqInspector dlq, ReencolarFallidos reencolar, RedisGovernor governor,
                    RedisLimiteAdaptativoStore limites, RedisCircuitBreakerStore breaker, ControlDeConsumidores control,
                    MeterRegistry metricas, JdbcTemplate jdbc, RamaProperties propiedades, SseHub hub, Clock reloj) {
        this.broker = broker;
        this.dlq = dlq;
        this.reencolar = reencolar;
        this.governor = governor;
        this.limites = limites;
        this.breaker = breaker;
        this.control = control;
        this.metricas = metricas;
        this.jdbc = jdbc;
        this.propiedades = propiedades;
        this.hub = hub;
        this.reloj = reloj;
    }

    @GetMapping("/colas")
    List<ResumenDeCola> colas() {
        return broker.colas();
    }

    /** Estado del gobernador de tráfico: cuánto se deja pasar hacia la Rama Judicial y por qué. */
    @GetMapping("/governor")
    Map<String, Object> governor() {
        Instant ahora = reloj.instant();
        LimiteAdaptativo limite = limites.leer();
        CircuitBreakerState estado = breaker.leer().evaluar(ahora);

        Map<String, Object> limiteJson = new LinkedHashMap<>();
        limiteJson.put("maxEnVuelo", limite.maxEnVuelo());
        limiteJson.put("intervaloMs", limite.intervaloMs());
        limiteJson.put("rachaDeExitos", limite.rachaDeExitos());
        limiteJson.put("topeEnVuelo", propiedades.governor().maxInFlight());
        limiteJson.put("intervaloBaseMs", propiedades.governor().minIntervalMs());

        Map<String, Object> breakerJson = new LinkedHashMap<>();
        breakerJson.put("estado", estado.estado().name());
        breakerJson.put("restanteSegundos", breaker.leer().restante(ahora).toSeconds());
        breakerJson.put("aperturasSeguidas", estado.aperturasSeguidas());
        breakerJson.put("abiertoHasta", estado.abiertoHasta());

        Map<String, Object> cache = new LinkedHashMap<>();
        double aciertos = contador("acierto");
        double fallos = contador("fallo");
        cache.put("aciertos", aciertos);
        cache.put("fallos", fallos);
        cache.put("tasaDeAciertos", aciertos + fallos == 0 ? null : aciertos / (aciertos + fallos));

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("enVuelo", governor.enVuelo());
        respuesta.put("limite", limiteJson);
        respuesta.put("breaker", breakerJson);
        respuesta.put("cacheDeListados", cache);
        respuesta.put("consumidoresPausados", control.estaPausado());
        return respuesta;
    }

    @GetMapping("/dlq")
    List<EntradaDlq> dlq(@RequestParam(defaultValue = "50") int maximo) {
        return dlq.examinar(Math.min(Math.max(1, maximo), 500));
    }

    /** Reencola todos los ítems fallidos de todos los jobs y limpia las DLQ. */
    @PostMapping("/dlq/requeue")
    ReencolarFallidos.Resultado reencolarTodo() {
        return reencolar.reencolar(null);
    }

    /** Cuántas peticiones salieron hacia la Rama Judicial, en cubos de 10 s: la prueba de que se fue amable con el servidor. */
    @GetMapping("/peticiones")
    List<Map<String, Object>> peticiones(@RequestParam(defaultValue = "10") int minutos) {
        return jdbc.queryForList(
                "SELECT date_trunc('minute', ts) + floor(extract(second from ts) / 10) * interval '10 seconds' AS cubo, "
                        + "count(*) AS total, count(*) FILTER (WHERE outcome = 'bloqueado') AS bloqueadas, "
                        + "count(*) FILTER (WHERE outcome NOT IN ('ok','excede','bloqueado')) AS errores, "
                        + "round(avg(duration_ms)) AS duracion_media_ms "
                        + "FROM request_log WHERE ts > now() - make_interval(mins => ?) GROUP BY 1 ORDER BY 1",
                Math.min(Math.max(1, minutos), 120));
    }

    /** Todo lo que ocurre en el sistema, de todos los jobs. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter eventos() {
        return hub.suscribir(null);
    }

    private double contador(String resultado) {
        Counter c = metricas.find("rama.catalog.cache").tag("resultado", resultado).counter();
        return c == null ? 0 : c.count();
    }
}
