package io.ramaqueue.domain.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * Circuit breaker global del carril hacia la Rama Judicial. Es una función pura del estado y del reloj;
 * quien lo guarda (Redis) solo persiste este valor.
 *
 * <pre>
 * CERRADO ──403──► ABIERTO ──(pasa el enfriamiento)──► SEMIABIERTO ──éxito──► CERRADO
 *                     ▲                                     │
 *                     └──────────────403────────────────────┘
 * </pre>
 */
public record CircuitBreakerState(Estado estado, Instant abiertoHasta, int aperturasSeguidas) {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    public static CircuitBreakerState cerrado() {
        return new CircuitBreakerState(Estado.CERRADO, null, 0);
    }

    /**
     * Registra un 403. Si el breaker ya está abierto y vigente se ignora: las demás peticiones que iban en vuelo
     * también recibirán 403 y no deben escalar el enfriamiento.
     */
    public CircuitBreakerState alBloqueo(Instant ahora, BackoffPolicy backoff) {
        if (estado == Estado.ABIERTO && ahora.isBefore(abiertoHasta)) {
            return this;
        }
        int aperturas = aperturasSeguidas + 1;
        return new CircuitBreakerState(Estado.ABIERTO, ahora.plus(backoff.enfriamiento(aperturas)), aperturas);
    }

    /** Un éxito cierra el breaker solo si estaba probando; un éxito rezagado con el breaker abierto no cuenta. */
    public CircuitBreakerState alExito() {
        return estado == Estado.SEMIABIERTO ? cerrado() : this;
    }

    /** Pasa de ABIERTO a SEMIABIERTO cuando termina el enfriamiento. */
    public CircuitBreakerState evaluar(Instant ahora) {
        if (estado == Estado.ABIERTO && !ahora.isBefore(abiertoHasta)) {
            return new CircuitBreakerState(Estado.SEMIABIERTO, null, aperturasSeguidas);
        }
        return this;
    }

    public boolean permitePeticiones(Instant ahora) {
        return evaluar(ahora).estado != Estado.ABIERTO;
    }

    public Duration restante(Instant ahora) {
        if (estado != Estado.ABIERTO || !ahora.isBefore(abiertoHasta)) {
            return Duration.ZERO;
        }
        return Duration.between(ahora, abiertoHasta);
    }
}
