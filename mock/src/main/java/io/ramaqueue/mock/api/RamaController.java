package io.ramaqueue.mock.api;

import io.ramaqueue.mock.dataset.Dataset;
import io.ramaqueue.mock.dataset.Dataset.Despacho;
import io.ramaqueue.mock.dataset.Dataset.Proceso;
import io.ramaqueue.mock.dataset.Dataset.Sujeto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;

/** Reproduce el contrato observado de consultaprocesos.ramajudicial.gov.co:448/api/v2. */
@RestController
@RequestMapping("/api/v2")
class RamaController {

    private static final int REGISTROS_POR_PAGINA = 20;
    private static final int REGISTROS_POR_PAGINA_DETALLE = 40;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String[] ACTUACIONES = {
            "Radicación de Proceso", "Al Despacho", "Auto admite demanda", "Fijación estado",
            "Notificación personal", "Auto fija fecha audiencia", "Audiencia", "Auto resuelve solicitud"};

    private final Dataset dataset;

    RamaController(Dataset dataset) {
        this.dataset = dataset;
    }

    // ─── Búsqueda ────────────────────────────────────────────────────────────────

    @GetMapping("/Procesos/Consulta/NombreRazonSocial")
    ResponseEntity<Object> buscar(
            @RequestParam(defaultValue = "") String nombre,
            @RequestParam(defaultValue = "nat") String tipoPersona,
            @RequestParam(name = "SoloActivos", defaultValue = "false") boolean soloActivos,
            @RequestParam(defaultValue = "") String codificacionDespacho,
            @RequestParam(defaultValue = "1") int pagina) {

        if (nombre.isBlank()) {
            return error(HttpStatus.NOT_FOUND, "Se ha de especificar el parametro \"NombreRazonSocial\".");
        }
        List<String> tokens = Dataset.tokenizar(nombre);
        if (tokens.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "Null or empty full-text predicate.");
        }
        if (!tipoPersona.equals("nat") && !tipoPersona.equals("jur")) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "tipoPersona debe ser 'nat' o 'jur'.");
        }

        String prefijo = codificacionDespacho.trim();
        int[] ids = dataset.buscar(tokens, tipoPersona.equals("nat"), prefijo);
        if (soloActivos) {
            LocalDate limite = Dataset.HOY.minusDays(30);
            ids = Arrays.stream(ids).filter(i -> !dataset.proceso(i).fechaUltima().isBefore(limite)).toArray();
        }
        if (ids.length > Dataset.MAXIMO_RESULTADOS) {
            return error(HttpStatus.BAD_REQUEST,
                    "Hay más de mil registros con los criterios especificados, favor intentarlo de nuevo con criterios más específicos.");
        }

        int paginas = (ids.length + REGISTROS_POR_PAGINA - 1) / REGISTROS_POR_PAGINA;
        int desde = Math.min(ids.length, Math.max(0, (pagina - 1)) * REGISTROS_POR_PAGINA);
        int hasta = Math.min(ids.length, desde + REGISTROS_POR_PAGINA);
        List<Object> procesos = new ArrayList<>();
        for (int i = desde; i < hasta; i++) {
            procesos.add(resumenProceso(dataset.proceso(ids[i])));
        }

        Map<String, Object> parametros = mapa(
                "numero", null, "nombre", nombre, "tipoPersona", tipoPersona, "idSujeto", null, "ponente", null,
                "claseProceso", null, "codificacionDespacho", prefijo, "soloActivos", soloActivos);
        Map<String, Object> respuesta = mapa(
                "tipoConsulta", "NombreRazonSocial",
                "procesos", procesos,
                "parametros", parametros,
                "paginacion", paginacion(ids.length, REGISTROS_POR_PAGINA, paginas, pagina));
        return ResponseEntity.ok(respuesta);
    }

    private Map<String, Object> resumenProceso(Proceso p) {
        Despacho d = dataset.despacho(p.despachoIdx());
        return mapa(
                "idProceso", p.id(),
                "idConexion", 100 + d.idx() % 900,
                "llaveProceso", p.llave(),
                "fechaProceso", fecha(p.fechaProceso()),
                "fechaUltimaActuacion", fecha(p.fechaUltima()),
                "despacho", d.nombre(),
                "departamento", d.departamento(),
                "sujetosProcesales", p.sujetosProcesales(),
                "esPrivado", false,
                "cantFilas", -1);
    }

    // ─── Listados ────────────────────────────────────────────────────────────────

    @GetMapping("/Lista/Entidades/{municipio}")
    List<Map<String, Object>> entidades(@PathVariable String municipio) {
        String codigo = municipio.trim();
        Map<String, String> encontradas = new TreeMap<>();
        if (codigo.length() == 5) {
            for (Despacho d : dataset.despachosConPrefijo(codigo)) {
                encontradas.put(d.entidadCodigo(), dataset.nombreEntidad(d.entidadCodigo()));
            }
        }
        return listado(encontradas);
    }

    @GetMapping("/Lista/Especialidades/{entidad}")
    List<Map<String, Object>> especialidades(@PathVariable String entidad) {
        String codigo = entidad.trim();
        Map<String, String> encontradas = new TreeMap<>();
        if (codigo.length() == 7) {
            for (Despacho d : dataset.despachosConPrefijo(codigo)) {
                encontradas.put(d.especialidadCodigo(), dataset.nombreEspecialidad(d.especialidadCodigo()));
            }
        }
        return listado(encontradas);
    }

    @GetMapping("/Lista/Despachos/{especialidad}")
    List<Map<String, Object>> despachos(@PathVariable String especialidad) {
        String codigo = especialidad.trim();
        Map<String, String> encontrados = new TreeMap<>();
        if (codigo.length() == 9) {
            for (Despacho d : dataset.despachosConPrefijo(codigo)) {
                // La API real devuelve los códigos de despacho con espacios al final.
                encontrados.put(d.codigo() + "  ", d.nombre());
            }
        }
        return listado(encontrados);
    }

    /** Ordenado por nombre, con el elemento «Seleccione ...» de código vacío al inicio. */
    private static List<Map<String, Object>> listado(Map<String, String> codigoANombre) {
        List<Map<String, Object>> lista = new ArrayList<>();
        lista.add(mapa("codigo", "", "nombre", "Seleccione ..."));
        codigoANombre.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> lista.add(mapa("codigo", e.getKey(), "nombre", e.getValue())));
        return lista;
    }

    // ─── Detalle de un proceso ───────────────────────────────────────────────────

    @GetMapping("/Proceso/Detalle/{id}")
    ResponseEntity<Object> detalle(@PathVariable long id) {
        Optional<Proceso> encontrado = dataset.procesoPorId(id);
        if (encontrado.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "No se encontró el proceso.");
        }
        Proceso p = encontrado.get();
        Despacho d = dataset.despacho(p.despachoIdx());
        String[] clase = claseDe(d.codigo().substring(7, 9));
        SplittableRandom r = new SplittableRandom(dataset.seed() ^ id);
        return ResponseEntity.ok(mapa(
                "idRegProceso", id,
                "llaveProceso", p.llave(),
                "idConexion", 100 + d.idx() % 900,
                "esPrivado", false,
                "fechaProceso", fecha(p.fechaProceso()),
                "codDespachoCompleto", d.codigo(),
                "despacho", d.nombre(),
                "ponente", Juez.nombre(r),
                "tipoProceso", clase[0],
                "claseProceso", clase[1],
                "subclaseProceso", "Sin Subclase de Proceso",
                "recurso", "Sin Tipo de Recurso",
                "ubicacion", "Despacho",
                "contenidoRadicacion", "SIN CONTENIDO DE RADICACIÓN",
                "fechaConsulta", fecha(Dataset.HOY),
                "ultimaActualizacion", fecha(p.fechaUltima())));
    }

    @GetMapping("/Proceso/Actuaciones/{id}")
    ResponseEntity<Object> actuaciones(@PathVariable long id, @RequestParam(defaultValue = "1") int pagina) {
        Optional<Proceso> encontrado = dataset.procesoPorId(id);
        if (encontrado.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "No se encontró el proceso.");
        }
        Proceso p = encontrado.get();
        int cantidad = 1 + (int) Math.floorMod(id * 31 + dataset.seed(), 8L);
        long dias = java.time.temporal.ChronoUnit.DAYS.between(p.fechaProceso(), p.fechaUltima());
        List<Object> lista = new ArrayList<>();
        for (int cons = cantidad; cons >= 1; cons--) {
            LocalDate fechaActuacion = cons == cantidad
                    ? p.fechaUltima()
                    : p.fechaProceso().plusDays(dias * cons / cantidad);
            lista.add(mapa(
                    "idRegActuacion", id * 100 + cons,
                    "llaveProceso", p.llave(),
                    "consActuacion", cons,
                    "fechaActuacion", fecha(fechaActuacion),
                    "actuacion", ACTUACIONES[(cons - 1) % ACTUACIONES.length],
                    "anotacion", "Actuación " + cons + " del proceso",
                    "fechaInicial", null,
                    "fechaFinal", null,
                    "fechaRegistro", fecha(fechaActuacion.plusDays(1)),
                    "codRegla", "00",
                    "conDocumentos", false,
                    "cant", cantidad));
        }
        List<Object> pagina1 = pagina == 1 ? lista : List.of();
        return ResponseEntity.ok(mapa(
                "actuaciones", pagina1,
                "paginacion", paginacion(cantidad, REGISTROS_POR_PAGINA_DETALLE, 1, pagina)));
    }

    @GetMapping("/Proceso/Sujetos/{id}")
    ResponseEntity<Object> sujetos(@PathVariable long id, @RequestParam(defaultValue = "1") int pagina) {
        Optional<Proceso> encontrado = dataset.procesoPorId(id);
        if (encontrado.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "No se encontró el proceso.");
        }
        Proceso p = encontrado.get();
        List<Object> lista = new ArrayList<>();
        int i = 0;
        for (Sujeto s : p.sujetos()) {
            i++;
            lista.add(mapa(
                    "idRegSujeto", id * 10 + i,
                    "tipoSujeto", s.rol() + (i % 2 == 0 ? " " : ""),
                    "esEmplazado", false,
                    "identificacion", null,
                    "nombreRazonSocial", conRuidoDeEspacios(s.nombre(), id + i),
                    "cant", p.sujetos().size()));
        }
        List<Object> paginaActual = pagina == 1 ? lista : List.of();
        return ResponseEntity.ok(mapa(
                "sujetos", paginaActual,
                "paginacion", paginacion(p.sujetos().size(), REGISTROS_POR_PAGINA_DETALLE, 1, pagina)));
    }

    /** La API real incrusta saltos de línea y tabs en medio de algunos nombres. */
    private static String conRuidoDeEspacios(String nombre, long semilla) {
        int espacio = nombre.lastIndexOf(' ');
        if (espacio > 0 && Math.floorMod(semilla, 7L) == 0) {
            return nombre.substring(0, espacio) + "\r\n\t\t\t\t\t" + nombre.substring(espacio + 1);
        }
        return nombre;
    }

    private static String[] claseDe(String especialidad) {
        return switch (especialidad) {
            case "03" -> new String[]{"Declarativo", "Verbal Sumario"};
            case "05" -> new String[]{"Ordinario", "Ordinario Laboral de Única Instancia"};
            case "07" -> new String[]{"Declarativo", "Familia - Verbal"};
            case "09" -> new String[]{"Penal", "Ley 906 de 2004"};
            case "10" -> new String[]{"Penal", "Control de Garantías"};
            case "12" -> new String[]{"Penal", "Ejecución de Penas"};
            case "16" -> new String[]{"Extinción de Dominio", "Ley 1708 de 2014"};
            default -> new String[]{"Otros", "Otros"};
        };
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────────

    private static Map<String, Object> paginacion(int registros, int porPagina, int paginas, int pagina) {
        return mapa("cantidadRegistros", registros, "registrosPagina", porPagina,
                "cantidadPaginas", paginas, "pagina", pagina, "paginas", null);
    }

    private static ResponseEntity<Object> error(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).body(mapa("StatusCode", estado.value(), "Message", mensaje));
    }

    private static String fecha(LocalDate fecha) {
        return fecha.atStartOfDay().format(FECHA);
    }

    /** Mapa que conserva el orden de inserción y admite valores nulos. */
    private static Map<String, Object> mapa(Object... paresClaveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < paresClaveValor.length; i += 2) {
            m.put((String) paresClaveValor[i], paresClaveValor[i + 1]);
        }
        return m;
    }

    /** Nombres ficticios de jueces (solo para el campo ponente). */
    private static final class Juez {
        private static final String[] NOMBRES = {"JUAN", "MARIA", "CARLOS", "ANA", "LUIS", "CLAUDIA", "JORGE", "PATRICIA"};
        private static final String[] APELLIDOS = {"GARCIA", "RODRIGUEZ", "MARTINEZ", "LOPEZ", "GONZALEZ", "PEREZ", "RAMIREZ", "TORRES"};

        static String nombre(SplittableRandom r) {
            return NOMBRES[r.nextInt(NOMBRES.length)] + " " + APELLIDOS[r.nextInt(APELLIDOS.length)]
                    + " " + APELLIDOS[r.nextInt(APELLIDOS.length)];
        }
    }
}
