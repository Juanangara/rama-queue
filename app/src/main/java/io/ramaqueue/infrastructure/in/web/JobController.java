package io.ramaqueue.infrastructure.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ramaqueue.application.port.in.CancelarJob;
import io.ramaqueue.application.port.in.ConsultarArbol;
import io.ramaqueue.application.port.in.ConsultarProgreso;
import io.ramaqueue.application.port.in.ConsultarResultados;
import io.ramaqueue.application.port.in.IniciarBusqueda;
import io.ramaqueue.application.port.in.ReencolarFallidos;
import io.ramaqueue.application.port.out.ProcesoHitRepository.FiltroDeResultados;
import io.ramaqueue.domain.model.Categoria;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.infrastructure.in.web.JobDtos.CrearJob;
import io.ramaqueue.infrastructure.in.web.JobDtos.JobProgreso;
import io.ramaqueue.infrastructure.in.web.JobDtos.JobResumen;
import io.ramaqueue.infrastructure.in.web.JobDtos.Nodo;
import io.ramaqueue.infrastructure.in.web.JobDtos.PaginaDeProcesos;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@Profile("api")
class JobController {

    private final IniciarBusqueda iniciar;
    private final ConsultarProgreso consultar;
    private final ConsultarResultados resultados;
    private final ConsultarArbol arbol;
    private final CancelarJob cancelar;
    private final ReencolarFallidos reencolar;
    private final SseHub hub;
    private final ObjectMapper json;

    JobController(IniciarBusqueda iniciar, ConsultarProgreso consultar, ConsultarResultados resultados, ConsultarArbol arbol,
                  CancelarJob cancelar, ReencolarFallidos reencolar, SseHub hub, ObjectMapper json) {
        this.iniciar = iniciar;
        this.consultar = consultar;
        this.resultados = resultados;
        this.arbol = arbol;
        this.cancelar = cancelar;
        this.reencolar = reencolar;
        this.hub = hub;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<Map<String, UUID>> crear(@RequestBody CrearJob peticion) {
        UUID id = iniciar.iniciar(peticion.nombre(), TipoPersona.desdeCodigo(peticion.tipoPersona()),
                Categoria.desdeCodigo(peticion.categoria()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id));
    }

    @GetMapping
    List<JobResumen> recientes() {
        return consultar.recientes(50).stream().map(JobResumen::de).toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<JobProgreso> progreso(@PathVariable UUID id) {
        return consultar.progreso(id).map(JobProgreso::de).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** El árbol de búsqueda: de dónde se bajó y por qué (nodos que excedieron, ramas sin respuesta…). */
    @GetMapping("/{id}/tree")
    ResponseEntity<List<Nodo>> arbol(@PathVariable UUID id) {
        return arbol.arbol(id).map(nodos -> ResponseEntity.ok(nodos.stream().map(Nodo::de).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/results")
    ResponseEntity<PaginaDeProcesos> resultados(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "1") int pagina,
                                                @RequestParam(defaultValue = "20") int tamano,
                                                @RequestParam(required = false) String ciudad,
                                                @RequestParam(required = false) String departamento,
                                                @RequestParam(required = false) String texto,
                                                @RequestParam(defaultValue = "false") boolean soloCompletos) {
        FiltroDeResultados filtro = new FiltroDeResultados(ciudad, departamento, texto, soloCompletos);
        return resultados.resultados(id, filtro, pagina, tamano).map(p -> PaginaDeProcesos.de(p, json)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Map<String, String>> cancelar(@PathVariable UUID id) {
        return switch (cancelar.cancelar(id)) {
            case CANCELADO -> ResponseEntity.ok(Map.of("estado", "CANCELLED"));
            case NO_EXISTE -> ResponseEntity.notFound().build();
            case YA_TERMINO -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "El job ya había terminado"));
        };
    }

    /** Vuelve a intentar los ítems fallidos del job (D-05). */
    @PostMapping("/{id}/requeue")
    ResponseEntity<ReencolarFallidos.Resultado> reencolar(@PathVariable UUID id) {
        if (consultar.progreso(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reencolar.reencolar(id));
    }

    /** Progreso en vivo (SSE): una fotografía al conectar, otra cada medio segundo con cambios, y los eventos importantes. */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> eventos(@PathVariable UUID id) {
        if (consultar.progreso(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(hub.suscribir(id));
    }
}
