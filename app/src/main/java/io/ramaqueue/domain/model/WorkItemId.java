package io.ramaqueue.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Identificador determinista: el mismo trabajo lógico siempre produce el mismo UUID, lo que hace idempotente la creación. */
public final class WorkItemId {

    private WorkItemId() {
    }

    public static UUID de(UUID jobId, TipoTrabajo tipo, String codigo, int pagina, Long idProceso) {
        String clave = jobId + "|" + tipo + "|" + (codigo == null ? "" : codigo.trim()) + "|" + pagina
                + "|" + (idProceso == null ? "" : idProceso);
        return UUID.nameUUIDFromBytes(clave.getBytes(StandardCharsets.UTF_8));
    }
}
