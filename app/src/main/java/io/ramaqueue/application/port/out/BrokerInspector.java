package io.ramaqueue.application.port.out;

/** Consulta el estado de las colas en el broker (API de gestión de RabbitMQ). */
public interface BrokerInspector {

    record EstadoDeCola(long listos, long sinConfirmar, int consumidores) {
        public boolean vacia() {
            return listos == 0 && sinConfirmar == 0;
        }
    }

    EstadoDeCola estadoDe(String cola);

    /** Resumen de una cola para el dashboard, con las tasas por segundo que informa el broker. */
    record ResumenDeCola(String nombre, long listos, long sinConfirmar, int consumidores, double publicadosPorSegundo,
                         double entregadosPorSegundo, double confirmadosPorSegundo) {
    }

    /** Todas las colas del sistema (las de trabajo, de espera y las DLQ). */
    java.util.List<ResumenDeCola> colas();
}
