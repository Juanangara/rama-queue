package io.ramaqueue.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Unidad de trabajo (1 ítem = 1 petición HTTP) y, a la vez, fila del outbox: {@code publicadoEn == null}
 * significa que aún no se publicó en RabbitMQ. Inmutable: cada transición devuelve una copia nueva.
 */
public record WorkItem(
        UUID id,
        UUID jobId,
        UUID parentId,
        TipoTrabajo tipo,
        Nivel nivel,
        String codigo,
        String etiqueta,
        int pagina,
        Long idProceso,
        EstadoTrabajo estado,
        int intentos,
        int prioridad,
        Instant publicadoEn,
        Instant bloqueadoEn,
        String ultimoError,
        Integer totalRegistros,
        Integer totalPaginas) {

    public WorkItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(estado, "estado");
        codigo = codigo == null ? "" : codigo.trim();
        if (pagina < 1) {
            throw new EntradaInvalidaException("La página debe ser >= 1");
        }
    }

    /** Ítem recién creado: {@code PENDING}, sin publicar, con id determinista. */
    public static WorkItem nuevo(UUID jobId, UUID parentId, TipoTrabajo tipo, Nivel nivel, String codigo,
                                 String etiqueta, int pagina, Long idProceso) {
        String limpio = codigo == null ? "" : codigo.trim();
        int prioridad = tipo.esDeBusqueda() && nivel != null ? nivel.profundidad() : 0;
        return new WorkItem(WorkItemId.de(jobId, tipo, limpio, pagina, idProceso), jobId, parentId, tipo, nivel,
                limpio, etiqueta, pagina, idProceso, EstadoTrabajo.PENDING, 0, prioridad,
                null, null, null, null, null);
    }

    // ─── Transiciones ────────────────────────────────────────────────────────────

    /** También se permite {@code IN_PROGRESS → IN_PROGRESS}: reentrega tras la caída de un worker. */
    public WorkItem iniciar(Instant ahora) {
        return con(EstadoTrabajo.IN_PROGRESS, intentos, publicadoEn, ahora, ultimoError, totalRegistros, totalPaginas);
    }

    public WorkItem completar(Integer registros, Integer paginas) {
        return con(EstadoTrabajo.DONE, intentos, publicadoEn, null, ultimoError, registros, paginas);
    }

    public WorkItem expandir(Integer registros, Integer paginas) {
        return con(EstadoTrabajo.EXPANDED, intentos, publicadoEn, null, ultimoError, registros, paginas);
    }

    public WorkItem excedido(String motivo) {
        return con(EstadoTrabajo.EXCEEDED, intentos, publicadoEn, null, motivo, totalRegistros, totalPaginas);
    }

    public WorkItem fallar(String error) {
        return con(EstadoTrabajo.FAILED, intentos, publicadoEn, null, error, totalRegistros, totalPaginas);
    }

    public WorkItem cancelar() {
        return con(EstadoTrabajo.CANCELLED, intentos, publicadoEn, null, ultimoError, totalRegistros, totalPaginas);
    }

    /** Fallo transitorio del ítem: consume un intento y vuelve a la cola de reintentos. */
    public WorkItem reintentar(String error) {
        return con(EstadoTrabajo.PENDING, intentos + 1, publicadoEn, null, error, totalRegistros, totalPaginas);
    }

    /** El servidor nos bloqueó (403): es culpa del carril, no del ítem, así que no consume un intento. */
    public WorkItem devolverPorBloqueo() {
        return con(EstadoTrabajo.PENDING, intentos, publicadoEn, null, "Bloqueado por el servidor (403)",
                totalRegistros, totalPaginas);
    }

    /** Watchdog: un worker cayó con el ítem en curso. Se vuelve a publicar vía outbox. */
    public WorkItem liberarPorAtasco() {
        return con(EstadoTrabajo.PENDING, intentos, null, null, "Liberado por el watchdog", totalRegistros, totalPaginas);
    }

    /** Reencolar desde la DLQ (D-05): vuelve a empezar con los intentos en cero. */
    public WorkItem reencolar() {
        return con(EstadoTrabajo.PENDING, 0, null, null, null, totalRegistros, totalPaginas);
    }

    /** Un ítem pendiente cuyo mensaje se perdió (o no se pudo enviar) vuelve al outbox para publicarse de nuevo. */
    public WorkItem paraRepublicar() {
        if (estado != EstadoTrabajo.PENDING) {
            throw new TransicionInvalidaException(estado, EstadoTrabajo.PENDING);
        }
        return new WorkItem(id, jobId, parentId, tipo, nivel, codigo, etiqueta, pagina, idProceso, estado,
                intentos, prioridad, null, bloqueadoEn, ultimoError, totalRegistros, totalPaginas);
    }

    public WorkItem marcarPublicado(Instant ahora) {
        return new WorkItem(id, jobId, parentId, tipo, nivel, codigo, etiqueta, pagina, idProceso, estado,
                intentos, prioridad, ahora, bloqueadoEn, ultimoError, totalRegistros, totalPaginas);
    }

    // ─── Consultas ───────────────────────────────────────────────────────────────

    public boolean esTerminal() {
        return estado.esTerminal();
    }

    public boolean pendienteDePublicar() {
        return estado == EstadoTrabajo.PENDING && publicadoEn == null;
    }

    public boolean estaAtascado(Instant ahora, Duration umbral) {
        return estado == EstadoTrabajo.IN_PROGRESS && bloqueadoEn != null
                && Duration.between(bloqueadoEn, ahora).compareTo(umbral) > 0;
    }

    private WorkItem con(EstadoTrabajo nuevo, int nuevosIntentos, Instant nuevoPublicado, Instant nuevoBloqueado,
                         String error, Integer registros, Integer paginas) {
        if (!estado.puedePasarA(nuevo)) {
            throw new TransicionInvalidaException(estado, nuevo);
        }
        return new WorkItem(id, jobId, parentId, tipo, nivel, codigo, etiqueta, pagina, idProceso, nuevo,
                nuevosIntentos, prioridad, nuevoPublicado, nuevoBloqueado, error, registros, paginas);
    }
}
