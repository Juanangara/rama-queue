package io.ramaqueue.infrastructure.out.messaging;

import io.ramaqueue.domain.policy.BackoffPolicy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Declara la topología completa. RabbitAdmin la crea al conectar (es idempotente).
 *
 * <pre>
 * rama.work  (direct)  search ─► search.fetch (prioridad)      detail ─► detail.fetch
 * rama.retry (direct)  search.retry.30s | 2m | 8m   detail.retry.…   (TTL; al vencer vuelven a rama.work)
 * rama.dlx   (direct)  search ─► search.dlq         detail ─► detail.dlq
 * rama.events (fanout) cada instancia de la API crea su cola exclusiva
 * </pre>
 *
 * Las colas de trabajo NO tienen dead-letter: un mensaje que el worker rechaza se descarta, y el watchdog recupera
 * el ítem desde Postgres. Así un error inesperado nunca provoca un bucle de reentregas inmediatas.
 */
public final class RabbitTopology {

    private RabbitTopology() {
    }

    public static Declarables declarar(BackoffPolicy backoff) {
        DirectExchange trabajo = new DirectExchange(Colas.EXCHANGE_TRABAJO, true, false);
        DirectExchange espera = new DirectExchange(Colas.EXCHANGE_ESPERA, true, false);
        DirectExchange dlx = new DirectExchange(Colas.EXCHANGE_DLX, true, false);
        FanoutExchange eventos = new FanoutExchange(Colas.EXCHANGE_EVENTOS, true, false);

        List<Declarable> declarables = new ArrayList<>(List.of(trabajo, espera, dlx, eventos));

        Queue busqueda = QueueBuilder.durable(Colas.TRABAJO_BUSQUEDA).maxPriority(Colas.PRIORIDAD_MAXIMA).build();
        Queue detalle = QueueBuilder.durable(Colas.TRABAJO_DETALLE).build();
        declarables.add(busqueda);
        declarables.add(detalle);
        declarables.add(BindingBuilder.bind(busqueda).to(trabajo).with(Colas.BUSQUEDA));
        declarables.add(BindingBuilder.bind(detalle).to(trabajo).with(Colas.DETALLE));

        for (String grupo : List.of(Colas.BUSQUEDA, Colas.DETALLE)) {
            for (Duration nivel : backoff.nivelesDeReintento()) {
                String nombre = Colas.espera(grupo, nivel);
                Queue cola = QueueBuilder.durable(nombre)
                        .ttl((int) nivel.toMillis())
                        .deadLetterExchange(Colas.EXCHANGE_TRABAJO)
                        .deadLetterRoutingKey(grupo)
                        .build();
                declarables.add(cola);
                Binding enlace = BindingBuilder.bind(cola).to(espera).with(nombre);
                declarables.add(enlace);
            }
            Queue muertos = QueueBuilder.durable(Colas.dlq(grupo)).build();
            declarables.add(muertos);
            declarables.add(BindingBuilder.bind(muertos).to(dlx).with(grupo));
        }
        return new Declarables(declarables);
    }
}
