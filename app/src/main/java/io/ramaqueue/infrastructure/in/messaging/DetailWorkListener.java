package io.ramaqueue.infrastructure.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.ramaqueue.application.port.in.ProcesarTrabajo;
import io.ramaqueue.infrastructure.out.messaging.Colas;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Profile("worker-detail")
class DetailWorkListener extends TrabajoListener {

    static final String ID = "detail-listener";

    DetailWorkListener(ProcesarTrabajo procesador, ObjectMapper json) {
        super(procesador, json);
    }

    @RabbitListener(id = ID, queues = Colas.TRABAJO_DETALLE, containerFactory = "detailContainerFactory")
    public void recibir(Message mensaje, Channel canal) throws IOException {
        manejar(mensaje, canal);
    }
}
