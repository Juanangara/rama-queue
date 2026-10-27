package io.ramaqueue.motor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.policy.RespuestaBusqueda;
import io.ramaqueue.domain.simulacion.MundoSintetico;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * La Rama Judicial en miniatura sobre HTTP real: sirve el {@link MundoSintetico} con el mismo contrato que la API
 * verdadera (400 por exceso, listados, detalle…), puede bloquear con 403 y mide cuánta presión recibe.
 */
final class RamaFalsa implements AutoCloseable {

    private static final String PREFIJO = "/api/v2";

    private final HttpServer servidor;
    private final ObjectMapper json = new ObjectMapper();
    private volatile MundoSintetico mundo;

    private volatile boolean detalleRoto;
    private final AtomicLong bloqueadoHasta = new AtomicLong();
    private final AtomicInteger enVuelo = new AtomicInteger();
    private final AtomicInteger pico = new AtomicInteger();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger bloqueadas = new AtomicInteger();

    private RamaFalsa(HttpServer servidor) {
        this.servidor = servidor;
    }

    static RamaFalsa iniciar() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RamaFalsa rama = new RamaFalsa(servidor);
            servidor.createContext(PREFIJO + "/", rama::atender);
            servidor.setExecutor(Executors.newCachedThreadPool());
            servidor.start();
            return rama;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo levantar la Rama falsa", e);
        }
    }

    String urlBase() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + PREFIJO;
    }

    void usar(MundoSintetico nuevo) {
        this.mundo = nuevo;
    }

    void bloquearDurante(Duration duracion) {
        bloqueadoHasta.set(System.currentTimeMillis() + duracion.toMillis());
    }

    /** Con {@code true}, el detalle de cualquier proceso responde 404: un fallo definitivo que lleva el ítem a la DLQ. */
    void romperDetalle(boolean roto) {
        this.detalleRoto = roto;
    }

    void reiniciar() {
        detalleRoto = false;
        bloqueadoHasta.set(0);
        enVuelo.set(0);
        pico.set(0);
        total.set(0);
        bloqueadas.set(0);
    }

    int peticiones() {
        return total.get();
    }

    int picoEnVuelo() {
        return pico.get();
    }

    int respuestas403() {
        return bloqueadas.get();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    // ─── Atención de peticiones ──────────────────────────────────────────────────

    private void atender(HttpExchange intercambio) throws IOException {
        int actual = enVuelo.incrementAndGet();
        pico.accumulateAndGet(actual, Math::max);
        total.incrementAndGet();
        try {
            if (System.currentTimeMillis() < bloqueadoHasta.get()) {
                bloqueadas.incrementAndGet();
                responder(intercambio, 403, "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Thread.sleep(2); // una latencia mínima hace medible la concurrencia
            String ruta = intercambio.getRequestURI().getPath().substring(PREFIJO.length());
            Map<String, String> consulta = parsear(intercambio.getRequestURI().getRawQuery());
            enrutar(intercambio, ruta, consulta);
        } catch (RuntimeException | InterruptedException e) {
            responder(intercambio, 500, ("error: " + e).getBytes(StandardCharsets.UTF_8));
        } finally {
            enVuelo.decrementAndGet();
        }
    }

    private void enrutar(HttpExchange intercambio, String ruta, Map<String, String> consulta) throws IOException {
        MundoSintetico m = mundo;
        if (ruta.equals("/Procesos/Consulta/NombreRazonSocial")) {
            int pagina = Integer.parseInt(consulta.getOrDefault("pagina", "1"));
            MundoSintetico.ResultadoPagina r = m.buscar(consulta.getOrDefault("codificacionDespacho", ""), pagina);
            if (r.respuesta() instanceof RespuestaBusqueda.ExcedeMil) {
                responderJson(intercambio, 400, Map.of("StatusCode", 400, "Message", "Hay más de mil registros"));
                return;
            }
            RespuestaBusqueda.Exitosa exito = (RespuestaBusqueda.Exitosa) r.respuesta();
            List<Object> procesos = new ArrayList<>();
            for (ProcesoEncontrado p : r.procesos()) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("idProceso", p.idProceso());
                fila.put("llaveProceso", p.llaveProceso());
                fila.put("despacho", p.despacho());
                fila.put("departamento", p.departamento());
                fila.put("sujetosProcesales", p.sujetosProcesales());
                fila.put("fechaProceso", p.fechaProceso());
                fila.put("fechaUltimaActuacion", p.fechaUltimaActuacion());
                procesos.add(fila);
            }
            responderJson(intercambio, 200, Map.of(
                    "procesos", procesos,
                    "paginacion", Map.of("cantidadRegistros", exito.totalRegistros(), "cantidadPaginas", exito.totalPaginas(),
                            "registrosPagina", MundoSintetico.POR_PAGINA, "pagina", pagina)));
        } else if (ruta.startsWith("/Lista/")) {
            String codigo = ruta.substring(ruta.lastIndexOf('/') + 1);
            List<Object> lista = new ArrayList<>();
            for (ItemListado i : m.listar(codigo)) {
                lista.add(Map.of("codigo", i.codigo(), "nombre", i.nombre()));
            }
            responderJson(intercambio, 200, lista);
        } else if (ruta.startsWith("/Proceso/Detalle/") && detalleRoto) {
            responder(intercambio, 404, "detalle no disponible".getBytes(StandardCharsets.UTF_8));
        } else if (ruta.startsWith("/Proceso/Detalle/")) {
            responderJson(intercambio, 200, Map.of("tipoProceso", "Penal", "claseProceso", "Ley 906",
                    "subclaseProceso", "Sin Subclase de Proceso", "ponente", "JUEZ FICTICIO"));
        } else if (ruta.startsWith("/Proceso/Actuaciones/")) {
            responderJson(intercambio, 200, Map.of(
                    "actuaciones", List.of(Map.of("consActuacion", 1, "fechaActuacion", "2021-01-01T00:00:00",
                            "actuacion", "Auto", "anotacion", "Anotación", "fechaRegistro", "2021-01-02T00:00:00")),
                    "paginacion", Map.of("cantidadRegistros", 1, "cantidadPaginas", 1)));
        } else if (ruta.startsWith("/Proceso/Sujetos/")) {
            long id = Long.parseLong(ruta.substring(ruta.lastIndexOf('/') + 1));
            int pagina = Integer.parseInt(consulta.getOrDefault("pagina", "1"));
            List<Object> sujetos = new ArrayList<>();
            for (String parte : m.sujetosPagina(id, pagina).split(" \\| ")) {
                int separador = parte.indexOf(": ");
                if (separador > 0) {
                    sujetos.add(Map.of("tipoSujeto", parte.substring(0, separador), "nombreRazonSocial", parte.substring(separador + 2)));
                }
            }
            responderJson(intercambio, 200, Map.of("sujetos", sujetos,
                    "paginacion", Map.of("cantidadPaginas", m.paginasSujetos(id), "pagina", pagina)));
        } else {
            responder(intercambio, 404, "no existe".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void responderJson(HttpExchange intercambio, int estado, Object cuerpo) throws IOException {
        intercambio.getResponseHeaders().add("Content-Type", "application/json");
        responder(intercambio, estado, json.writeValueAsBytes(cuerpo));
    }

    private static void responder(HttpExchange intercambio, int estado, byte[] cuerpo) throws IOException {
        intercambio.sendResponseHeaders(estado, cuerpo.length);
        intercambio.getResponseBody().write(cuerpo);
        intercambio.close();
    }

    private static Map<String, String> parsear(String consulta) {
        Map<String, String> mapa = new HashMap<>();
        if (consulta == null || consulta.isEmpty()) {
            return mapa;
        }
        for (String par : consulta.split("&")) {
            int igual = par.indexOf('=');
            String clave = URLDecoder.decode(igual < 0 ? par : par.substring(0, igual), StandardCharsets.UTF_8);
            String valor = igual < 0 ? "" : URLDecoder.decode(par.substring(igual + 1), StandardCharsets.UTF_8);
            mapa.put(clave, valor);
        }
        return mapa;
    }
}
