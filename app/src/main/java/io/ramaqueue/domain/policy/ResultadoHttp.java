package io.ramaqueue.domain.policy;

/** Significado de negocio de una respuesta HTTP de la Rama Judicial. */
public enum ResultadoHttp {
    OK,
    /** HTTP 400 en una búsqueda: «más de mil registros». No es un error. */
    EXCEDE_MIL,
    /** 403/429: el servidor nos bloquea. Es una señal del carril, no del ítem. */
    BLOQUEADO,
    /** Error pasajero (timeout, 5xx, JSON inválido): se reintenta con backoff. */
    TRANSITORIO,
    /** No tiene sentido reintentar (entrada inválida, proceso inexistente). */
    RECHAZO_DEFINITIVO,
    /** 404 en un listado: no hay elementos. */
    LISTA_VACIA
}
