package io.ramaqueue.infrastructure.in.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.infrastructure.in.web.SseHub;
import io.ramaqueue.infrastructure.out.messaging.Colas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Cada instancia de la API crea su propia cola anónima, exclusiva y autoborrable enlazada al exchange fanout de eventos:
 * así todas las instancias reciben todos los eventos y la cola desaparece sola al apagarse.
 */
@Component
@Profile("api")
class EventosEnVivo {

    private static final Logger log = LoggerFactory.getLogger(EventosEnVivo.class);

    private final SseHub hub;
    private final ObjectMapper json;

    EventosEnVivo(SseHub hub, ObjectMapper json) {
        this.hub = hub;
        this.json = json;
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(durable = "false", exclusive = "true", autoDelete = "true"),
            exchange = @Exchange(name = Colas.EXCHANGE_EVENTOS, type = ExchangeTypes.FANOUT)))
    void recibir(Message mensaje) {
        try {
            Map<String, Object> evento = json.readValue(mensaje.getBody(), new TypeReference<>() {
            });
            hub.recibir(evento);
        } catch (IOException e) {
            log.debug("Evento ilegible descartado: {}", e.getMessage());
        }
    }
}
