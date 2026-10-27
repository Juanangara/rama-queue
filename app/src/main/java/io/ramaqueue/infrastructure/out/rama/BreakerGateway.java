package io.ramaqueue.infrastructure.out.rama;

import io.ramaqueue.application.port.out.EventPublisher;
import io.ramaqueue.application.port.out.RamaException;
import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.policy.BackoffPolicy;
import io.ramaqueue.domain.policy.CircuitBreakerState;
import io.ramaqueue.infrastructure.out.redis.RedisCircuitBreakerStore;
import io.ramaqueue.infrastructure.out.redis.RedisLimiteAdaptativoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decorador: un 403/429 abre el circuit breaker y baja el límite adaptativo; una respuesta normal (incluidos el 400
 * de «más de mil» y el 404) cuenta como servidor sano. Con el breaker abierto no llega ninguna petición al servidor:
 * se rechaza aquí mismo como si fuera un 403, y el ítem vuelve a la cola sin gastar intentos.
 */
public class BreakerGateway implements RamaJudicialGateway {

    private static final Logger log = LoggerFactory.getLogger(BreakerGateway.class);

    private final RamaJudicialGateway delegado;
    private final RedisCircuitBreakerStore breaker;
    private final RedisLimiteAdaptativoStore limites;
    private final BackoffPolicy backoff;
    private final Clock reloj;
    private final EventPublisher eventos;

    public BreakerGateway(RamaJudicialGateway delegado, RedisCircuitBreakerStore breaker,
                          RedisLimiteAdaptativoStore limites, BackoffPolicy backoff, Clock reloj, EventPublisher eventos) {
        this.delegado = delegado;
        this.breaker = breaker;
        this.limites = limites;
        this.backoff = backoff;
        this.reloj = reloj;
        this.eventos = eventos;
    }

    private <T> T llamar(Supplier<T> operacion) {
        Instant ahora = reloj.instant();
        CircuitBreakerState antes = breaker.leer();
        if (!antes.permitePeticiones(ahora)) {
            throw new RamaException(403, "Circuit breaker abierto: quedan " + antes.restante(ahora).toSeconds() + " s de enfriamiento");
        }
        try {
            T resultado = operacion.get();
            servidorSano(antes.evaluar(ahora));
            return resultado;
        } catch (RamaException e) {
            if (e.estado() == 403 || e.estado() == 429) {
                bloqueado(antes, ahora);
            } else if (e.estado() == 400 || e.estado() == 404) {
                servidorSano(antes.evaluar(ahora));
            }
            throw e;
        }
    }

    private void servidorSano(CircuitBreakerState evaluado) {
        CircuitBreakerState nuevo = evaluado.alExito();
        if (!nuevo.equals(breaker.leer())) {
            breaker.guardar(nuevo);
            if (nuevo.estado() == CircuitBreakerState.Estado.CERRADO) {
                log.info("Circuit breaker cerrado: el servidor volvió a responder");
                eventos.publicar(new EventPublisher.EventoDeProgreso("BREAKER_CERRADO", null, null,
                        "el servidor volvió a responder", reloj.instant()));
            }
        }
        limites.alExito();
    }

    private void bloqueado(CircuitBreakerState antes, Instant ahora) {
        CircuitBreakerState nuevo = antes.alBloqueo(ahora, backoff);
        if (!nuevo.equals(antes)) {
            breaker.guardar(nuevo);
            limites.alBloqueo(); // solo en el primer 403 de la ráfaga: los demás en vuelo no repiten la penalización
            long segundos = java.time.Duration.between(ahora, nuevo.abiertoHasta()).toSeconds();
            log.warn("La Rama Judicial bloqueó (403): circuit breaker abierto durante {} s (apertura n.º {})",
                    segundos, nuevo.aperturasSeguidas());
            eventos.publicar(new EventPublisher.EventoDeProgreso("BREAKER_ABIERTO", null, null,
                    "bloqueo del servidor: apertura n.º " + nuevo.aperturasSeguidas() + ", espera de " + segundos + " s", ahora));
        }
    }

    @Override
    public PaginaDeBusqueda buscar(String nombre, TipoPersona tipoPersona, String codigo, int pagina) {
        return llamar(() -> delegado.buscar(nombre, tipoPersona, codigo, pagina));
    }

    @Override
    public List<ItemListado> listar(Nivel nivelPadre, String codigoPadre) {
        return llamar(() -> delegado.listar(nivelPadre, codigoPadre));
    }

    @Override
    public DetalleProceso detalle(long idProceso) {
        return llamar(() -> delegado.detalle(idProceso));
    }

    @Override
    public Optional<UltimaActuacion> ultimaActuacion(long idProceso) {
        return llamar(() -> delegado.ultimaActuacion(idProceso));
    }

    @Override
    public PaginaDeSujetos sujetos(long idProceso, int pagina) {
        return llamar(() -> delegado.sujetos(idProceso, pagina));
    }
}
