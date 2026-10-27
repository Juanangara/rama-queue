package io.ramaqueue.domain.model;

public enum TipoPersona {
    NATURAL("nat"),
    JURIDICA("jur");

    private final String codigo;

    TipoPersona(String codigo) {
        this.codigo = codigo;
    }

    /** Valor que espera la API de la Rama Judicial en {@code tipoPersona}. */
    public String codigo() {
        return codigo;
    }

    public static TipoPersona desdeCodigo(String codigo) {
        for (TipoPersona t : values()) {
            if (t.codigo.equals(codigo)) {
                return t;
            }
        }
        throw new EntradaInvalidaException("tipoPersona debe ser 'nat' o 'jur', no '" + codigo + "'");
    }
}
