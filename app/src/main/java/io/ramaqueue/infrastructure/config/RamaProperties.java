package io.ramaqueue.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/** Configuración del motor (prefijo {@code rama}). Los valores por defecto son los de la guía §15. */
@ConfigurationProperties("rama")
public record RamaProperties(
        @DefaultValue("http://localhost:8089/api/v2") String baseUrl,
        @DefaultValue Http http,
        @DefaultValue Governor governor,
        @DefaultValue Breaker breaker,
        @DefaultValue Retry retry,
        @DefaultValue Watchdog watchdog,
        @DefaultValue Outbox outbox,
        @DefaultValue Cache cache,
        @DefaultValue Workers workers,
        @DefaultValue BrokerApi brokerApi) {

    public record Http(
            @DefaultValue("30s") Duration searchTimeout,
            @DefaultValue("15s") Duration otherTimeout) {
    }

    /**
     * Límites hacia el servidor: concurrencia inicial y tope, y separación mínima entre peticiones. Se arranca despacio
     * (1 en vuelo, 2 s): la Rama real bloqueó a 2 en vuelo y 1 s entre peticiones tras ~75 peticiones. El AIMD sube el ritmo si no hay bloqueos.
     */
    public record Governor(
            @DefaultValue("1") int initialInFlight,
            @DefaultValue("4") int maxInFlight,
            @DefaultValue("2000") long minIntervalMs,
            @DefaultValue("16000") long maxIntervalMs,
            @DefaultValue("50") int successesToIncrease,
            @DefaultValue("60s") Duration leaseTtl,
            @DefaultValue("30m") Duration maxWait) {
    }

    public record Breaker(
            @DefaultValue("2m") Duration cooldownBase,
            @DefaultValue("2") double factor,
            @DefaultValue("15m") Duration cooldownMax) {
    }

    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue({"30s", "2m", "8m"}) List<Duration> tiers) {
    }

    public record Watchdog(
            @DefaultValue("5m") Duration stuckAfter,
            @DefaultValue("30s") Duration interval,
            @DefaultValue("10m") Duration republishGrace,
            @DefaultValue("200") int maxPerCycle) {
    }

    public record Outbox(
            @DefaultValue("500ms") Duration interval,
            @DefaultValue("100") int batch,
            // Cada salto de la cadena de un trabajo espera detrás de, como mucho, esta cantidad de mensajes: a 1 petición cada 2 s,
            // 10 mensajes son ~20 s por salto (40 serían 80 s). Basta para mantener ocupados a los consumidores.
            @DefaultValue("10") int maxInBroker,
            @DefaultValue("0.25") double guaranteedShare) {
    }

    public record Cache(@DefaultValue("24h") Duration ttl) {
    }

    public record Workers(
            @DefaultValue("4") int searchConcurrency,
            @DefaultValue("2") int detailConcurrency) {
    }

    public record BrokerApi(
            @DefaultValue("http://localhost:15672") String url,
            @DefaultValue("ramaqueue") String user,
            @DefaultValue("ramaqueue") String password) {
    }
}
