package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.TipoTrabajo;

/** RN-13: traduce un código HTTP a su significado de negocio según el tipo de ítem. */
public final class HttpOutcomeClassifier {

    /** Estado que se informa cuando no hubo respuesta HTTP (timeout, conexión caída, JSON ilegible). */
    public static final int SIN_RESPUESTA = -1;

    private HttpOutcomeClassifier() {
    }

    public static ResultadoHttp clasificar(TipoTrabajo tipo, int estado) {
        if (estado == 200) {
            return ResultadoHttp.OK;
        }
        if (estado == 403 || estado == 429) {
            return ResultadoHttp.BLOQUEADO;
        }
        if (estado == SIN_RESPUESTA || estado == 408 || estado >= 500) {
            return ResultadoHttp.TRANSITORIO;
        }
        if (estado == 400 && tipo == TipoTrabajo.SEARCH) {
            return ResultadoHttp.EXCEDE_MIL;
        }
        if (estado == 404 && tipo == TipoTrabajo.LIST) {
            return ResultadoHttp.LISTA_VACIA;
        }
        return ResultadoHttp.RECHAZO_DEFINITIVO;
    }
}
