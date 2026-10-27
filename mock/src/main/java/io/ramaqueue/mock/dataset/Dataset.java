package io.ramaqueue.mock.dataset;

import io.ramaqueue.mock.dataset.Catalogo.Tier;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;

/**
 * Universo sintético y determinista de despachos y procesos, con un índice invertido por token.
 * La búsqueda es full-text sobre los nombres de los sujetos, así que "más de mil resultados" surge
 * de forma natural: un apellido muy común coincide con muchos procesos y uno raro con pocos.
 */
public final class Dataset {

    public static final LocalDate HOY = LocalDate.of(2026, 10, 26);
    public static final long ID_BASE = 10_000_000L;
    public static final int MAXIMO_RESULTADOS = 1000;

    private static final Set<String> CIUDADES_GRANDES =
            Set.of("11001", "05001", "76001", "08001", "13001", "68001", "54001", "23001");

    /** Despachos penales de las tres ciudades principales: concentran mucho más trabajo que el resto. */
    private static final Set<String> CONGESTIONADOS =
            Set.of("110013109001", "050013109001", "760013109001");
    private static final int PROCESOS_CONGESTIONADOS = 4000;

    private static final String[] ROLES_ADICIONALES = {"Tercero Interesado", "Litisconsorte", "Coadyuvante"};

    public record Despacho(int idx, String codigo, String nombre, String municipioCodigo,
                           String municipioNombre, String departamento) {
        public String entidadCodigo() {
            return codigo.substring(0, 7);
        }

        public String especialidadCodigo() {
            return codigo.substring(0, 9);
        }
    }

    public record Sujeto(String rol, String nombre, boolean natural) {
    }

    public record Proceso(int idx, int despachoIdx, String llave, LocalDate fechaProceso,
                          LocalDate fechaUltima, List<Sujeto> sujetos) {
        public long id() {
            return ID_BASE + idx;
        }

        /** Con más de dos sujetos la búsqueda devuelve la lista cortada con "...". */
        public boolean truncado() {
            return sujetos.size() > 2;
        }

        public String sujetosProcesales() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(2, sujetos.size()); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(sujetos.get(i).rol()).append(": ").append(sujetos.get(i).nombre());
            }
            return truncado() ? sb.append(" | ...").toString() : sb.append(' ').toString();
        }
    }

    private final long seed;
    private final Despacho[] despachos;
    private final List<Despacho> listaDespachos;
    private final Proceso[] procesos;
    private final int[] procInicio;
    private final Map<String, int[]> indiceNaturales;
    private final Map<String, int[]> indiceJuridicas;

    public Dataset(Territorio territorio, long seed, boolean congestionado) {
        this.seed = seed;
        this.despachos = generarDespachos(territorio, seed);
        this.listaDespachos = List.of(despachos);

        List<Proceso> lista = new ArrayList<>();
        procInicio = new int[despachos.length + 1];
        for (Despacho d : despachos) {
            procInicio[d.idx()] = lista.size();
            SplittableRandom r = rng(seed ^ 0x5EEDL, d.codigo());
            int n = congestionado && CONGESTIONADOS.contains(d.codigo()) ? PROCESOS_CONGESTIONADOS : 15 + r.nextInt(31);
            for (int j = 0; j < n; j++) {
                lista.add(nuevoProceso(lista.size(), d, r));
            }
        }
        procInicio[despachos.length] = lista.size();
        procesos = lista.toArray(new Proceso[0]);

        Map<String, IntList> naturales = new HashMap<>();
        Map<String, IntList> juridicas = new HashMap<>();
        for (Proceso p : procesos) {
            for (Sujeto s : p.sujetos()) {
                Map<String, IntList> destino = s.natural() ? naturales : juridicas;
                for (String token : tokenizar(s.nombre())) {
                    destino.computeIfAbsent(token, k -> new IntList()).agregarSiNuevo(p.idx());
                }
            }
        }
        indiceNaturales = compactar(naturales);
        indiceJuridicas = compactar(juridicas);
    }

    // ─── Generación ──────────────────────────────────────────────────────────────

    private static Despacho[] generarDespachos(Territorio territorio, long seed) {
        List<Despacho> lista = new ArrayList<>();
        for (Territorio.Departamento dep : territorio.departamentos()) {
            for (Territorio.Municipio mun : dep.municipios()) {
                Tier tier = tierDe(mun.codigo(), seed);
                if (tier == null) {
                    continue;
                }
                for (Catalogo.Entidad ent : Catalogo.entidadesPara(tier)) {
                    for (String esp : Catalogo.especialidadesPara(ent.codigo(), tier)) {
                        String base = mun.codigo() + ent.codigo() + esp;
                        int n = cantidadDespachos(tier, rng(seed, base));
                        for (int k = 1; k <= n; k++) {
                            String codigo = base + "%03d".formatted(k);
                            boolean formatoB = Math.floorMod(mezclar(seed ^ codigo.hashCode()), 5) == 0;
                            String nombre = Catalogo.nombreDespacho(
                                    ent, Catalogo.ESPECIALIDADES.get(esp), k, mun.nombre(), formatoB);
                            lista.add(new Despacho(0, codigo, nombre, mun.codigo(), mun.nombre(), dep.nombre()));
                        }
                    }
                }
            }
        }
        lista.sort(Comparator.comparing(Despacho::codigo));
        Despacho[] resultado = new Despacho[lista.size()];
        for (int i = 0; i < resultado.length; i++) {
            Despacho d = lista.get(i);
            resultado[i] = new Despacho(i, d.codigo(), d.nombre(), d.municipioCodigo(), d.municipioNombre(), d.departamento());
        }
        return resultado;
    }

    private static Tier tierDe(String codigoMunicipio, long seed) {
        if (CIUDADES_GRANDES.contains(codigoMunicipio)) {
            return Tier.BIG;
        }
        if (codigoMunicipio.endsWith("001")) {
            return Tier.CAPITAL;
        }
        return Math.floorMod(mezclar(seed ^ codigoMunicipio.hashCode()), 5) == 0 ? Tier.SMALL : null;
    }

    private static int cantidadDespachos(Tier tier, SplittableRandom r) {
        return switch (tier) {
            case BIG -> 2 + r.nextInt(5);
            case CAPITAL -> 1 + r.nextInt(3);
            case SMALL -> 1 + r.nextInt(2);
        };
    }

    private static Proceso nuevoProceso(int idx, Despacho d, SplittableRandom r) {
        int anio = 2015 + r.nextInt(11);
        int consecutivo = 1 + r.nextInt(99_998);
        String llave = d.codigo() + anio + "%05d".formatted(consecutivo) + "00";
        LocalDate fechaProceso = LocalDate.of(anio, 1, 1).plusDays(r.nextInt(365));
        LocalDate fechaUltima;
        if (r.nextInt(100) < 8) {
            fechaUltima = HOY.minusDays(r.nextInt(30));
        } else {
            long margen = ChronoUnit.DAYS.between(fechaProceso, HOY.minusDays(31));
            fechaUltima = fechaProceso.plusDays(r.nextInt(1 + (int) Math.max(0, margen)));
        }
        List<Sujeto> sujetos = new ArrayList<>();
        sujetos.add(nuevoSujeto("Demandante", r));
        sujetos.add(nuevoSujeto("Demandado", r));
        if (r.nextInt(100) < 8) {
            int extra = 3 + r.nextInt(3);
            for (int i = 0; i < extra; i++) {
                sujetos.add(nuevoSujeto(ROLES_ADICIONALES[r.nextInt(ROLES_ADICIONALES.length)], r));
            }
        }
        return new Proceso(idx, d.idx(), llave, fechaProceso, fechaUltima, List.copyOf(sujetos));
    }

    private static Sujeto nuevoSujeto(String rol, SplittableRandom r) {
        boolean natural = r.nextInt(100) < 75;
        return new Sujeto(rol, natural ? Nombres.persona(r) : Nombres.empresa(r), natural);
    }

    static long mezclar(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static SplittableRandom rng(long seed, String clave) {
        return new SplittableRandom(mezclar(seed ^ clave.hashCode()));
    }

    // ─── Búsqueda ────────────────────────────────────────────────────────────────

    /** Mayúsculas, sin tildes, solo letras, y descartando tokens de menos de 3 caracteres. */
    public static List<String> tokenizar(String texto) {
        String limpio = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String t : limpio.split("[^A-Z]+")) {
            if (t.length() >= 3 && !tokens.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** Índices (ascendentes) de los procesos que contienen todos los tokens, dentro del prefijo de despacho dado. */
    public int[] buscar(List<String> tokens, boolean natural, String prefijo) {
        if (tokens.isEmpty()) {
            return new int[0];
        }
        int[] rango = rangoDespachos(prefijo);
        if (rango[0] >= rango[1]) {
            return new int[0];
        }
        int desdeProceso = procInicio[rango[0]];
        int hastaProceso = procInicio[rango[1]];
        Map<String, int[]> indice = natural ? indiceNaturales : indiceJuridicas;

        int n = tokens.size();
        int[][] listas = new int[n][];
        int[] desde = new int[n];
        int[] hasta = new int[n];
        int menor = 0;
        for (int i = 0; i < n; i++) {
            int[] lista = indice.get(tokens.get(i));
            if (lista == null) {
                return new int[0];
            }
            listas[i] = lista;
            desde[i] = limiteInferior(lista, 0, lista.length, desdeProceso);
            hasta[i] = limiteInferior(lista, desde[i], lista.length, hastaProceso);
            if (hasta[i] == desde[i]) {
                return new int[0];
            }
            if (hasta[i] - desde[i] < hasta[menor] - desde[menor]) {
                menor = i;
            }
        }
        int[] salida = new int[hasta[menor] - desde[menor]];
        int cuenta = 0;
        candidato:
        for (int j = desde[menor]; j < hasta[menor]; j++) {
            int valor = listas[menor][j];
            for (int i = 0; i < n; i++) {
                if (i != menor && Arrays.binarySearch(listas[i], desde[i], hasta[i], valor) < 0) {
                    continue candidato;
                }
            }
            salida[cuenta++] = valor;
        }
        return Arrays.copyOf(salida, cuenta);
    }

    /** Cuántos nodos de cada nivel del árbol exceden mil y qué despachos nunca podrán responder. */
    public Map<String, Object> resumen(List<String> tokens, boolean natural) {
        int[] ids = buscar(tokens, natural, "");
        int[] porDespacho = new int[despachos.length];
        for (int id : ids) {
            porDespacho[procesos[id].despachoIdx()]++;
        }
        int[] longitudes = {2, 5, 7, 9, 12};
        String[] niveles = {"departamento", "municipio", "entidad", "especialidad", "despacho"};
        Map<String, Object> nodosQueExceden = new LinkedHashMap<>();
        List<String> sinRespuesta = new ArrayList<>();
        for (int n = 0; n < longitudes.length; n++) {
            Map<String, Integer> acumulado = new HashMap<>();
            for (Despacho d : despachos) {
                if (porDespacho[d.idx()] > 0) {
                    acumulado.merge(d.codigo().substring(0, longitudes[n]), porDespacho[d.idx()], Integer::sum);
                }
            }
            nodosQueExceden.put(niveles[n], acumulado.values().stream().filter(v -> v > MAXIMO_RESULTADOS).count());
            if (n == longitudes.length - 1) {
                acumulado.forEach((codigo, total) -> {
                    if (total > MAXIMO_RESULTADOS) {
                        sinRespuesta.add(codigo);
                    }
                });
            }
        }
        sinRespuesta.sort(null);
        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("total", ids.length);
        resumen.put("excedeMil", ids.length > MAXIMO_RESULTADOS);
        resumen.put("nodosQueExceden", nodosQueExceden);
        resumen.put("despachosSinRespuesta", sinRespuesta);
        return resumen;
    }

    // ─── Acceso ──────────────────────────────────────────────────────────────────

    public long seed() {
        return seed;
    }

    public List<Despacho> despachos() {
        return listaDespachos;
    }

    public int totalProcesos() {
        return procesos.length;
    }

    public Proceso proceso(int idx) {
        return procesos[idx];
    }

    public Despacho despacho(int idx) {
        return despachos[idx];
    }

    public Optional<Proceso> procesoPorId(long id) {
        long idx = id - ID_BASE;
        return idx >= 0 && idx < procesos.length ? Optional.of(procesos[(int) idx]) : Optional.empty();
    }

    public List<Despacho> despachosConPrefijo(String prefijo) {
        int[] rango = rangoDespachos(prefijo);
        return listaDespachos.subList(rango[0], Math.max(rango[0], rango[1]));
    }

    public String nombreEntidad(String codigoEntidad) {
        Catalogo.Entidad e = Catalogo.ENTIDADES.get(codigoEntidad.substring(5, 7));
        return e == null ? codigoEntidad : e.nombre();
    }

    public String nombreEspecialidad(String codigoEspecialidad) {
        Catalogo.Especialidad e = Catalogo.ESPECIALIDADES.get(codigoEspecialidad.substring(7, 9));
        return e == null ? codigoEspecialidad : e.nombre();
    }

    // ─── Utilidades internas ─────────────────────────────────────────────────────

    private int[] rangoDespachos(String prefijo) {
        int lo = primerIndice(despachos.length, i -> despachos[i].codigo().compareTo(prefijo) >= 0);
        int hi = primerIndice(despachos.length, i -> despachos[i].codigo().compareTo(prefijo + '￿') > 0);
        return new int[]{lo, hi};
    }

    private static int primerIndice(int n, IntPredicate cumple) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumple.test(mid)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private static int limiteInferior(int[] a, int desde, int hasta, int clave) {
        int lo = desde;
        int hi = hasta;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < clave) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static Map<String, int[]> compactar(Map<String, IntList> origen) {
        Map<String, int[]> destino = new HashMap<>(origen.size() * 2);
        origen.forEach((token, lista) -> destino.put(token, lista.aArreglo()));
        return destino;
    }

    private static final class IntList {
        private int[] datos = new int[4];
        private int tamano;

        void agregarSiNuevo(int valor) {
            if (tamano > 0 && datos[tamano - 1] == valor) {
                return;
            }
            if (tamano == datos.length) {
                datos = Arrays.copyOf(datos, tamano * 2);
            }
            datos[tamano++] = valor;
        }

        int[] aArreglo() {
            return Arrays.copyOf(datos, tamano);
        }
    }
}
