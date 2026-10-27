package io.ramaqueue.application.port.out;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Las colas de mensajes muertos: constancia de los ítems que fallaron definitivamente. */
public interface DlqInspector {

    record EntradaDlq(String grupo, UUID itemId, UUID jobId, String tipo, String motivo, int intentos) {
    }

    /** Mira (sin consumir) hasta {@code maximo} mensajes de cada DLQ. */
    List<EntradaDlq> examinar(int maximo);

    /** Quita de las DLQ los mensajes cuyo ítem cumple el predicado y conserva el resto. Devuelve cuántos quitó. */
    int depurar(Predicate<UUID> descartar);
}
