package io.ramaqueue.application.port.out;

import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SujetoProceso;
import io.ramaqueue.domain.model.TipoPersona;

import java.util.List;
import java.util.Optional;

/**
 * La Rama Judicial vista como un puerto. Cada método equivale a una petición HTTP (salvo los listados en caché).
 * Cualquier respuesta que no sea 2xx se traduce en una {@link RamaException} con el estado HTTP.
 */
public interface RamaJudicialGateway {

    /** Una página de la búsqueda por nombre. HTTP 400 («más de mil») llega como {@link RamaException} con estado 400. */
    PaginaDeBusqueda buscar(String nombre, TipoPersona tipoPersona, String codigo, int pagina);

    /** Hijos de un ámbito: entidades de un municipio, especialidades de una entidad o despachos de una especialidad. */
    List<ItemListado> listar(Nivel nivelPadre, String codigoPadre);

    DetalleProceso detalle(long idProceso);

    /** La actuación de mayor consecutivo de la primera página, si el proceso tiene actuaciones. */
    Optional<UltimaActuacion> ultimaActuacion(long idProceso);

    PaginaDeSujetos sujetos(long idProceso, int pagina);

    record PaginaDeBusqueda(int totalRegistros, int totalPaginas, List<ProcesoEncontrado> procesos) {
    }

    record DetalleProceso(String tipoProceso, String claseProceso, String subclaseProceso, String ponente) {
    }

    record UltimaActuacion(String fechaActuacion, String actuacion, String anotacion, String fechaRegistro) {
    }

    record PaginaDeSujetos(int totalPaginas, List<SujetoProceso> sujetos) {
    }
}
