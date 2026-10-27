package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.text.TextNormalizer;

import java.util.List;
import java.util.Optional;

/** Deduce la ciudad de la Rama Judicial a partir del texto del despacho (RN-12). */
public final class CityDetector {

    public static final List<String> CIUDADES = List.of(
            "armenia", "barranquilla", "bogota", "bucaramanga", "buga", "cali", "cartagena", "florencia",
            "ibague", "la dorada", "manizales", "medellin", "monteria", "neiva", "palmira", "pasto", "pereira",
            "popayan", "quibdo", "santa marta", "tunja", "valledupar", "villavicencio");

    private CityDetector() {
    }

    /** Coincide por palabras completas: «cali» no se confunde con «calima». */
    public static Optional<String> detectar(String despacho) {
        String texto = " " + TextNormalizer.normalizar(despacho).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        return CIUDADES.stream().filter(c -> texto.contains(" " + c + " ")).findFirst();
    }
}
