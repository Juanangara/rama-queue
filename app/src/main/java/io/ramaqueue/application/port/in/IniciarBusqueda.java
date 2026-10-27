package io.ramaqueue.application.port.in;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.TipoPersona;

import java.util.UUID;

public interface IniciarBusqueda {

    /** Valida la entrada, crea el job y su ítem raíz. Lanza {@code EntradaInvalidaException} si no es válida. */
    UUID iniciar(String nombre, TipoPersona tipoPersona, Categoria categoria);
}
