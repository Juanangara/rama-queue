package io.ramaqueue.infrastructure.in.scheduler;

import io.ramaqueue.domain.policy.CircuitBreakerState;
import io.ramaqueue.infrastructure.out.redis.RedisCircuitBreakerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * El «interruptor» de los consumidores de un worker. En cada ciclo (1 s):
 * <ul>
 *   <li>detiene los consumidores mientras el circuit breaker está abierto o hay una pausa manual (en la UI de RabbitMQ se
 *       ve «0 consumidores») y los arranca cuando ambas cosas terminan;</li>
 *   <li>aplica la concurrencia pedida desde el dashboard;</li>
 *   <li>en el perfil {@code demo}, se cae si alguien reclamó el billete de «matar un worker».</li>
 * </ul>
 * Los contenedores nacen detenidos: si Redis no responde, ningún worker arranca (falla cerrado) y nunca se golpea al
 * servidor sin gobernador.
 */
@Component
@Profile({"worker-search", "worker-detail"})
public class VigilanteDelBreaker {

    private static final Logger log = LoggerFactory.getLogger(VigilanteDelBreaker.class);

    private final RabbitListenerEndpointRegistry registro;
    private final RedisCircuitBreakerStore breaker;
    private final ControlDeConsumidores control;
    private final Clock reloj;
    private final Environment entorno;
    private final java.util.Map<String, Integer> concurrenciaAplicada = new java.util.concurrent.ConcurrentHashMap<>();

    public VigilanteDelBreaker(RabbitListenerEndpointRegistry registro, RedisCircuitBreakerStore breaker,
                               ControlDeConsumidores control, Clock reloj, Environment entorno) {
        this.registro = registro;
        this.breaker = breaker;
        this.control = control;
        this.reloj = reloj;
        this.entorno = entorno;
    }

    public void revisar() {
        if (entorno.acceptsProfiles(Profiles.of("demo")) && control.reclamarCaida()) {
            log.error("Caída solicitada desde el dashboard: este worker se detiene de golpe (demo)");
            Runtime.getRuntime().halt(137);
        }
        CircuitBreakerState estado = breaker.leer();
        boolean breakerPermite = estado.permitePeticiones(reloj.instant());
        boolean pausado = control.estaPausado();
        boolean debenCorrer = breakerPermite && !pausado;
        for (String id : registro.getListenerContainerIds()) {
            MessageListenerContainer contenedor = registro.getListenerContainer(id);
            if (contenedor == null) {
                continue;
            }
            aplicarConcurrencia(id, contenedor);
            if (!debenCorrer && contenedor.isRunning()) {
                log.warn("Consumidores {} detenidos ({})", id, pausado ? "pausa manual" : "circuit breaker abierto");
                contenedor.stop();
            } else if (debenCorrer && !contenedor.isRunning()) {
                log.info("Consumidores {} en marcha", id);
                contenedor.start();
            }
        }
    }

    private void aplicarConcurrencia(String id, MessageListenerContainer contenedor) {
        if (!(contenedor instanceof SimpleMessageListenerContainer simple)) {
            return;
        }
        control.concurrenciaDeseada(id).ifPresent(deseada -> {
            if (!deseada.equals(concurrenciaAplicada.get(id))) {
                // El máximo debe ser siempre >= que el mínimo: se sube primero uno y se baja después el otro.
                simple.setMaxConcurrentConsumers(Math.max(deseada, simple.getActiveConsumerCount()));
                simple.setConcurrentConsumers(deseada);
                simple.setMaxConcurrentConsumers(deseada);
                concurrenciaAplicada.put(id, deseada);
                log.info("Concurrencia de {} ajustada a {} consumidores", id, deseada);
            }
        });
    }
}
