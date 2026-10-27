package io.ramaqueue.infrastructure.out.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import io.ramaqueue.application.port.out.BrokerInspector;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * Lee el estado de las colas de la API de gestión de RabbitMQ. Esas estadísticas se refrescan cada pocos segundos, por
 * eso la reconciliación exige además una gracia larga antes de dar un mensaje por perdido.
 */
public class RabbitManagementInspector implements BrokerInspector {

    private final RestClient cliente;

    public RabbitManagementInspector(String url, String usuario, String clave) {
        String credencial = Base64.getEncoder().encodeToString((usuario + ":" + clave).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.cliente = RestClient.builder()
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credencial)
                .build();
    }

    @Override
    public java.util.List<ResumenDeCola> colas() {
        JsonNode nodos = cliente.get().uri("/api/queues/{vhost}", "/").retrieve().body(JsonNode.class);
        java.util.List<ResumenDeCola> colas = new java.util.ArrayList<>();
        if (nodos != null) {
            for (JsonNode n : nodos) {
                String nombre = n.path("name").asText();
                if (!nombre.startsWith("search.") && !nombre.startsWith("detail.")) {
                    continue;
                }
                JsonNode tasas = n.path("message_stats");
                colas.add(new ResumenDeCola(nombre, n.path("messages_ready").asLong(0), n.path("messages_unacknowledged").asLong(0),
                        n.path("consumers").asInt(0), tasas.path("publish_details").path("rate").asDouble(0),
                        tasas.path("deliver_get_details").path("rate").asDouble(0), tasas.path("ack_details").path("rate").asDouble(0)));
            }
        }
        colas.sort(java.util.Comparator.comparing(ResumenDeCola::nombre));
        return colas;
    }

    @Override
    public EstadoDeCola estadoDe(String cola) {
        // El vhost «/» va como variable de plantilla: así se codifica como %2F una sola vez (escrito a mano se duplicaría).
        JsonNode nodo = cliente.get().uri("/api/queues/{vhost}/{cola}", "/", cola).retrieve().body(JsonNode.class);
        if (nodo == null) {
            throw new IllegalStateException("La API de gestión no devolvió datos de la cola " + cola);
        }
        return new EstadoDeCola(
                nodo.path("messages_ready").asLong(0),
                nodo.path("messages_unacknowledged").asLong(0),
                nodo.path("consumers").asInt(0));
    }
}
