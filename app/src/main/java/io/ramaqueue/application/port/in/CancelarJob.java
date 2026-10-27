package io.ramaqueue.application.port.in;

import java.util.UUID;

public interface CancelarJob {

    enum Resultado { CANCELADO, NO_EXISTE, YA_TERMINO }

    Resultado cancelar(UUID jobId);
}
