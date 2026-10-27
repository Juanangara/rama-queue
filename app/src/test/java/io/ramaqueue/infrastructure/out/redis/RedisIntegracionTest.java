package io.ramaqueue.infrastructure.out.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.policy.AdaptiveLimitPolicy;
import io.ramaqueue.domain.policy.BackoffPolicy;
import io.ramaqueue.domain.policy.CircuitBreakerState;
import io.ramaqueue.domain.policy.LimiteAdaptativo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class RedisIntegracionTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory conexion;
    private static StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void conectar() {
        conexion = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        conexion.afterPropertiesSet();
        redis = new StringRedisTemplate(conexion);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void cerrar() {
        conexion.destroy();
    }

    @BeforeEach
    void limpiar() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private RedisLimiteAdaptativoStore limites(long intervaloMs, int enVuelo) {
        return new RedisLimiteAdaptativoStore(redis, json,
                new AdaptiveLimitPolicy(enVuelo, 4, intervaloMs, 8 * intervaloMs, 50));
    }

    private RedisGovernor governor(RedisLimiteAdaptativoStore limites, Duration vigencia) {
        return new RedisGovernor(redis, limites, vigencia, Duration.ofSeconds(20));
    }

    // ─── Compuerta ───────────────────────────────────────────────────────────────

    @Test
    void laCompuertaSeparaLasPeticionesPorElIntervaloConfigurado() {
        RedisGovernor governor = governor(limites(200, 4), Duration.ofSeconds(60));
        // Calentamiento: la primera adquisición paga la carga de clases y del script Lua, y en una máquina cargada puede
        // retrasarse cientos de milisegundos; medir cada hueco por separado confundiría ese retraso con una separación rota.
        governor.liberar(governor.adquirir());
        List<Long> instantes = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            RedisGovernor.Permiso permiso = governor.adquirir();
            instantes.add(System.nanoTime() / 1_000_000);
            governor.liberar(permiso);
        }

        // 5 permisos = 4 huecos de 200 ms. Si la compuerta no separara, el tramo total sería de unos pocos milisegundos.
        long tramo = instantes.get(instantes.size() - 1) - instantes.get(0);
        assertThat(tramo).as("tramo total de 5 permisos con intervalo de 200 ms").isGreaterThanOrEqualTo(4 * 180);
        // Y ningún permiso se adelanta de forma grosera al anterior (margen amplio para el jitter de hilos y de red).
        for (int i = 1; i < instantes.size(); i++) {
            assertThat(instantes.get(i) - instantes.get(i - 1)).as("hueco %d", i).isGreaterThanOrEqualTo(100);
        }
    }

    @Test
    void variosHilosCompartenElMismoRitmoAunqueSeanMuchos() throws Exception {
        RedisGovernor governor = governor(limites(150, 4), Duration.ofSeconds(60));
        List<Long> instantes = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (int h = 0; h < 8; h++) {
                futuros.add(pool.submit(() -> {
                    for (int i = 0; i < 3; i++) {
                        RedisGovernor.Permiso permiso = governor.adquirir();
                        instantes.add(System.nanoTime() / 1_000_000);
                        governor.liberar(permiso);
                    }
                }));
            }
            for (Future<?> f : futuros) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> ordenados = new ArrayList<>(instantes);
        Collections.sort(ordenados);
        assertThat(ordenados).hasSize(24);
        for (int i = 1; i < ordenados.size(); i++) {
            assertThat(ordenados.get(i) - ordenados.get(i - 1)).as("hueco %d", i).isGreaterThanOrEqualTo(130);
        }
    }

    // ─── Semáforo ────────────────────────────────────────────────────────────────

    @Test
    void elSemaforoNuncaDejaMasPeticionesEnVueloQueElLimite() throws Exception {
        RedisGovernor governor = governor(limites(1, 2), Duration.ofSeconds(60));
        AtomicInteger enVuelo = new AtomicInteger();
        AtomicInteger pico = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (int h = 0; h < 6; h++) {
                futuros.add(pool.submit(() -> {
                    RedisGovernor.Permiso permiso = governor.adquirir();
                    int ahora = enVuelo.incrementAndGet();
                    pico.accumulateAndGet(ahora, Math::max);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    enVuelo.decrementAndGet();
                    governor.liberar(permiso);
                }));
            }
            for (Future<?> f : futuros) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(pico.get()).isEqualTo(2);
        assertThat(governor.enVuelo()).isZero();
    }

    @Test
    void elPermisoDeUnWorkerMuertoCaducaYNoRetieneAlResto() throws Exception {
        RedisGovernor governor = governor(limites(1, 1), Duration.ofMillis(400));
        governor.adquirir(); // este permiso nunca se libera: el worker «murió»

        long inicio = System.nanoTime();
        RedisGovernor.Permiso segundo = governor.adquirir();
        long esperaMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(segundo).isNotNull();
        assertThat(esperaMs).isBetween(200L, 3_000L);
    }

    @Test
    void siSeAcabaLaEsperaMaximaSeLanzaUnaExcepcionYNoSeDejaUnPermisoHuerfano() {
        RedisLimiteAdaptativoStore limites = limites(1, 1);
        RedisGovernor tomador = new RedisGovernor(redis, limites, Duration.ofSeconds(60), Duration.ofSeconds(20));
        RedisGovernor impaciente = new RedisGovernor(redis, limites, Duration.ofSeconds(60), Duration.ofMillis(300));
        tomador.adquirir();

        assertThatThrownBy(impaciente::adquirir).isInstanceOf(RedisGovernor.GovernorSaturadoException.class);
        assertThat(tomador.enVuelo()).isEqualTo(1);
    }

    // ─── Límite adaptativo ───────────────────────────────────────────────────────

    @Test
    void elLimiteAdaptativoSePersisteYReaccionaAlBloqueo() {
        RedisLimiteAdaptativoStore limites = limites(1_000, 2);
        assertThat(limites.leer()).isEqualTo(new LimiteAdaptativo(2, 1_000, 0));

        limites.alBloqueo();

        assertThat(limites.leer()).isEqualTo(new LimiteAdaptativo(1, 2_000, 0));
        limites.alExito();
        assertThat(limites.leer().rachaDeExitos()).isEqualTo(1);
        limites.reiniciar();
        assertThat(limites.leer().maxEnVuelo()).isEqualTo(2);
    }

    @Test
    void despuesDeUnBloqueoElGovernorUsaElNuevoIntervalo() {
        RedisLimiteAdaptativoStore limites = limites(100, 4);
        RedisGovernor governor = governor(limites, Duration.ofSeconds(60));
        limites.alBloqueo();
        limites.alBloqueo(); // intervalo 400 ms, 1 en vuelo
        List<Long> instantes = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            RedisGovernor.Permiso permiso = governor.adquirir();
            instantes.add(System.nanoTime() / 1_000_000);
            governor.liberar(permiso);
        }

        assertThat(instantes.get(1) - instantes.get(0)).isGreaterThanOrEqualTo(380);
        assertThat(instantes.get(2) - instantes.get(1)).isGreaterThanOrEqualTo(380);
    }

    // ─── Circuit breaker ─────────────────────────────────────────────────────────

    @Test
    void elEstadoDelBreakerSobreviveAlViajeAJsonConSusFechas() {
        RedisCircuitBreakerStore store = new RedisCircuitBreakerStore(redis, json);
        assertThat(store.leer()).isEqualTo(CircuitBreakerState.cerrado());

        Instant ahora = Instant.parse("2026-10-26T12:00:00Z");
        CircuitBreakerState abierto = CircuitBreakerState.cerrado().alBloqueo(ahora, BackoffPolicy.porDefecto());
        store.guardar(abierto);

        assertThat(store.leer()).isEqualTo(abierto);
        assertThat(store.leer().abiertoHasta()).isEqualTo(ahora.plus(Duration.ofMinutes(2)));
        store.reiniciar();
        assertThat(store.leer().estado()).isEqualTo(CircuitBreakerState.Estado.CERRADO);
    }

    // ─── Caché de listados ───────────────────────────────────────────────────────

    @Test
    void laCacheGuardaLosListadosYCuentaAciertosYFallos() {
        SimpleMeterRegistry metricas = new SimpleMeterRegistry();
        RedisCatalogCache cache = new RedisCatalogCache(redis, json, Duration.ofHours(24), metricas);
        List<ItemListado> lista = List.of(new ItemListado("1100133", "JUZGADO ADMINISTRATIVO"));

        assertThat(cache.obtener(Nivel.MUNICIPIO, "11001")).isEmpty();
        cache.guardar(Nivel.MUNICIPIO, "11001", lista);
        assertThat(cache.obtener(Nivel.MUNICIPIO, "11001")).isEqualTo(Optional.of(lista));
        assertThat(cache.obtener(Nivel.ENTIDAD, "11001")).isEmpty();

        assertThat(metricas.get("rama.catalog.cache").tag("resultado", "acierto").counter().count()).isEqualTo(1.0);
        assertThat(metricas.get("rama.catalog.cache").tag("resultado", "fallo").counter().count()).isEqualTo(2.0);
        assertThat(redis.getExpire("cat:MUNICIPIO:11001")).isPositive();
    }
}
