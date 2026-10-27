package io.ramaqueue.application.port.in;

import java.util.UUID;

public interface ReencolarFallidos {

    /** @param reencolados ítems que volvieron a PENDING; @param retiradosDeLaDlq mensajes quitados de las DLQ */
    record Resultado(int reencolados, int retiradosDeLaDlq) {
    }

    /** Reencola los ítems FAILED de un job (o de todos, con {@code null}) y limpia sus copias de las DLQ (D-05). */
    Resultado reencolar(UUID jobIdOTodos);
}
