package io.ramaqueue.infrastructure.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.out.WorkPublisher;
import io.ramaqueue.domain.model.WorkItem;
import io.ramaqueue.domain.policy.BackoffPolicy;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica con confirmaciones del broker y {@code mandatory}: un mensaje no enrutable o no confirmado es un fallo
 * visible, nunca una pérdida silenciosa. El mensaje es solo un puntero ({@code jobId}, {@code itemId}); la verdad
 * del ítem está en Postgres.
 */
public class RabbitWorkPublisher implements WorkPublisher {

    private final RabbitTemplate plantilla;
    private final ObjectMapper json;
    private final BackoffPolicy backoff;
    private final Duration esperaDeConfirmacion;

    public RabbitWorkPublisher(RabbitTemplate plantilla, ObjectMapper json, BackoffPolicy backoff,
                               Duration esperaDeConfirmacion) {
        this.plantilla = plantilla;
        this.json = json;
        this.backoff = backoff;
        this.esperaDeConfirmacion = esperaDeConfirmacion;
    }

    @Override
    public void publicar(Collection<WorkItem> items) {
        List<CorrelationData> pendientes = new ArrayList<>();
        for (WorkItem item : items) {
            CorrelationData correlacion = new CorrelationData(item.id().toString());
            plantilla.send(Colas.EXCHANGE_TRABAJO, Colas.grupoDe(item), mensaje(item), correlacion);
            pendientes.add(correlacion);
        }
        confirmar(pendientes);
    }

    @Override
    public void publicarReintento(WorkItem item, int nivelDeReintento) {
        int indice = Math.min(Math.max(0, nivelDeReintento), backoff.nivelesDeReintento().size() - 1);
        String cola = Colas.espera(Colas.grupoDe(item), backoff.nivelesDeReintento().get(indice));
        CorrelationData correlacion = new CorrelationData(item.id().toString());
        plantilla.send(Colas.EXCHANGE_ESPERA, cola, mensaje(item), correlacion);
        confirmar(List.of(correlacion));
    }

    @Override
    public void enviarADlq(WorkItem item, String motivo) {
        Message mensaje = MessageBuilder.fromMessage(mensaje(item))
                .setHeader("x-reason", motivo == null ? "" : motivo)
                .setHeader("x-attempts", item.intentos())
                .setHeader("x-item-type", item.tipo().name())
                .build();
        CorrelationData correlacion = new CorrelationData(item.id().toString());
        plantilla.send(Colas.EXCHANGE_DLX, Colas.grupoDe(item), mensaje, correlacion);
        confirmar(List.of(correlacion));
    }

    private Message mensaje(WorkItem item) {
        try {
            byte[] cuerpo = json.writeValueAsBytes(java.util.Map.of(
                    "jobId", item.jobId().toString(), "itemId", item.id().toString(), "tipo", item.tipo().name()));
            return MessageBuilder.withBody(cuerpo)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setPriority(item.tipo().esDeBusqueda() ? Math.min(item.prioridad(), Colas.PRIORIDAD_MAXIMA) : null)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el mensaje del ítem " + item.id(), e);
        }
    }

    private void confirmar(List<CorrelationData> correlaciones) {
        for (CorrelationData c : correlaciones) {
            try {
                CorrelationData.Confirm confirmacion = c.getFuture().get(esperaDeConfirmacion.toMillis(), TimeUnit.MILLISECONDS);
                if (!confirmacion.isAck()) {
                    throw new PublicacionFallidaException("El broker no confirmó el mensaje " + c.getId() + ": " + confirmacion.getReason());
                }
                if (c.getReturned() != null) {
                    throw new PublicacionFallidaException("El mensaje " + c.getId() + " no se pudo enrutar a ninguna cola: "
                            + c.getReturned().getReplyText());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublicacionFallidaException("Interrumpido esperando la confirmación de " + c.getId(), e);
            } catch (ExecutionException | TimeoutException e) {
                throw new PublicacionFallidaException("Sin confirmación del broker para " + c.getId(), e);
            }
        }
    }
}
