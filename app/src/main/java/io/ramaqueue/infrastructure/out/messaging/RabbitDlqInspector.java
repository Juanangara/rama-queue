package io.ramaqueue.infrastructure.out.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import io.ramaqueue.application.port.out.DlqInspector;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Lee y depura las colas de mensajes muertos con {@code basic.get}. Mirar no consume: lo leído se devuelve a la cola con
 * un nack multiple. Depurar confirma (ack) lo que se descarta y republica lo que se conserva antes de confirmarlo.
 */
public class RabbitDlqInspector implements DlqInspector {

    private static final Map<String, String> GRUPO_POR_COLA = Map.of(
            Colas.DLQ_BUSQUEDA, Colas.BUSQUEDA, Colas.DLQ_DETALLE, Colas.DETALLE);

    private final RabbitTemplate plantilla;
    private final ObjectMapper json;

    public RabbitDlqInspector(RabbitTemplate plantilla, ObjectMapper json) {
        this.plantilla = plantilla;
        this.json = json;
    }

    @Override
    public List<EntradaDlq> examinar(int maximo) {
        return plantilla.execute(canal -> {
            List<EntradaDlq> entradas = new ArrayList<>();
            for (Map.Entry<String, String> cola : GRUPO_POR_COLA.entrySet()) {
                long ultimaEtiqueta = -1;
                for (int i = 0; i < maximo; i++) {
                    GetResponse respuesta = canal.basicGet(cola.getKey(), false);
                    if (respuesta == null) {
                        break;
                    }
                    ultimaEtiqueta = respuesta.getEnvelope().getDeliveryTag();
                    entradas.add(leer(cola.getValue(), respuesta));
                }
                if (ultimaEtiqueta >= 0) {
                    // Los canales se reutilizan, así que no basta con cerrarlos: se devuelve lo leído de forma explícita.
                    canal.basicNack(ultimaEtiqueta, true, true);
                }
            }
            return entradas;
        });
    }

    @Override
    public int depurar(Predicate<UUID> descartar) {
        Integer quitados = plantilla.execute(canal -> {
            int total = 0;
            for (Map.Entry<String, String> cola : GRUPO_POR_COLA.entrySet()) {
                int pendientes = canal.queueDeclarePassive(cola.getKey()).getMessageCount();
                for (int i = 0; i < pendientes; i++) {
                    GetResponse respuesta = canal.basicGet(cola.getKey(), false);
                    if (respuesta == null) {
                        break;
                    }
                    EntradaDlq entrada = leer(cola.getValue(), respuesta);
                    if (entrada.itemId() != null && descartar.test(entrada.itemId())) {
                        total++;
                    } else {
                        canal.basicPublish(Colas.EXCHANGE_DLX, cola.getValue(), respuesta.getProps(), respuesta.getBody());
                    }
                    canal.basicAck(respuesta.getEnvelope().getDeliveryTag(), false);
                }
            }
            return total;
        });
        return quitados == null ? 0 : quitados;
    }

    private EntradaDlq leer(String grupo, GetResponse respuesta) {
        UUID itemId = null;
        UUID jobId = null;
        String tipo = null;
        try {
            JsonNode cuerpo = json.readTree(respuesta.getBody());
            itemId = UUID.fromString(cuerpo.path("itemId").asText());
            jobId = UUID.fromString(cuerpo.path("jobId").asText());
            tipo = cuerpo.path("tipo").asText(null);
        } catch (IOException | IllegalArgumentException e) {
            // Un mensaje ilegible se muestra igualmente, sin ids.
        }
        Map<String, Object> cabeceras = respuesta.getProps().getHeaders();
        String motivo = cabeceras == null || cabeceras.get("x-reason") == null ? "" : cabeceras.get("x-reason").toString();
        int intentos = cabeceras == null || cabeceras.get("x-attempts") == null ? 0
                : Integer.parseInt(cabeceras.get("x-attempts").toString());
        return new EntradaDlq(grupo, itemId, jobId, tipo, motivo, intentos);
    }
}
