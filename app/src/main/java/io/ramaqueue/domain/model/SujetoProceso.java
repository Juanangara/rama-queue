package io.ramaqueue.domain.model;

/** Un sujeto procesal de un proceso, ya con el espacio en blanco del nombre colapsado. */
public record SujetoProceso(String rol, String nombre) {

    public SujetoProceso {
        rol = rol == null || rol.isBlank() ? "Desconocido" : rol.trim();
        nombre = nombre == null ? "" : nombre.replaceAll("\\s+", " ").trim();
    }

    /** Formato en el que la búsqueda muestra los sujetos: {@code «Demandante: NOMBRE»}. */
    public String comoTexto() {
        return rol + ": " + nombre;
    }
}
