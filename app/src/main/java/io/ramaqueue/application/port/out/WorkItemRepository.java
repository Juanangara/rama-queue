package io.ramaqueue.application.port.out;

import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.GrupoDeCola;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.model.WorkItem;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de los ítems de trabajo. Postgres es la fuente de verdad del árbol y hace de outbox. */
public interface WorkItemRepository {

    /** Inserta los ítems que no existan (los ids son deterministas). Devuelve cuántos eran nuevos. */
    int insertarNuevos(Collection<WorkItem> items);

    Optional<WorkItem> buscar(UUID id);

    /**
     * Toma el ítem para procesarlo: lo pasa a IN_PROGRESS y devuelve la copia reclamada. Vacío si ya es terminal
     * o si otro worker lo tiene en curso. Un ítem en curso solo se toma si el mensaje es una reentrega (el consumidor
     * anterior murió sin ack) o si el reclamo anterior es más viejo que {@code obsolescencia}.
     */
    Optional<WorkItem> reclamar(UUID id, Instant ahora, boolean esReentrega, Duration obsolescencia);

    /**
     * Guarda el resultado de un ítem reclamado solo si el reclamo sigue vigente (sigue IN_PROGRESS con el mismo
     * {@code bloqueadoEn}). Devuelve {@code false} si se perdió el reclamo; el llamador debe deshacer la transacción.
     */
    boolean confirmar(WorkItem reclamado, WorkItem resultado);

    /** Guardado incondicional (watchdog, republicación). */
    void guardar(WorkItem item);

    /** Lo que un trabajo tiene sin publicar, para el reparto justo: sus ítems (ordenados por prioridad) y el servicio que ya recibió. */
    record PendientesDeTrabajo(UUID jobId, Instant creadoEn, long servicio, List<WorkItem> items) {
    }

    /**
     * Hasta {@code porTrabajo} ítems PENDING sin publicar de cada trabajo, del {@code grupo} de cola dado, bloqueados con
     * {@code FOR UPDATE SKIP LOCKED}: varios relays no se pisan. Los que no se publiquen se liberan al cerrar la transacción.
     */
    List<PendientesDeTrabajo> bloquearCandidatosDePublicacion(GrupoDeCola grupo, int porTrabajo);

    /** Ítems publicados que siguen PENDING: lo que hay en (o camino de) el broker para ese grupo. Exacto, sin la API de gestión. */
    long publicadosEnElBroker(GrupoDeCola grupo);

    void marcarPublicados(Collection<UUID> ids, Instant ahora);

    /** Ítems IN_PROGRESS con el reclamo anterior a {@code reclamadosAntesDe}, bloqueados para el watchdog. */
    List<WorkItem> bloquearAtascados(Instant reclamadosAntesDe, int limite);

    /** PENDING ya publicados antes de {@code publicadosAntesDe} vuelven al outbox. Devuelve cuántos. */
    int marcarParaRepublicar(boolean deBusqueda, Instant publicadosAntesDe, Instant ahora);

    ConteosDeTrabajo conteos(UUID jobId);

    boolean hayActivos(UUID jobId);

    /** Un nodo del árbol de búsqueda (solo ítems SEARCH y LIST de la primera página, con sus páginas agregadas). */
    record NodoDeArbol(UUID id, UUID parentId, TipoTrabajo tipo, Nivel nivel, String codigo, String etiqueta,
                       EstadoTrabajo estado, int intentos, Integer totalRegistros, Integer totalPaginas,
                       long paginasTerminadas, String ultimoError) {
    }

    List<NodoDeArbol> arbol(UUID jobId, int limite);

    /** Cancela de golpe los ítems PENDING del job. Los que estén en curso se cancelan solos al terminar o al reclamarse sus hijos. */
    int cancelarPendientes(UUID jobId);

    /** Los ítems FAILED del job, o de todos los jobs si {@code jobId} es {@code null}. */
    List<WorkItem> fallidos(UUID jobId);
}
