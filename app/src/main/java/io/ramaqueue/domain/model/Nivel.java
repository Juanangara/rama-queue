package io.ramaqueue.domain.model;

import java.util.Optional;

/** Niveles del árbol territorial de la Rama Judicial. Los códigos son jerárquicos por prefijo. */
public enum Nivel {
    RAIZ(0),
    DEPARTAMENTO(2),
    MUNICIPIO(5),
    ENTIDAD(7),
    ESPECIALIDAD(9),
    DESPACHO(12);

    private final int longitudCodigo;

    Nivel(int longitudCodigo) {
        this.longitudCodigo = longitudCodigo;
    }

    public int longitudCodigo() {
        return longitudCodigo;
    }

    /** Más profundo = más prioridad en la cola: se terminan ramas completas antes de abrir otras. */
    public int profundidad() {
        return ordinal();
    }

    public boolean esUltimo() {
        return this == DESPACHO;
    }

    public Optional<Nivel> siguiente() {
        return esUltimo() ? Optional.empty() : Optional.of(values()[ordinal() + 1]);
    }

    /** Los hijos de estos niveles no son estáticos: se obtienen de un endpoint {@code Lista/*}. */
    public boolean hijosPorListado() {
        return this == MUNICIPIO || this == ENTIDAD || this == ESPECIALIDAD;
    }

    public static Nivel deCodigo(String codigo) {
        int longitud = codigo == null ? 0 : codigo.trim().length();
        for (Nivel n : values()) {
            if (n.longitudCodigo == longitud) {
                return n;
            }
        }
        throw new IllegalArgumentException("Código sin nivel conocido: '" + codigo + "'");
    }
}
