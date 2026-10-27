package io.ramaqueue.infrastructure.out.rama;

import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.infrastructure.out.redis.RedisGovernor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Decorador: ninguna petición sale sin permiso del semáforo y turno de la compuerta, compartidos por todos los workers. */
public class GovernedGateway implements RamaJudicialGateway {

    private final RamaJudicialGateway delegado;
    private final RedisGovernor governor;

    public GovernedGateway(RamaJudicialGateway delegado, RedisGovernor governor) {
        this.delegado = delegado;
        this.governor = governor;
    }

    private <T> T gobernado(Supplier<T> operacion) {
        RedisGovernor.Permiso permiso = governor.adquirir();
        try {
            return operacion.get();
        } finally {
            governor.liberar(permiso);
        }
    }

    @Override
    public PaginaDeBusqueda buscar(String nombre, TipoPersona tipoPersona, String codigo, int pagina) {
        return gobernado(() -> delegado.buscar(nombre, tipoPersona, codigo, pagina));
    }

    @Override
    public List<ItemListado> listar(Nivel nivelPadre, String codigoPadre) {
        return gobernado(() -> delegado.listar(nivelPadre, codigoPadre));
    }

    @Override
    public DetalleProceso detalle(long idProceso) {
        return gobernado(() -> delegado.detalle(idProceso));
    }

    @Override
    public Optional<UltimaActuacion> ultimaActuacion(long idProceso) {
        return gobernado(() -> delegado.ultimaActuacion(idProceso));
    }

    @Override
    public PaginaDeSujetos sujetos(long idProceso, int pagina) {
        return gobernado(() -> delegado.sujetos(idProceso, pagina));
    }
}
