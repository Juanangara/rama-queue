package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.policy.RepartoJusto.Trabajo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RepartoJustoTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    private static final UUID GRANDE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PEQUENO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTRO = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final Map<UUID, UUID> dueno = new java.util.HashMap<>();

    /** Un trabajo con {@code n} ítems; recuerda de quién es cada ítem para poder contarlos después. */
    private Trabajo trabajo(UUID id, long segundos, long servicio, int n) {
        List<UUID> ids = IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
        ids.forEach(item -> dueno.put(item, id));
        return new Trabajo(id, T0.plusSeconds(segundos), servicio, ids);
    }

    private Map<UUID, Long> porTrabajo(List<UUID> elegidos) {
        return elegidos.stream().collect(Collectors.groupingBy(dueno::get, Collectors.counting()));
    }

    @Test
    void sinCupoOSinTrabajosNoElige() {
        assertThat(RepartoJusto.elegir(List.of(trabajo(GRANDE, 0, 0, 5)), 0, 1)).isEmpty();
        assertThat(RepartoJusto.elegir(List.of(), 10, 3)).isEmpty();
        assertThat(RepartoJusto.elegir(List.of(trabajo(GRANDE, 0, 0, 0)), 10, 3)).isEmpty();
    }

    @Test
    void unTrabajoNuevoSeAdelantaAlQueYaConsumioMuchoServicio() {
        Trabajo grande = trabajo(GRANDE, 0, 5_000, 200);
        Trabajo pequeno = trabajo(PEQUENO, 100, 0, 4);

        List<UUID> elegidos = RepartoJusto.elegir(List.of(grande, pequeno), 10, 3);

        // El pequeño tiene solo 4 ítems y salen todos en esta tanda; el grande se queda con el resto del cupo.
        assertThat(porTrabajo(elegidos)).containsEntry(PEQUENO, 4L).containsEntry(GRANDE, 6L);
        // Y el pequeño sale antes: sus 4 ítems están entre los primeros publicados, no detrás de los del grande.
        assertThat(elegidos.subList(0, 3)).anyMatch(id -> dueno.get(id).equals(PEQUENO));
    }

    @Test
    void conElMismoServicioReparteAPartesIguales() {
        Trabajo a = trabajo(GRANDE, 0, 100, 50);
        Trabajo b = trabajo(PEQUENO, 10, 100, 50);
        Trabajo c = trabajo(OTRO, 20, 100, 50);

        Map<UUID, Long> reparto = porTrabajo(RepartoJusto.elegir(List.of(a, b, c), 30, 0));

        assertThat(reparto).containsEntry(GRANDE, 10L).containsEntry(PEQUENO, 10L).containsEntry(OTRO, 10L);
    }

    @Test
    void laCuotaGarantizadaEvitaQueElTrabajoGrandeSeQuedeSinAvanzar() {
        // El grande ya consumió muchísimo; los pequeños tienen más servicio relativo que… nada: parten de cero y lo absorberían todo.
        Trabajo grande = trabajo(GRANDE, 0, 1_000_000, 100);
        Trabajo p1 = trabajo(PEQUENO, 10, 0, 100);
        Trabajo p2 = trabajo(OTRO, 20, 0, 100);

        Map<UUID, Long> sinCuota = porTrabajo(RepartoJusto.elegir(List.of(grande, p1, p2), 40, 0));
        Map<UUID, Long> conCuota = porTrabajo(RepartoJusto.elegir(List.of(grande, p1, p2), 40, 10));

        assertThat(sinCuota.getOrDefault(GRANDE, 0L)).isZero();
        assertThat(conCuota.get(GRANDE)).isEqualTo(10L); // 25 % de 40
    }

    @Test
    void laCuotaVaAlMasAntiguoYSiNoLeAlcanzaPasaAlSiguiente() {
        Trabajo viejo = trabajo(GRANDE, 0, 999, 2);
        Trabajo medio = trabajo(PEQUENO, 10, 999, 50);

        Map<UUID, Long> reparto = porTrabajo(RepartoJusto.elegir(List.of(medio, viejo), 20, 10));

        assertThat(reparto.get(GRANDE)).isEqualTo(2L); // todo lo que tenía
        assertThat(reparto.get(PEQUENO)).isEqualTo(18L);
    }

    @Test
    void nuncaEligeMasDelCupoNiRepiteItems() {
        List<Trabajo> trabajos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            trabajos.add(trabajo(UUID.randomUUID(), i, i * 10L, 7));
        }
        List<UUID> elegidos = RepartoJusto.elegir(trabajos, 25, 7);

        assertThat(elegidos).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    void sePuedenElegirTodosCuandoElCupoSobra() {
        Trabajo a = trabajo(GRANDE, 0, 0, 3);
        Trabajo b = trabajo(PEQUENO, 1, 0, 2);

        assertThat(RepartoJusto.elegir(List.of(a, b), 100, 25)).hasSize(5);
    }

    @Test
    void dentroDeUnTrabajoSeTomanLosPrimerosSegunElOrdenDePrioridadRecibido() {
        Trabajo unico = trabajo(GRANDE, 0, 0, 10);

        List<UUID> elegidos = RepartoJusto.elegir(List.of(unico), 4, 1);

        // Los cuatro primeros, ni uno más ni uno menos (el que cubre la cuota garantizada sale al final).
        assertThat(elegidos).containsExactlyInAnyOrderElementsOf(unico.items().subList(0, 4));
    }

    @Test
    void esDeterministaAunqueLosTrabajosLleguenEnOtroOrden() {
        Trabajo a = trabajo(GRANDE, 0, 5, 10);
        Trabajo b = trabajo(PEQUENO, 0, 5, 10); // mismo servicio y misma antigüedad: desempata por id
        Trabajo c = trabajo(OTRO, 0, 5, 10);

        List<UUID> uno = RepartoJusto.elegir(List.of(a, b, c), 12, 3);
        List<UUID> otro = RepartoJusto.elegir(List.of(c, a, b), 12, 3);

        assertThat(uno).isEqualTo(otro);
    }
}
