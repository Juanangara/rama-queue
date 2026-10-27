package io.ramaqueue.infrastructure.in.web;

import io.ramaqueue.infrastructure.in.scheduler.ControlDeConsumidores;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mandos de operación y de demostración. Pausar, reanudar y cambiar la concurrencia llegan a los workers a través de Redis.
 * «Matar un worker» solo funciona con el perfil {@code demo}.
 */
@RestController
@RequestMapping("/api/admin")
@Profile("api")
class AdminController {

    private static final Set<String> LISTENERS = Set.of("search-listener", "detail-listener");

    private final ControlDeConsumidores control;
    private final Environment entorno;

    AdminController(ControlDeConsumidores control, Environment entorno) {
        this.control = control;
        this.entorno = entorno;
    }

    @GetMapping("/consumidores")
    Map<String, Object> estado() {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("pausados", control.estaPausado());
        for (String listener : LISTENERS) {
            estado.put(listener + ".concurrenciaPedida", control.concurrenciaDeseada(listener).orElse(null));
        }
        return estado;
    }

    @PostMapping("/consumidores/pausar")
    Map<String, Object> pausar() {
        control.pausar();
        return estado();
    }

    @PostMapping("/consumidores/reanudar")
    Map<String, Object> reanudar() {
        control.reanudar();
        return estado();
    }

    @PostMapping("/consumidores/concurrencia")
    ResponseEntity<Map<String, Object>> concurrencia(@RequestParam String listener, @RequestParam int consumidores) {
        if (!LISTENERS.contains(listener)) {
            return ResponseEntity.badRequest().body(Map.of("error", "listener debe ser uno de " + LISTENERS));
        }
        control.fijarConcurrencia(listener, consumidores);
        return ResponseEntity.ok(estado());
    }

    /** Demo: un worker (el primero que lo vea) se detiene de golpe, sin ack; Docker lo reinicia y el broker reentrega. */
    @PostMapping("/workers/caida")
    ResponseEntity<Map<String, String>> caida() {
        if (!entorno.acceptsProfiles(Profiles.of("demo"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Solo disponible con el perfil «demo»"));
        }
        control.solicitarCaidaDeUnWorker();
        return ResponseEntity.accepted().body(Map.of("estado", "un worker se detendrá en su próximo ciclo"));
    }
}
