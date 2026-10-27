package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.text.TextNormalizer;

import java.util.Set;

/**
 * Comprueba que el nombre buscado aparezca de verdad en un texto. La búsqueda de la API es full-text y puede
 * devolver coincidencias parciales; aquí se exige que TODAS las palabras del nombre estén presentes como palabras
 * completas, en cualquier orden y con otras palabras en medio (así «JUAN GARCIA» encuentra «JUAN CARLOS GARCIA PEREZ»).
 */
public final class NameMatcher {

    private final Set<String> palabrasBuscadas;

    public NameMatcher(String nombreBuscado) {
        this.palabrasBuscadas = TextNormalizer.palabras(nombreBuscado);
        if (palabrasBuscadas.isEmpty()) {
            throw new IllegalArgumentException("El nombre buscado no tiene palabras");
        }
    }

    /** {@code true} si alguno de los textos contiene todas las palabras del nombre. */
    public boolean coincide(String... textos) {
        for (String texto : textos) {
            if (TextNormalizer.palabras(texto).containsAll(palabrasBuscadas)) {
                return true;
            }
        }
        return false;
    }
}
