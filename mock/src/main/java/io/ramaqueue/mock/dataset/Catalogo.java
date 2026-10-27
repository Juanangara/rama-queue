package io.ramaqueue.mock.dataset;

import java.util.List;
import java.util.Map;

/** Catálogos fijos de entidades y especialidades del universo sintético. */
final class Catalogo {

    record Entidad(String codigo, String nombre) {
    }

    record Especialidad(String codigo, String nombre) {
    }

    enum Tier { BIG, CAPITAL, SMALL }

    static final Entidad TRIBUNAL = new Entidad("22", "TRIBUNAL SUPERIOR");
    static final Entidad CIRCUITO = new Entidad("31", "JUZGADO DE CIRCUITO ");
    static final Entidad MUNICIPAL = new Entidad("40", "JUZGADO MUNICIPAL");
    static final Entidad PEQUENAS = new Entidad("41", "JUZGADO DE PEQUEÑAS CAUSAS");

    static final Map<String, Entidad> ENTIDADES = Map.of(
            "22", TRIBUNAL, "31", CIRCUITO, "40", MUNICIPAL, "41", PEQUENAS);

    static final Map<String, Especialidad> ESPECIALIDADES = Map.of(
            "03", new Especialidad("03", "CIVIL"),
            "05", new Especialidad("05", "LABORAL"),
            "07", new Especialidad("07", "FAMILIA"),
            "09", new Especialidad("09", "PENAL CON FUNCIÓN DE CONOCIMIENTO"),
            "10", new Especialidad("10", "PENAL CON FUNCIÓN DE CONTROL DE GARANTÍAS"),
            "12", new Especialidad("12", "EJECUCIÓN DE PENAS Y MEDIDAS DE SEGURIDAD"),
            "16", new Especialidad("16", "EXTINCIÓN DE DOMINIO"));

    /** El orden importa: las ciudades pequeñas solo toman las primeras especialidades de cada entidad. */
    private static final Map<String, List<String>> ESPECIALIDADES_POR_ENTIDAD = Map.of(
            "22", List.of("09", "03", "05", "07"),
            "31", List.of("09", "03", "05", "07", "12", "16"),
            "40", List.of("10", "03", "07"),
            "41", List.of("03", "05"));

    private Catalogo() {
    }

    static List<Entidad> entidadesPara(Tier tier) {
        return switch (tier) {
            case BIG -> List.of(TRIBUNAL, CIRCUITO, MUNICIPAL, PEQUENAS);
            case CAPITAL -> List.of(TRIBUNAL, CIRCUITO, MUNICIPAL);
            case SMALL -> List.of(CIRCUITO, MUNICIPAL);
        };
    }

    static List<String> especialidadesPara(String entidad, Tier tier) {
        List<String> todas = ESPECIALIDADES_POR_ENTIDAD.get(entidad);
        int limite = switch (tier) {
            case BIG -> todas.size();
            case CAPITAL -> 3;
            case SMALL -> 2;
        };
        return todas.subList(0, Math.min(limite, todas.size()));
    }

    /** Dos formatos de nombre, ambos observados en la API real. */
    static String nombreDespacho(Entidad entidad, Especialidad especialidad, int numero, String municipio, boolean formatoB) {
        if (formatoB || entidad == TRIBUNAL) {
            return "DESPACHO %03d - %s - %s - %s *".formatted(numero, entidad.nombre().trim(), especialidad.nombre(), municipio);
        }
        return "JUZGADO %03d %s DE %s ".formatted(numero, especialidad.nombre(), municipio);
    }
}
