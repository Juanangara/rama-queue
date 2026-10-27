package io.ramaqueue.domain.model;

/** Un elemento de {@code procesos[]} tal como lo devuelve la búsqueda. */
public record ProcesoEncontrado(
        long idProceso,
        String llaveProceso,
        String despacho,
        String departamento,
        String sujetosProcesales,
        String fechaProceso,
        String fechaUltimaActuacion) {

    /** La API corta la lista de sujetos con "..." cuando es larga. */
    public boolean sujetosTruncados() {
        return sujetosProcesales != null && sujetosProcesales.contains("...");
    }
}
