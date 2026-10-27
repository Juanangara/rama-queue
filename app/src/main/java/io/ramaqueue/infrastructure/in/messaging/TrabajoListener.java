package io.ramaqueue.infrastructure.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.ramaqueue.application.port.in.ProcesarTrabajo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

import java.io.IOException;
import java.util.UUID;

/**
 * Lógica común de los consumidores. El ack se manda SOLO después de que el caso de uso termine: si el worker muere a
 * mitad, el broker reentrega. Un fallo inesperado se rechaza sin reencolar (la cola de trabajo no tiene dead-letter, así
 * que el mensaje se descarta) y el watchdog recupera el ítem desde Postgres: nunca hay un bucle de reentregas.
 */
abstract class TrabajoListener {

    private static final Logger log = LoggerFactory.getLogger(TrabajoListener.class);

    private final ProcesarTrabajo procesador;
    private final ObjectMapper json;

    TrabajoListener(ProcesarTrabajo procesador, ObjectMapper json) {
        this.procesador = procesador;
        this.json = json;
    }

    protected void manejar(Message mensaje, Channel canal) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();
        try {
            JsonNode cuerpo = json.readTree(mensaje.getBody());
            UUID itemId = UUID.fromString(cuerpo.path("itemId").asText());
            MDC.put("jobId", cuerpo.path("jobId").asText());
            MDC.put("itemId", itemId.toString());
            procesador.procesar(itemId, mensaje.getMessageProperties().isRedelivered());
            canal.basicAck(etiqueta, false);
        } catch (Throwable error) {
            log.error("Fallo inesperado procesando un mensaje; se descarta y el watchdog recuperará el ítem", error);
            canal.basicReject(etiqueta, false);
        } finally {
            MDC.clear();
        }
    }
}
