package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.Territorio;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.Veredicto;
import io.ramaqueue.domain.model.WorkItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Corazón del dominio: decide cómo se explora el árbol de la Rama Judicial (RN-04 a RN-06). Reemplaza el recorrido
 * anidado con {@code sleep} del proyecto anterior por una función pura: dado un ítem y su resultado, devuelve el
 * estado final del ítem y los hijos a crear. No hace entrada/salida, así que se prueba entera en memoria.
 */
public final class ExpansionPlanner {

    private final Territorio territorio;

    public ExpansionPlanner(Territorio territorio) {
        this.territorio = territorio;
    }

    // ─── Búsqueda ────────────────────────────────────────────────────────────────

    public Plan planificarBusqueda(WorkItem item, RespuestaBusqueda respuesta) {
        requerirTipo(item, TipoTrabajo.SEARCH);
        return switch (respuesta) {
            case RespuestaBusqueda.Exitosa exitosa -> Plan.hecho(item.pagina() == 1
                    ? paginasRestantes(item, exitosa.totalPaginas())
                    : List.of());
            case RespuestaBusqueda.ExcedeMil ignorada -> expandirPorExceso(item);
        };
    }

    private Plan expandirPorExceso(WorkItem item) {
        if (item.pagina() != 1) {
            return Plan.excedido("La API dijo «más de mil» en la página " + item.pagina() + ", que no debería ocurrir");
        }
        Nivel nivel = item.nivel();
        if (nivel.esUltimo()) {
            return Plan.excedido("Más de mil registros en el último nivel (" + item.codigo() + "): la Rama Judicial no los muestra");
        }
        if (nivel == Nivel.RAIZ) {
            List<WorkItem> hijos = new ArrayList<>();
            for (Territorio.Departamento d : territorio.departamentos()) {
                hijos.add(hijoDeBusqueda(item, Nivel.DEPARTAMENTO, d.codigo(), d.nombre()));
            }
            return Plan.expandido(sinRepetidos(hijos));
        }
        if (nivel == Nivel.DEPARTAMENTO) {
            List<Territorio.Municipio> municipios = territorio.municipiosDe(item.codigo());
            if (municipios.isEmpty()) {
                return Plan.excedido("Más de mil registros en un departamento sin municipios mapeados (" + item.codigo() + ")");
            }
            List<WorkItem> hijos = new ArrayList<>();
            for (Territorio.Municipio m : municipios) {
                hijos.add(hijoDeBusqueda(item, Nivel.MUNICIPIO, m.codigo(), m.nombre()));
            }
            return Plan.expandido(sinRepetidos(hijos));
        }
        // MUNICIPIO, ENTIDAD, ESPECIALIDAD: los hijos salen de un listado remoto, que es un ítem aparte.
        return Plan.expandido(List.of(
                WorkItem.nuevo(item.jobId(), item.id(), TipoTrabajo.LIST, nivel, item.codigo(), item.etiqueta(), 1, null)));
    }

    // ─── Listados ────────────────────────────────────────────────────────────────

    /** Un listado vacío deja el propio ítem {@code LIST} en EXCEEDED: el padre ya quedó EXPANDED y no cambia. */
    public Plan planificarListado(WorkItem item, List<ItemListado> crudos) {
        requerirTipo(item, TipoTrabajo.LIST);
        Nivel nivelHijo = item.nivel().siguiente()
                .orElseThrow(() -> new IllegalStateException("Un listado no puede colgar del último nivel"));
        List<ItemListado> elementos = ItemListado.limpiar(crudos);
        if (elementos.isEmpty()) {
            return Plan.excedido("Más de mil registros y el listado de " + nivelHijo + " de " + item.codigo() + " está vacío");
        }
        List<WorkItem> hijos = new ArrayList<>();
        for (ItemListado e : elementos) {
            hijos.add(hijoDeBusqueda(item, nivelHijo, e.codigo(), e.nombre()));
        }
        return Plan.hecho(sinRepetidos(hijos));
    }

    // ─── Procesos encontrados ────────────────────────────────────────────────────

    /** Trabajo que genera un proceso encontrado según el veredicto del clasificador. */
    public List<WorkItem> trabajoParaProceso(WorkItem origen, long idProceso, Veredicto veredicto) {
        return switch (veredicto) {
            case RECHAZADO -> List.of();
            case REQUIERE_SUJETOS -> List.of(porProceso(origen.jobId(), origen.id(), TipoTrabajo.RESOLVE_SUJETOS, 1, idProceso));
            case ACEPTADO -> tareasDeDetalle(origen.jobId(), origen.id(), idProceso);
        };
    }

    /** RN-11: el enriquecimiento de un proceso aceptado son tres ítems, uno por petición. */
    public List<WorkItem> tareasDeDetalle(UUID jobId, UUID padreId, long idProceso) {
        return List.of(
                porProceso(jobId, padreId, TipoTrabajo.DETALLE, 1, idProceso),
                porProceso(jobId, padreId, TipoTrabajo.ACTUACIONES, 1, idProceso),
                porProceso(jobId, padreId, TipoTrabajo.SUJETOS, 1, idProceso));
    }

    /** Los sujetos de un proceso aceptado se paginan: la página 1 crea las demás. */
    public Plan planificarSujetos(WorkItem item, int totalPaginas) {
        requerirTipo(item, TipoTrabajo.SUJETOS);
        return Plan.hecho(item.pagina() == 1 ? paginasRestantes(item, totalPaginas) : List.of());
    }

    /**
     * Resolución de sujetos truncados, página a página: si lo acumulado ya hace aceptar el proceso se crea su
     * detalle y se corta la cadena; si no y quedan páginas se pide la siguiente; si no, el proceso se descarta.
     */
    public Plan planificarResolucion(WorkItem item, int totalPaginas, boolean aceptado) {
        requerirTipo(item, TipoTrabajo.RESOLVE_SUJETOS);
        if (aceptado) {
            return Plan.hecho(tareasDeDetalle(item.jobId(), item.id(), item.idProceso()));
        }
        if (item.pagina() < totalPaginas) {
            return Plan.hecho(List.of(
                    porProceso(item.jobId(), item.id(), TipoTrabajo.RESOLVE_SUJETOS, item.pagina() + 1, item.idProceso())));
        }
        return Plan.hecho(List.of());
    }

    // ─── Construcción de ítems ───────────────────────────────────────────────────

    private static List<WorkItem> paginasRestantes(WorkItem primera, int totalPaginas) {
        List<WorkItem> paginas = new ArrayList<>();
        for (int p = 2; p <= totalPaginas; p++) {
            paginas.add(WorkItem.nuevo(primera.jobId(), primera.id(), primera.tipo(), primera.nivel(),
                    primera.codigo(), primera.etiqueta(), p, primera.idProceso()));
        }
        return paginas;
    }

    private static WorkItem hijoDeBusqueda(WorkItem padre, Nivel nivel, String codigo, String etiqueta) {
        return WorkItem.nuevo(padre.jobId(), padre.id(), TipoTrabajo.SEARCH, nivel, codigo, etiqueta, 1, null);
    }

    private static WorkItem porProceso(UUID jobId, UUID padreId, TipoTrabajo tipo, int pagina, long idProceso) {
        return WorkItem.nuevo(jobId, padreId, tipo, null, "", null, pagina, idProceso);
    }

    private static List<WorkItem> sinRepetidos(List<WorkItem> items) {
        Map<UUID, WorkItem> porId = new LinkedHashMap<>();
        for (WorkItem i : items) {
            porId.putIfAbsent(i.id(), i);
        }
        return List.copyOf(porId.values());
    }

    private static void requerirTipo(WorkItem item, TipoTrabajo esperado) {
        if (item.tipo() != esperado) {
            throw new IllegalArgumentException("Se esperaba un ítem " + esperado + " y llegó " + item.tipo());
        }
    }
}
