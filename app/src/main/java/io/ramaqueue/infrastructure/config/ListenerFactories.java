package io.ramaqueue.infrastructure.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Consumidores con ack manual y prefetch 1: cada worker tiene a lo sumo un mensaje en mano. Arrancan detenidos; los
 * enciende {@code VigilanteDelBreaker} cuando comprueba que el circuit breaker lo permite.
 */
@Configuration
class ListenerFactories {

    @Bean
    @Profile("worker-search")
    SimpleRabbitListenerContainerFactory searchContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurador,
                                                                ConnectionFactory conexiones, RamaProperties p) {
        return fabrica(configurador, conexiones, p.workers().searchConcurrency());
    }

    @Bean
    @Profile("worker-detail")
    SimpleRabbitListenerContainerFactory detailContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurador,
                                                                ConnectionFactory conexiones, RamaProperties p) {
        return fabrica(configurador, conexiones, p.workers().detailConcurrency());
    }

    private static SimpleRabbitListenerContainerFactory fabrica(SimpleRabbitListenerContainerFactoryConfigurer configurador,
                                                                ConnectionFactory conexiones, int concurrencia) {
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        configurador.configure(fabrica, conexiones);
        fabrica.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        fabrica.setPrefetchCount(1);
        fabrica.setConcurrentConsumers(concurrencia);
        fabrica.setMaxConcurrentConsumers(concurrencia);
        fabrica.setDefaultRequeueRejected(false);
        fabrica.setAutoStartup(false);
        return fabrica;
    }
}
