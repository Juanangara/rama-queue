package io.ramaqueue.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elemento de un listado {@code Lista/*} (entidad, especialidad o despacho). */
public record ItemListado(String codigo, String nombre) {

    /**
     * Limpia un listado crudo: descarta el «Seleccione ...» de código vacío, recorta los espacios finales
     * de los códigos de despacho y elimina repetidos conservando el orden.
     */
    public static List<ItemListado> limpiar(List<ItemListado> crudos) {
        Map<String, ItemListado> porCodigo = new LinkedHashMap<>();
        for (ItemListado item : crudos) {
            if (item == null || item.codigo() == null || item.codigo().isBlank()) {
                continue;
            }
            String codigo = item.codigo().trim();
            porCodigo.putIfAbsent(codigo, new ItemListado(codigo, item.nombre() == null ? "" : item.nombre().trim()));
        }
        return List.copyOf(porCodigo.values());
    }
}
