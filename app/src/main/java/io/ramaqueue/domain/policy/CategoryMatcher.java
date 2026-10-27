package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.text.TextNormalizer;

/** Decide si un proceso pertenece a la categoría elegida, buscando sus palabras clave en el despacho o los sujetos. */
public final class CategoryMatcher {

    private static final String EJECUCION_DE_PENAS = "ejecucion de pena";

    private CategoryMatcher() {
    }

    public static boolean coincide(Categoria categoria, String despacho, String sujetosProcesales) {
        String d = TextNormalizer.normalizar(despacho);
        String s = TextNormalizer.normalizar(sujetosProcesales);
        return categoria.palabrasClave().stream().anyMatch(k -> d.contains(k) || s.contains(k));
    }

    /** D-02: los despachos de ejecución de penas se excluyen solo cuando la categoría es penal. */
    public static boolean excluido(Categoria categoria, String despacho) {
        return categoria == Categoria.PENAL && TextNormalizer.normalizar(despacho).contains(EJECUCION_DE_PENAS);
    }
}
