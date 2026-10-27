package io.ramaqueue.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.ramaqueue.application.port.in.CancelarJob;
import io.ramaqueue.application.port.in.ConsultarProgreso;
import io.ramaqueue.application.port.in.IniciarBusqueda;
import io.ramaqueue.application.port.in.MantenerCola;
import io.ramaqueue.application.port.in.ProcesarTrabajo;
import io.ramaqueue.application.port.in.PublicarPendientes;
import io.ramaqueue.application.port.in.ReencolarFallidos;
import io.ramaqueue.application.port.out.BrokerInspector;
import io.ramaqueue.application.port.out.DlqInspector;
import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.ProcesoHitRepository;
import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.application.port.out.SearchJobRepository;
import io.ramaqueue.application.port.out.UnidadDeTrabajo;
import io.ramaqueue.application.port.out.WorkItemRepository;
import io.ramaqueue.application.port.out.WorkPublisher;
import io.ramaqueue.application.service.CancelarJobService;
import io.ramaqueue.application.service.ConsultarProgresoService;
import io.ramaqueue.application.service.ConsultasDeJobService;
import io.ramaqueue.application.service.IniciarBusquedaService;
import io.ramaqueue.application.service.ProcesadorDeTrabajo;
import io.ramaqueue.application.service.PublicadorDeOutbox;
import io.ramaqueue.application.service.ReencolarFallidosService;
import io.ramaqueue.application.service.VigilanteDeCola;
import io.ramaqueue.domain.model.Territorio;
import io.ramaqueue.domain.policy.AdaptiveLimitPolicy;
import io.ramaqueue.domain.policy.BackoffPolicy;
import io.ramaqueue.domain.policy.ExpansionPlanner;
import io.ramaqueue.infrastructure.out.messaging.Colas;
import io.ramaqueue.infrastructure.out.messaging.RabbitDlqInspector;
import io.ramaqueue.infrastructure.out.messaging.RabbitEventPublisher;
import io.ramaqueue.infrastructure.out.messaging.RabbitManagementInspector;
import io.ramaqueue.infrastructure.out.messaging.RabbitTopology;
import io.ramaqueue.infrastructure.out.messaging.RabbitWorkPublisher;
import io.ramaqueue.infrastructure.out.persistence.JdbcProcesoHitRepository;
import io.ramaqueue.infrastructure.out.persistence.JdbcSearchJobRepository;
import io.ramaqueue.infrastructure.out.persistence.JdbcWorkItemRepository;
import io.ramaqueue.infrastructure.out.persistence.SpringUnidadDeTrabajo;
import io.ramaqueue.infrastructure.out.rama.BreakerGateway;
import io.ramaqueue.infrastructure.out.rama.CachingGateway;
import io.ramaqueue.infrastructure.out.rama.GovernedGateway;
import io.ramaqueue.infrastructure.out.rama.HttpRamaJudicialGateway;
import io.ramaqueue.infrastructure.out.rama.JdbcRegistroDePeticiones;
import io.ramaqueue.infrastructure.out.rama.RegistroDePeticiones;
import io.ramaqueue.infrastructure.out.redis.RedisCatalogCache;
import io.ramaqueue.infrastructure.out.redis.RedisCircuitBreakerStore;
import io.ramaqueue.infrastructure.out.redis.RedisGovernor;
import io.ramaqueue.infrastructure.out.redis.RedisLimiteAdaptativoStore;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Único lugar donde se ensamblan los puertos con sus adaptadores. Los casos de uso son clases sin Spring; aquí se les
 * pasan sus dependencias, y el gateway se arma como una cadena de decoradores:
 * {@code Caché → Governor → Breaker → HTTP}.
 */
@Configuration
@EnableConfigurationProperties(RamaProperties.class)
class MotorConfig {

    // ─── Políticas del dominio ───────────────────────────────────────────────────

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    BackoffPolicy backoffPolicy(RamaProperties p) {
        return new BackoffPolicy(p.breaker().cooldownBase(), p.breaker().factor(), p.breaker().cooldownMax(),
                p.retry().tiers(), p.retry().maxAttempts());
    }

    @Bean
    AdaptiveLimitPolicy adaptiveLimitPolicy(RamaProperties p) {
        RamaProperties.Governor g = p.governor();
        return new AdaptiveLimitPolicy(g.initialInFlight(), g.maxInFlight(), g.minIntervalMs(), g.maxIntervalMs(),
                g.successesToIncrease());
    }

    @Bean
    Territorio territorio(ObjectMapper json) {
        return TerritorioLoader.cargar(json);
    }

    @Bean
    ExpansionPlanner expansionPlanner(Territorio territorio) {
        return new ExpansionPlanner(territorio);
    }

    // ─── Persistencia ────────────────────────────────────────────────────────────

    @Bean
    UnidadDeTrabajo unidadDeTrabajo(PlatformTransactionManager transacciones) {
        return new SpringUnidadDeTrabajo(transacciones);
    }

    @Bean
    WorkItemRepository workItemRepository(JdbcTemplate jdbc, PlatformTransactionManager transacciones) {
        return new JdbcWorkItemRepository(jdbc, transacciones);
    }

    @Bean
    SearchJobRepository searchJobRepository(JdbcTemplate jdbc) {
        return new JdbcSearchJobRepository(jdbc);
    }

    @Bean
    ProcesoHitRepository procesoHitRepository(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcProcesoHitRepository(jdbc, json);
    }

    @Bean
    RegistroDePeticiones registroDePeticiones(JdbcTemplate jdbc) {
        return new JdbcRegistroDePeticiones(jdbc);
    }

    // ─── Redis ───────────────────────────────────────────────────────────────────

    @Bean
    RedisLimiteAdaptativoStore limiteAdaptativoStore(StringRedisTemplate redis, ObjectMapper json, AdaptiveLimitPolicy politica) {
        return new RedisLimiteAdaptativoStore(redis, json, politica);
    }

    @Bean
    RedisCircuitBreakerStore circuitBreakerStore(StringRedisTemplate redis, ObjectMapper json) {
        return new RedisCircuitBreakerStore(redis, json);
    }

    @Bean
    RedisGovernor redisGovernor(StringRedisTemplate redis, RedisLimiteAdaptativoStore limites, RamaProperties p) {
        return new RedisGovernor(redis, limites, p.governor().leaseTtl(), p.governor().maxWait());
    }

    @Bean
    RedisCatalogCache redisCatalogCache(StringRedisTemplate redis, ObjectMapper json, RamaProperties p, MeterRegistry metricas) {
        return new RedisCatalogCache(redis, json, p.cache().ttl(), metricas);
    }

    // ─── Gateway hacia la Rama Judicial ──────────────────────────────────────────

    @Bean
    RamaJudicialGateway ramaJudicialGateway(RamaProperties p, ObjectMapper json, RegistroDePeticiones registro,
                                            MeterRegistry metricas, RedisCircuitBreakerStore breaker,
                                            RedisLimiteAdaptativoStore limites, BackoffPolicy backoff, Clock reloj,
                                            RedisGovernor governor, RedisCatalogCache cache, EventPublisher eventos) {
        RamaJudicialGateway http = new HttpRamaJudicialGateway(p.baseUrl(), json, p.http().searchTimeout(),
                p.http().otherTimeout(), registro, metricas);
        RamaJudicialGateway conBreaker = new BreakerGateway(http, breaker, limites, backoff, reloj, eventos);
        RamaJudicialGateway gobernado = new GovernedGateway(conBreaker, governor);
        return new CachingGateway(gobernado, cache);
    }

    // ─── Mensajería ──────────────────────────────────────────────────────────────

    @Bean
    Declarables topologiaRabbit(BackoffPolicy backoff) {
        return RabbitTopology.declarar(backoff);
    }

    @Bean
    WorkPublisher workPublisher(RabbitTemplate plantilla, ObjectMapper json, BackoffPolicy backoff) {
        return new RabbitWorkPublisher(plantilla, json, backoff, Duration.ofSeconds(10));
    }

    @Bean
    EventPublisher eventPublisher(ConnectionFactory conexiones, ObjectMapper json) {
        return new RabbitEventPublisher(conexiones, json);
    }

    @Bean
    BrokerInspector brokerInspector(RamaProperties p) {
        return new RabbitManagementInspector(p.brokerApi().url(), p.brokerApi().user(), p.brokerApi().password());
    }

    // ─── Casos de uso ────────────────────────────────────────────────────────────

    @Bean
    IniciarBusqueda iniciarBusqueda(Clock reloj, SearchJobRepository jobs, WorkItemRepository items,
                                    UnidadDeTrabajo unidad, EventPublisher eventos) {
        return new IniciarBusquedaService(reloj, jobs, items, unidad, eventos);
    }

    @Bean
    ConsultarProgreso consultarProgreso(SearchJobRepository jobs, WorkItemRepository items, ProcesoHitRepository hits) {
        return new ConsultarProgresoService(jobs, items, hits);
    }

    @Bean
    ConsultasDeJobService consultasDeJob(SearchJobRepository jobs, ProcesoHitRepository hits, WorkItemRepository items) {
        return new ConsultasDeJobService(jobs, hits, items);
    }

    @Bean
    CancelarJob cancelarJob(Clock reloj, SearchJobRepository jobs, WorkItemRepository items, UnidadDeTrabajo unidad,
                            EventPublisher eventos) {
        return new CancelarJobService(reloj, jobs, items, unidad, eventos);
    }

    @Bean
    DlqInspector dlqInspector(RabbitTemplate plantilla, ObjectMapper json) {
        return new RabbitDlqInspector(plantilla, json);
    }

    @Bean
    ReencolarFallidos reencolarFallidos(Clock reloj, WorkItemRepository items, SearchJobRepository jobs,
                                        UnidadDeTrabajo unidad, DlqInspector dlq, EventPublisher eventos) {
        return new ReencolarFallidosService(reloj, items, jobs, unidad, dlq, eventos);
    }

    @Bean
    ProcesarTrabajo procesarTrabajo(Clock reloj, WorkItemRepository items, SearchJobRepository jobs, ProcesoHitRepository hits,
                                    RamaJudicialGateway rama, ExpansionPlanner planner, BackoffPolicy backoff,
                                    WorkPublisher publicador, EventPublisher eventos, UnidadDeTrabajo unidad,
                                    RamaProperties p) {
        return new ProcesadorDeTrabajo(reloj, items, jobs, hits, rama, planner, backoff, publicador, eventos, unidad,
                p.watchdog().stuckAfter());
    }

    @Bean
    PublicarPendientes publicarPendientes(Clock reloj, WorkItemRepository items, WorkPublisher publicador,
                                          UnidadDeTrabajo unidad, RamaProperties p) {
        return new PublicadorDeOutbox(reloj, items, publicador, unidad, p.outbox().batch(),
                p.outbox().maxInBroker(), p.outbox().guaranteedShare());
    }

    @Bean
    MantenerCola mantenerCola(Clock reloj, WorkItemRepository items, UnidadDeTrabajo unidad, BrokerInspector broker,
                              BackoffPolicy backoff, RamaProperties p) {
        List<String> esperasBusqueda = backoff.nivelesDeReintento().stream().map(d -> Colas.espera(Colas.BUSQUEDA, d)).toList();
        List<String> esperasDetalle = backoff.nivelesDeReintento().stream().map(d -> Colas.espera(Colas.DETALLE, d)).toList();
        // Un ítem puede esperar legítimamente en una cola de reintento hasta su nivel máximo: la gracia lo supera con holgura.
        Duration gracia = p.watchdog().republishGrace().plus(backoff.nivelesDeReintento().get(backoff.nivelesDeReintento().size() - 1));
        return new VigilanteDeCola(reloj, items, unidad, broker, p.watchdog().stuckAfter(), gracia, p.watchdog().maxPerCycle(),
                new VigilanteDeCola.Colas(Colas.TRABAJO_BUSQUEDA, esperasBusqueda),
                new VigilanteDeCola.Colas(Colas.TRABAJO_DETALLE, esperasDetalle));
    }

    // ─── Métricas de infraestructura ─────────────────────────────────────────────

    @Bean
    MeterBinder metricasDeInfraestructura(RedisGovernor governor, RedisLimiteAdaptativoStore limites,
                                          RedisCircuitBreakerStore breaker, Clock reloj) {
        return (MeterRegistry registro) -> {
            Gauge.builder("rama.governor.in_flight", governor, RedisGovernor::enVuelo)
                    .description("Peticiones hacia la Rama Judicial en vuelo ahora mismo").register(registro);
            Gauge.builder("rama.aimd.max_in_flight", limites, l -> l.leer().maxEnVuelo())
                    .description("Límite adaptativo de peticiones en vuelo").register(registro);
            Gauge.builder("rama.aimd.interval_ms", limites, l -> l.leer().intervaloMs())
                    .description("Separación mínima adaptativa entre peticiones").register(registro);
            Gauge.builder("rama.breaker.state", breaker, b -> switch (b.leer().evaluar(Instant.now(reloj)).estado()) {
                        case CERRADO -> 0;
                        case SEMIABIERTO -> 1;
                        case ABIERTO -> 2;
                    })
                    .description("Circuit breaker: 0 cerrado, 1 semiabierto, 2 abierto").register(registro);
        };
    }
}
