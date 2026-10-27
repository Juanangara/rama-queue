package io.ramaqueue.infrastructure.out.rama;

import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.infrastructure.out.redis.RedisCatalogCache;

import java.util.List;
import java.util.Optional;

/** Decorador más externo: un listado ya conocido se resuelve sin gastar permiso ni petición. Lo demás pasa directo. */
public class CachingGateway implements RamaJudicialGateway {

    private final RamaJudicialGateway delegado;
    private final RedisCatalogCache cache;

    public CachingGateway(RamaJudicialGateway delegado, RedisCatalogCache cache) {
        this.delegado = delegado;
        this.cache = cache;
    }

    @Override
    public List<ItemListado> listar(Nivel nivelPadre, String codigoPadre) {
        Optional<List<ItemListado>> enCache = cache.obtener(nivelPadre, codigoPadre);
        if (enCache.isPresent()) {
            return enCache.get();
        }
        List<ItemListado> lista = delegado.listar(nivelPadre, codigoPadre);
        cache.guardar(nivelPadre, codigoPadre, lista);
        return lista;
    }

    @Override
    public PaginaDeBusqueda buscar(String nombre, TipoPersona tipoPersona, String codigo, int pagina) {
        return delegado.buscar(nombre, tipoPersona, codigo, pagina);
    }

    @Override
    public DetalleProceso detalle(long idProceso) {
        return delegado.detalle(idProceso);
    }

    @Override
    public Optional<UltimaActuacion> ultimaActuacion(long idProceso) {
        return delegado.ultimaActuacion(idProceso);
    }

    @Override
    public PaginaDeSujetos sujetos(long idProceso, int pagina) {
        return delegado.sujetos(idProceso, pagina);
    }
}
