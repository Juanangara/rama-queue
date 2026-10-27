package io.ramaqueue.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Eventos de progreso para el dashboard. Es best-effort: un fallo aquí nunca debe afectar al procesamiento. */
public interface EventPublisher {

    record EventoDeProgreso(String tipo, UUID jobId, UUID itemId, String detalle, Instant instante) {
    }

    void publicar(EventoDeProgreso evento);
}
