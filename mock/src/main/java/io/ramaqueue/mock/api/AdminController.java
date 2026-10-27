package io.ramaqueue.mock.api;

import io.ramaqueue.mock.chaos.ChaosState;
import io.ramaqueue.mock.dataset.Dataset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Controles de caos y diagnóstico. No pasan por el filtro de caos. */
@RestController
@RequestMapping("/__admin")
class AdminController {

    private final ChaosState caos;
    private final Dataset dataset;

    AdminController(ChaosState caos, Dataset dataset) {
        this.caos = caos;
        this.dataset = dataset;
    }

    /** Responde 403 a todo durante N segundos (simula un bloqueo del servidor). */
    @PostMapping("/block")
    Map<String, Object> bloquear(@RequestParam(defaultValue = "60") int seconds) {
        caos.bloquear(seconds, System.currentTimeMillis());
        return caos.estadisticas(System.currentTimeMillis());
    }

    @PostMapping("/latency")
    Map<String, Object> latencia(@RequestParam int ms, @RequestParam(defaultValue = "0") int jitterMs) {
        caos.configurarLatencia(ms, jitterMs);
        return caos.estadisticas(System.currentTimeMillis());
    }

    @PostMapping("/limit")
    Map<String, Object> limite(@RequestParam int perMinute) {
        caos.configurarLimite(perMinute);
        return caos.estadisticas(System.currentTimeMillis());
    }

    /** Borra estadísticas y bloqueos y devuelve la configuración a sus valores iniciales. */
    @PostMapping("/reset")
    Map<String, Object> reiniciar() {
        caos.restablecerEstadisticas();
        caos.restablecerConfiguracion();
        return caos.estadisticas(System.currentTimeMillis());
    }

    @GetMapping("/stats")
    Map<String, Object> estadisticas() {
        return caos.estadisticas(System.currentTimeMillis());
    }

    /** Tamaño esperado del árbol de búsqueda para un nombre, sin gastar peticiones ni pasar por el caos. */
    @GetMapping("/dataset")
    ResponseEntity<Object> universo(@RequestParam String nombre, @RequestParam(defaultValue = "nat") String tipoPersona) {
        List<String> tokens = Dataset.tokenizar(nombre);
        return ResponseEntity.ok(dataset.resumen(tokens, tipoPersona.equals("nat")));
    }
}
