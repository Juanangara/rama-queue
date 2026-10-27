package io.ramaqueue.domain.model;

import io.ramaqueue.domain.text.TextNormalizer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Búsqueda pedida por el usuario: un nombre, un tipo de persona y exactamente una categoría. */
public record SearchJob(
        UUID id,
        String nombre,
        TipoPersona tipoPersona,
        Categoria categoria,
        JobStatus estado,
        Instant creadoEn,
        Instant finalizadoEn,
        String error) {

    /** La API exige al menos una palabra de 3 letras; con menos responde 404 (RN-01). */
    public static final int LARGO_MINIMO_PALABRA = 3;

    public static SearchJob nuevo(UUID id, String nombre, TipoPersona tipoPersona, Categoria categoria, Instant ahora) {
        Objects.requireNonNull(tipoPersona, "tipoPersona");
        Objects.requireNonNull(categoria, "categoria");
        String limpio = validarNombre(nombre);
        return new SearchJob(id, limpio, tipoPersona, categoria, JobStatus.RUNNING, ahora, null, null);
    }

    public static String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new EntradaInvalidaException("El nombre es obligatorio");
        }
        boolean hayPalabraUtil = TextNormalizer.palabras(nombre).stream()
                .anyMatch(p -> p.length() >= LARGO_MINIMO_PALABRA);
        if (!hayPalabraUtil) {
            throw new EntradaInvalidaException(
                    "El nombre debe tener al menos una palabra de " + LARGO_MINIMO_PALABRA + " letras");
        }
        return nombre.trim().replaceAll("\\s+", " ");
    }

    public SearchJob finalizar(JobStatus nuevoEstado, Instant ahora, String motivo) {
        if (!nuevoEstado.esFinal()) {
            throw new IllegalArgumentException("Un job se finaliza con un estado final, no " + nuevoEstado);
        }
        return new SearchJob(id, nombre, tipoPersona, categoria, nuevoEstado, creadoEn, ahora, motivo);
    }

    public WorkItem itemRaiz() {
        return WorkItem.nuevo(id, null, TipoTrabajo.SEARCH, Nivel.RAIZ, "", "TODO EL PAÍS", 1, null);
    }
}
