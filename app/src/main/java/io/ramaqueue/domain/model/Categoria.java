package io.ramaqueue.domain.model;

import java.util.List;

/** Tipo de caso que el usuario elige (uno solo). Las palabras clave están normalizadas (minúsculas, sin tildes). */
public enum Categoria {
    PENAL("penal", List.of("penal")),
    CIVIL("civil", List.of("civil")),
    LABORAL("laboral", List.of("laboral")),
    FAMILIAR("familiar", List.of("familia", "familiar")),
    EXTINCION_DOMINIO("extincionDominio", List.of("extincion de dominio", "extincion"));

    private final String codigo;
    private final List<String> palabrasClave;

    Categoria(String codigo, List<String> palabrasClave) {
        this.codigo = codigo;
        this.palabrasClave = palabrasClave;
    }

    public String codigo() {
        return codigo;
    }

    public List<String> palabrasClave() {
        return palabrasClave;
    }

    public static Categoria desdeCodigo(String codigo) {
        for (Categoria c : values()) {
            if (c.codigo.equals(codigo)) {
                return c;
            }
        }
        throw new EntradaInvalidaException("Categoría desconocida: '" + codigo + "'");
    }
}
