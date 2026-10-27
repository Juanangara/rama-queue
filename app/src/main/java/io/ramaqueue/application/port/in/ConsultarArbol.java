package io.ramaqueue.application.port.in;

import io.ramaqueue.application.port.out.WorkItemRepository.NodoDeArbol;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultarArbol {

    /** El árbol de búsqueda del job (vacío si el job no existe). Es lo que dibuja el dashboard. */
    Optional<List<NodoDeArbol>> arbol(UUID jobId);
}
