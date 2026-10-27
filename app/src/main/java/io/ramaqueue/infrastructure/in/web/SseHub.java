package io.ramaqueue.infrastructure.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.in.ConsultarProgreso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reparte a los navegadores (SSE) lo que ocurre en el motor. Los eventos llegan del exchange fanout {@code rama.events}, así
 * que sirven aunque el trabajo lo hagan otros procesos. Para no inundar al cliente, el avance se envía como una
 * <em>fotografía</em> cada medio segundo por job con cambios, y solo se reenvían tal cual los eventos poco frecuentes e
 * importantes (creación, fin, bloqueos, fallos, ramas sin respuesta).
 */
@Component
@Profile("api")
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private record Suscripcion(UUID jobId, SseEmitter emisor) {
    }

    private final List<Suscripcion> suscripciones = new CopyOnWriteArrayList<>();
    private final Set<UUID> conCambios = ConcurrentHashMap.newKeySet();
    private final ConsultarProgreso progreso;
    private final ObjectMapper json;

    public SseHub(ConsultarProgreso progreso, ObjectMapper json) {
        this.progreso = progreso;
        this.json = json;
    }

    /** Suscripción a un job (con su fotografía inicial) o, con {@code null}, a todo lo que ocurra. */
    public SseEmitter suscribir(UUID jobId) {
        SseEmitter emisor = new SseEmitter(Duration.ofMinutes(30).toMillis());
        Suscripcion suscripcion = new Suscripcion(jobId, emisor);
        suscripciones.add(suscripcion);
        Runnable retirar = () -> suscripciones.remove(suscripcion);
        emisor.onCompletion(retirar);
        emisor.onTimeout(retirar);
        emisor.onError(e -> retirar.run());
        if (jobId != null) {
            enviarProgreso(suscripcion, jobId);
        }
        return emisor;
    }

    /** Un evento publicado por el motor. */
    public void recibir(Map<String, Object> evento) {
        UUID jobId = uuid(evento.get("jobId"));
        if (jobId != null) {
            conCambios.add(jobId);
        }
        if (esImportante(String.valueOf(evento.get("tipo")))) {
            for (Suscripcion s : suscripciones) {
                if (s.jobId() == null || jobId == null || s.jobId().equals(jobId)) {
                    enviar(s, "evento", evento);
                }
            }
        }
    }

    /** Fotografía del avance de cada job que cambió desde la última vez, solo para quien lo sigue. */
    public void emitirProgreso() {
        if (conCambios.isEmpty()) {
            return;
        }
        for (UUID jobId : new ArrayList<>(conCambios)) {
            conCambios.remove(jobId);
            for (Suscripcion s : suscripciones) {
                if (jobId.equals(s.jobId())) {
                    enviarProgreso(s, jobId);
                }
            }
        }
    }

    /** Comentario SSE periódico: evita que proxies y navegadores cierren conexiones inactivas. */
    public void latido() {
        for (Suscripcion s : suscripciones) {
            try {
                s.emisor().send(SseEmitter.event().comment("latido"));
            } catch (IOException | IllegalStateException e) {
                suscripciones.remove(s);
            }
        }
    }

    public int suscriptores() {
        return suscripciones.size();
    }

    private void enviarProgreso(Suscripcion s, UUID jobId) {
        progreso.progreso(jobId).ifPresent(p -> enviar(s, "progreso", JobDtos.JobProgreso.de(p)));
    }

    private void enviar(Suscripcion s, String nombre, Object datos) {
        try {
            s.emisor().send(SseEmitter.event().name(nombre).data(json.writeValueAsString(datos), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            suscripciones.remove(s);
        }
    }

    private static boolean esImportante(String tipo) {
        return !tipo.startsWith("ITEM_") || tipo.equals("ITEM_EXCEEDED") || tipo.equals("ITEM_FAILED");
    }

    private static UUID uuid(Object valor) {
        try {
            return valor == null ? null : UUID.fromString(valor.toString());
        } catch (IllegalArgumentException e) {
            log.debug("UUID inválido en un evento: {}", valor);
            return null;
        }
    }
}
