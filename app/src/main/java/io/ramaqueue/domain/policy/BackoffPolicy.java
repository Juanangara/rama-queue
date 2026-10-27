package io.ramaqueue.domain.policy;

import java.time.Duration;
import java.util.List;

/**
 * Tiempos de espera: enfriamiento del circuit breaker (crece con cada apertura seguida) y niveles de reintento
 * de los ítems con fallo transitorio (cada nivel es una cola con TTL en RabbitMQ).
 */
public record BackoffPolicy(
        Duration enfriamientoBase,
        double factor,
        Duration enfriamientoMaximo,
        List<Duration> nivelesDeReintento,
        int maxIntentos) {

    public BackoffPolicy {
        if (nivelesDeReintento.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos un nivel de reintento");
        }
        nivelesDeReintento = List.copyOf(nivelesDeReintento);
    }

    public static BackoffPolicy porDefecto() {
        return new BackoffPolicy(
                Duration.ofMinutes(2), 2.0, Duration.ofMinutes(15),
                List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(8)), 5);
    }

    /** Espera tras la {@code aperturaNumero}-ésima apertura seguida del breaker (1 = la primera). */
    public Duration enfriamiento(int aperturaNumero) {
        double ms = enfriamientoBase.toMillis() * Math.pow(factor, Math.max(0, aperturaNumero - 1));
        return Duration.ofMillis((long) Math.min(ms, enfriamientoMaximo.toMillis()));
    }

    /** Posición (desde 0) del nivel de reintento para el intento dado (1 = primer reintento). */
    public int indiceNivel(int intento) {
        return Math.min(Math.max(0, intento - 1), nivelesDeReintento.size() - 1);
    }

    public Duration esperaDeReintento(int intento) {
        return nivelesDeReintento.get(indiceNivel(intento));
    }

    /** {@code true} cuando el ítem ya consumió todos sus intentos. */
    public boolean agotado(int intentos) {
        return intentos >= maxIntentos;
    }
}
