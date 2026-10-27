package io.ramaqueue.domain.text;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Minúsculas, sin tildes, espacios colapsados y recortados. */
    public static String normalizar(String texto) {
        return Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Palabras completas (letras y dígitos) del texto normalizado, sin repetidos y en orden de aparición. */
    public static Set<String> palabras(String texto) {
        Set<String> palabras = new LinkedHashSet<>();
        for (String p : normalizar(texto).split("[^a-z0-9]+")) {
            if (!p.isEmpty()) {
                palabras.add(p);
            }
        }
        return palabras;
    }
}
