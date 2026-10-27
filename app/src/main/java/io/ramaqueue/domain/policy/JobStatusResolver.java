package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.ConteosDeTrabajo;
import io.ramaqueue.domain.model.EstadoTrabajo;
import io.ramaqueue.domain.model.JobStatus;

/** RN-12 y RN-16: el estado de un job se deduce de los conteos de sus ítems, nunca de contadores en memoria. */
public final class JobStatusResolver {

    private JobStatusResolver() {
    }

    /**
     * @param conteos      ítems del job por estado
     * @param estadoRaiz   estado del ítem raíz (la consulta inicial); define si hubo cobertura alguna
     * @param cancelado    el usuario pidió cancelar
     */
    public static JobStatus resolver(ConteosDeTrabajo conteos, EstadoTrabajo estadoRaiz, boolean cancelado) {
        if (cancelado) {
            return JobStatus.CANCELLED;
        }
        if (conteos.activos() > 0) {
            return JobStatus.RUNNING;
        }
        if (estadoRaiz == EstadoTrabajo.FAILED || estadoRaiz == EstadoTrabajo.CANCELLED) {
            return JobStatus.FAILED;
        }
        return conteos.conProblema() > 0 ? JobStatus.PARTIAL : JobStatus.COMPLETED;
    }
}
