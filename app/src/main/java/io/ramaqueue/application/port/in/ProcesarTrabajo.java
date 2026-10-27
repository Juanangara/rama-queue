package io.ramaqueue.application.port.in;

import java.util.UUID;

/** Lo que invoca el consumidor de mensajes por cada entrega. Es idempotente: una entrega repetida no hace daño. */
public interface ProcesarTrabajo {

    enum ResultadoDeEntrega {
        /** Se procesó (con éxito o dejando el ítem listo para reintentar). */
        PROCESADO,
        /** No había nada que hacer: ya era terminal, otro worker lo tiene, o se perdió el reclamo. */
        IGNORADO
    }

    ResultadoDeEntrega procesar(UUID itemId, boolean esReentrega);
}
