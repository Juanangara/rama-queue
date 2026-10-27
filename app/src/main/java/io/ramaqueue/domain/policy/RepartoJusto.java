package io.ramaqueue.domain.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Decide qué ítems pendientes se publican ahora cuando hay varios trabajos compitiendo por el mismo presupuesto de peticiones.
 *
 * <p>Regla: <b>menos servicio recibido primero</b>, con una <b>cuota garantizada</b> para el trabajo más antiguo. El «servicio»
 * es lo que un trabajo ya consumió (peticiones hechas): no se conoce el tamaño de un trabajo de antemano, así que un trabajo nuevo
 * empieza en cero y se adelanta, y uno grande pierde la ventaja a medida que consume. La cuota garantizada evita el hambre: un goteo
 * de trabajos pequeños no puede dejar sin avanzar al grande.
 *
 * <p>Función pura y determinista: mismos datos, mismo resultado (los empates se rompen por antigüedad y luego por id).
 */
public final class RepartoJusto {

    /** Lo que un trabajo tiene listo para publicar: sus ítems ya ordenados por prioridad dentro del trabajo. */
    public record Trabajo(UUID jobId, Instant creadoEn, long servicio, List<UUID> items) {
        public Trabajo {
            items = List.copyOf(items);
        }
    }

    private RepartoJusto() {
    }

    /**
     * @param cupo              cuántos ítems se pueden publicar en total
     * @param garantizados      cuántos de esos ítems van al trabajo más antiguo con ítems (la cuota que evita el hambre; ver
     *                          {@link CuotaGarantizada}, que la calcula sin sesgo de redondeo)
     * @return los ids elegidos, en el orden en que conviene publicarlos; nunca más de {@code cupo}
     */
    public static List<UUID> elegir(List<Trabajo> trabajos, int cupo, int garantizados) {
        if (cupo <= 0 || trabajos.isEmpty()) {
            return List.of();
        }
        Comparator<Trabajo> porAntiguedad = Comparator.comparing(Trabajo::creadoEn).thenComparing(Trabajo::jobId);
        List<Trabajo> conItems = trabajos.stream().filter(t -> !t.items().isEmpty()).sorted(porAntiguedad).toList();
        if (conItems.isEmpty()) {
            return List.of();
        }

        List<UUID> elegidos = new ArrayList<>();
        List<UUID> garantizadosElegidos = new ArrayList<>();
        Map<UUID, Integer> tomados = new HashMap<>();

        // 1) Cuota garantizada: del más antiguo hacia el más nuevo hasta cubrirla.
        int porGarantizar = Math.max(0, Math.min(cupo, garantizados));
        for (Trabajo trabajo : conItems) {
            while (porGarantizar > 0 && tomados.getOrDefault(trabajo.jobId(), 0) < trabajo.items().size()) {
                tomar(trabajo, tomados, garantizadosElegidos);
                porGarantizar--;
            }
        }

        // 2) El resto, siempre al trabajo con menos servicio (el recibido antes + lo elegido en esta tanda).
        Comparator<Trabajo> porServicio = Comparator
                .comparingLong((Trabajo t) -> t.servicio() + tomados.getOrDefault(t.jobId(), 0))
                .thenComparing(porAntiguedad);
        PriorityQueue<Trabajo> cola = new PriorityQueue<>(porServicio);
        for (Trabajo trabajo : conItems) {
            if (tomados.getOrDefault(trabajo.jobId(), 0) < trabajo.items().size()) {
                cola.add(trabajo);
            }
        }
        while (garantizadosElegidos.size() + elegidos.size() < cupo && !cola.isEmpty()) {
            Trabajo siguiente = cola.poll();
            tomar(siguiente, tomados, elegidos);
            if (tomados.get(siguiente.jobId()) < siguiente.items().size()) {
                cola.add(siguiente); // su servicio subió: se recoloca
            }
        }
        // Primero salen los de quien menos ha recibido; los de la cuota garantizada (que evitan el hambre, no corren prisa) van al final.
        List<UUID> resultado = new ArrayList<>(elegidos);
        resultado.addAll(garantizadosElegidos);
        return List.copyOf(resultado);
    }

    private static void tomar(Trabajo trabajo, Map<UUID, Integer> tomados, List<UUID> elegidos) {
        int indice = tomados.getOrDefault(trabajo.jobId(), 0);
        elegidos.add(trabajo.items().get(indice));
        tomados.put(trabajo.jobId(), indice + 1);
    }
}
