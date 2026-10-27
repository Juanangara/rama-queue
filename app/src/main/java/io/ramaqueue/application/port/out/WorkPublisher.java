package io.ramaqueue.application.port.out;

import io.ramaqueue.domain.model.WorkItem;

import java.util.Collection;

/** Salida hacia el broker. Todos los métodos esperan la confirmación (publisher confirm) antes de volver. */
public interface WorkPublisher {

    /** Publica en la cola de trabajo que corresponda al tipo de ítem. Lanza {@link PublicacionFallidaException} si el broker no confirma. */
    void publicar(Collection<WorkItem> items);

    /** Reenvía el ítem a la cola de espera del nivel dado; vuelve solo a la cola de trabajo cuando vence su TTL. */
    void publicarReintento(WorkItem item, int nivelDeReintento);

    /** Deja constancia del ítem fallido en la cola de mensajes muertos, con la causa. */
    void enviarADlq(WorkItem item, String motivo);

    class PublicacionFallidaException extends RuntimeException {
        public PublicacionFallidaException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }

        public PublicacionFallidaException(String mensaje) {
            super(mensaje);
        }
    }
}
