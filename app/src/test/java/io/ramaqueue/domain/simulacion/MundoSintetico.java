package io.ramaqueue.domain.simulacion;

import io.ramaqueue.domain.model.ItemListado;
import io.ramaqueue.domain.model.Nivel;
import io.ramaqueue.domain.model.ProcesoEncontrado;
import io.ramaqueue.domain.model.Territorio;
import io.ramaqueue.domain.model.Territorio.Departamento;
import io.ramaqueue.domain.model.Territorio.Municipio;
import io.ramaqueue.domain.policy.RespuestaBusqueda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Un país en miniatura con un gateway falso: reproduce las reglas de la Rama Judicial (más de {@code limite}
 * resultados ⇒ «excede», 20 por página, listados jerárquicos, sujetos truncados) y sabe de antemano qué
 * debería encontrar cualquier exploración correcta.
 */
public final class MundoSintetico {

    public static final int POR_PAGINA = 20;
    public static final String NOMBRE_BUSCADO = "JUAN GARCIA";
    private static final ItemListado SELECCIONE = new ItemListado("", "Seleccione ...");

    enum TipoDespacho { PENAL, CIVIL, EJECUCION }

    enum Caso { VISIBLE, AJENO, OCULTO_CON_NOMBRE, OCULTO_SIN_NOMBRE }

    public record ResultadoPagina(RespuestaBusqueda respuesta, List<ProcesoEncontrado> procesos) {
    }

    public record Esperado(Set<Long> aceptados, int excedidos) {
    }

    private final int limite;
    private final int escala;
    private final List<Departamento> departamentos = new ArrayList<>();
    private final NavigableMap<String, List<ProcesoEncontrado>> procesosPorDespacho = new TreeMap<>();
    private final Map<String, List<ItemListado>> listados = new HashMap<>();
    private final Map<Long, ProcesoEncontrado> procesos = new HashMap<>();
    private final Map<Long, List<String>> paginasDeSujetos = new HashMap<>();
    private final Map<Long, Boolean> debeAceptarse = new HashMap<>();

    public MundoSintetico(long semilla, int limite) {
        this(semilla, limite, limite);
    }

    /**
     * @param limite a partir de cuántos resultados el gateway responde «excede»
     * @param escala tamaño típico de un despacho al generar los datos; es independiente del límite para poder
     *               probar un gateway que nunca excede (límite enorme) o que siempre excede (límite 1)
     */
    public MundoSintetico(long semilla, int limite, int escala) {
        this.limite = limite;
        this.escala = escala;
        Random r = new Random(semilla);
        long siguienteId = 1_000;

        int nDepartamentos = 2 + r.nextInt(3);
        for (int i = 0; i < nDepartamentos; i++) {
            String codDepto = "%02d".formatted(10 + i);
            List<Municipio> municipios = new ArrayList<>();
            int nMunicipios = 2 + r.nextInt(3);
            for (int j = 0; j < nMunicipios; j++) {
                String codMuni = codDepto + "%03d".formatted(j + 1);
                municipios.add(new Municipio("MUNICIPIO " + codMuni, codMuni));
                List<ItemListado> entidades = new ArrayList<>(List.of(SELECCIONE));
                int nEntidades = r.nextInt(3);
                for (int k = 0; k < nEntidades; k++) {
                    String codEnt = codMuni + "%02d".formatted(30 + k);
                    entidades.add(new ItemListado(codEnt, "ENTIDAD " + codEnt));
                    List<ItemListado> especialidades = new ArrayList<>(List.of(SELECCIONE));
                    int nEspecialidades = 1 + r.nextInt(2);
                    for (int m = 0; m < nEspecialidades; m++) {
                        String codEsp = codEnt + "%02d".formatted(3 + 2 * m);
                        especialidades.add(new ItemListado(codEsp, "ESPECIALIDAD " + codEsp));
                        List<ItemListado> despachos = new ArrayList<>(List.of(SELECCIONE));
                        int nDespachos = 1 + r.nextInt(3);
                        for (int d = 0; d < nDespachos; d++) {
                            String codDesp = codEsp + "%03d".formatted(1 + d);
                            despachos.add(new ItemListado(codDesp + "  ", "DESPACHO " + codDesp));
                            siguienteId = crearProcesos(r, codDesp, siguienteId);
                        }
                        listados.put(codEsp, despachos);
                    }
                    listados.put(codEnt, especialidades);
                }
                listados.put(codMuni, entidades);
            }
            departamentos.add(new Departamento("DEPARTAMENTO " + codDepto, codDepto, municipios));
        }
    }

    // ─── Construcción de procesos ────────────────────────────────────────────────

    private long crearProcesos(Random r, String codigoDespacho, long siguienteId) {
        int cuantos = r.nextInt(10) == 0 ? escala + 1 + r.nextInt(escala) : r.nextInt(escala / 3 + 1);
        int t = r.nextInt(100);
        TipoDespacho tipo = t < 65 ? TipoDespacho.PENAL : t < 90 ? TipoDespacho.CIVIL : TipoDespacho.EJECUCION;
        String nombreDespacho = switch (tipo) {
            case PENAL -> "JUZGADO 001 PENAL DEL CIRCUITO " + codigoDespacho;
            case CIVIL -> "JUZGADO 001 CIVIL MUNICIPAL " + codigoDespacho;
            case EJECUCION -> "JUZGADO 001 PENAL DE EJECUCION DE PENAS " + codigoDespacho;
        };
        List<ProcesoEncontrado> lista = new ArrayList<>();
        for (int i = 0; i < cuantos; i++) {
            long id = siguienteId++;
            int c = r.nextInt(100);
            Caso caso = c < 35 ? Caso.VISIBLE : c < 70 ? Caso.AJENO : c < 85 ? Caso.OCULTO_CON_NOMBRE : Caso.OCULTO_SIN_NOMBRE;
            String base = caso == Caso.VISIBLE
                    ? "Demandante: GARCIA PEREZ JUAN | Demandado: EMPRESA ANDINA "
                    : "Demandante: LOPEZ RUIZ ANA | Demandado: EMPRESA ANDINA ";
            boolean oculto = caso == Caso.OCULTO_CON_NOMBRE || caso == Caso.OCULTO_SIN_NOMBRE;
            String visible = oculto ? base.trim() + " | ..." : base;

            List<String> paginas = new ArrayList<>();
            int nPaginas = oculto ? 1 + r.nextInt(3) : 1;
            for (int p = 1; p <= nPaginas; p++) {
                String texto = p == 1 ? base.trim() + " | Tercero Interesado: PERSONA " + id : "Tercero Interesado: PERSONA " + id + "-" + p;
                if (caso == Caso.OCULTO_CON_NOMBRE && p == nPaginas) {
                    texto += " | Litisconsorte: JUAN CARLOS GARCIA RUIZ";
                }
                paginas.add(texto);
            }

            ProcesoEncontrado proceso = new ProcesoEncontrado(id, "%023d".formatted(id), nombreDespacho,
                    "DEPARTAMENTO", visible, "2020-01-01T00:00:00", "2021-01-01T00:00:00");
            lista.add(proceso);
            procesos.put(id, proceso);
            paginasDeSujetos.put(id, paginas);
            debeAceptarse.put(id, tipo == TipoDespacho.PENAL && (caso == Caso.VISIBLE || caso == Caso.OCULTO_CON_NOMBRE));
        }
        procesosPorDespacho.put(codigoDespacho, lista);
        return siguienteId;
    }

    // ─── Gateway falso ───────────────────────────────────────────────────────────

    public Territorio territorio() {
        return new Territorio(departamentos);
    }

    public ResultadoPagina buscar(String codigo, int pagina) {
        List<ProcesoEncontrado> todos = procesosBajo(codigo);
        if (todos.size() > limite) {
            return new ResultadoPagina(new RespuestaBusqueda.ExcedeMil(), List.of());
        }
        int paginas = (todos.size() + POR_PAGINA - 1) / POR_PAGINA;
        int desde = Math.min(todos.size(), (pagina - 1) * POR_PAGINA);
        int hasta = Math.min(todos.size(), desde + POR_PAGINA);
        return new ResultadoPagina(new RespuestaBusqueda.Exitosa(todos.size(), paginas), todos.subList(desde, hasta));
    }

    /** Listado crudo, con el «Seleccione ...» y los espacios finales de los despachos, como la API real. */
    public List<ItemListado> listar(String codigoPadre) {
        return listados.getOrDefault(codigoPadre, List.of(SELECCIONE));
    }

    public ProcesoEncontrado proceso(long id) {
        return procesos.get(id);
    }

    public int paginasSujetos(long id) {
        return paginasDeSujetos.get(id).size();
    }

    public String sujetosPagina(long id, int pagina) {
        return paginasDeSujetos.get(id).get(pagina - 1);
    }

    // ─── Verdad de referencia ────────────────────────────────────────────────────

    /** Qué debe encontrar una exploración correcta y cuántas ramas deben quedar sin respuesta. */
    public Esperado esperado() {
        Set<Long> aceptados = new TreeSet<>();
        int[] excedidos = {0};
        explorar(Nivel.RAIZ, "", aceptados, excedidos);
        return new Esperado(aceptados, excedidos[0]);
    }

    private void explorar(Nivel nivel, String codigo, Set<Long> aceptados, int[] excedidos) {
        List<ProcesoEncontrado> bajo = procesosBajo(codigo);
        if (bajo.size() <= limite) {
            for (ProcesoEncontrado p : bajo) {
                if (debeAceptarse.get(p.idProceso())) {
                    aceptados.add(p.idProceso());
                }
            }
            return;
        }
        if (nivel.esUltimo()) {
            excedidos[0]++;
            return;
        }
        List<String> hijos;
        if (nivel == Nivel.RAIZ) {
            hijos = departamentos.stream().map(Departamento::codigo).toList();
        } else if (nivel == Nivel.DEPARTAMENTO) {
            hijos = territorio().municipiosDe(codigo).stream().map(Municipio::codigo).toList();
        } else {
            hijos = ItemListado.limpiar(listar(codigo)).stream().map(ItemListado::codigo).toList();
            if (hijos.isEmpty()) {
                excedidos[0]++;
                return;
            }
        }
        for (String hijo : hijos) {
            explorar(nivel.siguiente().orElseThrow(), hijo, aceptados, excedidos);
        }
    }

    private List<ProcesoEncontrado> procesosBajo(String prefijo) {
        List<ProcesoEncontrado> todos = new ArrayList<>();
        procesosPorDespacho.subMap(prefijo, true, prefijo + '￿', true).values().forEach(todos::addAll);
        return todos;
    }
}
