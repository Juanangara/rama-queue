package io.ramaqueue.application.port.in;

public interface PublicarPendientes {

    /** Publica un lote del outbox. Devuelve cuántos ítems se publicaron. */
    int publicarLote();
}
