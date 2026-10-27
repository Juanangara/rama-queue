package io.ramaqueue.infrastructure.out.rama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.ramaqueue.application.port.out.RamaException;
import io.ramaqueue.application.port.out.RamaJudicialGateway;
import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.SujetoProceso;
import io.ramaqueue.domain.model.TipoPersona;
import io.ramaqueue.domain.policy.HttpOutcomeClassifier;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Habla con la API de la Rama Judicial (la real o el mock). Cada método hace exactamente una petición.
 * Todo lo que no sea 200 se convierte en {@link RamaException} con el estado HTTP; los fallos de red, los timeouts y el
 * JSON ilegible se informan como {@link HttpOutcomeClassifier#SIN_RESPUESTA}.
 */
public class HttpRamaJudicialGateway implements RamaJudicialGateway {

    private final HttpClient cliente;
    private final String urlBase;
    private final ObjectMapper json;
    private final Duration esperaBusqueda;
    private final Duration esperaGeneral;
    private final RegistroDePeticiones registro;
    private final MeterRegistry metricas;

    public HttpRamaJudicialGateway(String urlBase, ObjectMapper json, Duration esperaBusqueda, Duration esperaGeneral,
                                   RegistroDePeticiones registro, MeterRegistry metricas) {
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.json = json;
        this.esperaBusqueda = esperaBusqueda;
        this.esperaGeneral = esperaGeneral;
        this.registro = registro;
        this.metricas = metricas;
        this.cliente = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public PaginaDeBusqueda buscar(String nombre, TipoPersona tipoPersona, String codigo, int pagina) {
        String ruta = "/Procesos/Consulta/NombreRazonSocial?nombre=" + codificar(nombre)
                + "&tipoPersona=" + tipoPersona.codigo()
                + "&SoloActivos=false"
                + "&codificacionDespacho=" + codificar(codigo)
                + "&pagina=" + pagina;
        JsonNode raiz = pedir("busqueda", ruta, esperaBusqueda);
        JsonNode paginacion = raiz.path("paginacion");
        List<ProcesoEncontrado> procesos = new ArrayList<>();
        for (JsonNode p : raiz.path("procesos")) {
            procesos.add(new ProcesoEncontrado(p.path("idProceso").asLong(), texto(p, "llaveProceso"), texto(p, "despacho"),
                    texto(p, "departamento"), texto(p, "sujetosProcesales"), texto(p, "fechaProceso"),
                    texto(p, "fechaUltimaActuacion")));
        }
        return new PaginaDeBusqueda(paginacion.path("cantidadRegistros").asInt(0), paginacion.path("cantidadPaginas").asInt(0), procesos);
    }

    @Override
    public List<ItemListado> listar(Nivel nivelPadre, String codigoPadre) {
        String recurso = switch (nivelPadre) {
            case MUNICIPIO -> "Entidades";
            case ENTIDAD -> "Especialidades";
            case ESPECIALIDAD -> "Despachos";
            default -> throw new IllegalArgumentException("No hay listado remoto para los hijos de " + nivelPadre);
        };
        JsonNode raiz = pedir("listado", "/Lista/" + recurso + "/" + codificar(codigoPadre), esperaGeneral);
        List<ItemListado> lista = new ArrayList<>();
        for (JsonNode e : raiz) {
            lista.add(new ItemListado(texto(e, "codigo"), texto(e, "nombre")));
        }
        return lista;
    }

    @Override
    public DetalleProceso detalle(long idProceso) {
        JsonNode d = pedir("detalle", "/Proceso/Detalle/" + idProceso, esperaGeneral);
        return new DetalleProceso(texto(d, "tipoProceso"), texto(d, "claseProceso"), texto(d, "subclaseProceso"), texto(d, "ponente"));
    }

    @Override
    public Optional<UltimaActuacion> ultimaActuacion(long idProceso) {
        JsonNode raiz = pedir("actuaciones", "/Proceso/Actuaciones/" + idProceso + "?pagina=1", esperaGeneral);
        return elementos(raiz, "actuaciones", "idRegActuacion").stream()
                .max(Comparator.comparingLong(a -> a.path("consActuacion").asLong(0)))
                .map(a -> new UltimaActuacion(texto(a, "fechaActuacion"), texto(a, "actuacion"), texto(a, "anotacion"),
                        texto(a, "fechaRegistro")));
    }

    @Override
    public PaginaDeSujetos sujetos(long idProceso, int pagina) {
        JsonNode raiz = pedir("sujetos", "/Proceso/Sujetos/" + idProceso + "?pagina=" + pagina, esperaGeneral);
        List<SujetoProceso> sujetos = new ArrayList<>();
        for (JsonNode s : elementos(raiz, "sujetos", "nombreRazonSocial")) {
            if (!s.path("nombreRazonSocial").asText("").isBlank()) {
                sujetos.add(new SujetoProceso(texto(s, "tipoSujeto"), texto(s, "nombreRazonSocial")));
            }
        }
        return new PaginaDeSujetos(Math.max(1, raiz.path("paginacion").path("cantidadPaginas").asInt(1)), sujetos);
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────────

    private JsonNode pedir(String endpoint, String ruta, Duration espera) {
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(urlBase + ruta))
                .timeout(espera)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", "https://consultaprocesos.ramajudicial.gov.co")
                .header("Referer", "https://consultaprocesos.ramajudicial.gov.co/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0")
                .GET().build();
        long inicio = System.nanoTime();
        Integer estado = null;
        String resultado = "error";
        try {
            HttpResponse<String> respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            estado = respuesta.statusCode();
            if (estado != 200) {
                resultado = estado == 403 || estado == 429 ? "bloqueado" : estado == 400 ? "excede" : "http_" + estado;
                throw new RamaException(estado, "HTTP " + estado + " en " + endpoint);
            }
            JsonNode cuerpo = json.readTree(respuesta.body());
            resultado = "ok";
            return cuerpo;
        } catch (JsonProcessingException e) {
            throw new RamaException(HttpOutcomeClassifier.SIN_RESPUESTA, "JSON ilegible en " + endpoint, e);
        } catch (IOException e) {
            throw new RamaException(HttpOutcomeClassifier.SIN_RESPUESTA, "Sin respuesta en " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RamaException(HttpOutcomeClassifier.SIN_RESPUESTA, "Interrumpido en " + endpoint, e);
        } finally {
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            Timer.builder("rama.request").tag("endpoint", endpoint).tag("resultado", resultado)
                    .register(metricas).record(Duration.ofMillis(ms));
            registro.registrar(endpoint, estado, resultado, ms);
        }
    }

    // ─── Utilidades de lectura ───────────────────────────────────────────────────

    /**
     * La API devuelve las listas anidadas como arreglo o como objeto con claves numéricas, y a veces deja elementos
     * sueltos en el primer nivel (comportamiento observado en el proyecto anterior). Se recogen todas las formas.
     */
    private static List<JsonNode> elementos(JsonNode raiz, String clave, String campoDistintivo) {
        List<JsonNode> lista = new ArrayList<>();
        JsonNode contenedor = raiz.path(clave);
        if (contenedor.isArray() || contenedor.isObject()) {
            contenedor.forEach(lista::add);
        }
        raiz.fields().forEachRemaining(campo -> {
            String nombre = campo.getKey();
            if (!nombre.equals(clave) && !nombre.equals("paginacion") && campo.getValue().isObject()
                    && campo.getValue().has(campoDistintivo)) {
                lista.add(campo.getValue());
            }
        });
        return lista;
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }
}
