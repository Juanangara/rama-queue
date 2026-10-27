package io.ramaqueue.infrastructure.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.out.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eventos de progreso en un exchange fanout. Usa su propia plantilla sin {@code mandatory}: con el dashboard apagado
 * no hay ninguna cola enlazada y eso es normal, no un error. Nunca propaga excepciones.
 */
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate plantilla;
    private final ObjectMapper json;

    public RabbitEventPublisher(ConnectionFactory conexiones, ObjectMapper json) {
        this.plantilla = new RabbitTemplate(conexiones);
        this.json = json;
    }

    @Override
    public void publicar(EventoDeProgreso evento) {
        try {
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("tipo", evento.tipo());
            cuerpo.put("jobId", evento.jobId() == null ? null : evento.jobId().toString());
            cuerpo.put("itemId", evento.itemId() == null ? null : evento.itemId().toString());
            cuerpo.put("detalle", evento.detalle());
            cuerpo.put("instante", evento.instante().toString());
            plantilla.send(Colas.EXCHANGE_EVENTOS, "", org.springframework.amqp.core.MessageBuilder
                    .withBody(json.writeValueAsBytes(cuerpo))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build());
        } catch (JsonProcessingException | RuntimeException e) {
            log.debug("No se pudo publicar el evento {}: {}", evento.tipo(), e.getMessage());
        }
    }
}
