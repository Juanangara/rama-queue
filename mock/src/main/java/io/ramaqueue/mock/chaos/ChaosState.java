package io.ramaqueue.mock.chaos;

import io.ramaqueue.mock.MockProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado de caos y de estadísticas del mock: límite por minuto con bloqueo temporal, bloqueo manual,
 * latencia, y las métricas que demuestran qué tan amable fue el cliente (pico de concurrencia y
 * separación mínima entre peticiones).
 */
@Component
public class ChaosState {

    private static final long VENTANA_MS = 60_000;

    private final AtomicInteger enVuelo = new AtomicInteger();
    private final Deque<Long> ventana = new ArrayDeque<>();
    private final Map<Integer, Long> porEstado = new TreeMap<>();

    private final int latenciaInicial;
    private final int jitterInicial;
    private final int limiteInicial;
    private final int bloqueoAutomaticoInicial;

    private volatile int latenciaMs;
    private volatile int jitterMs;
    private volatile int limitePorMinuto;
    private volatile int bloqueoAutomaticoSegundos;

    private long bloqueadoHasta;
    private long total;
    private int picoEnVuelo;
    private long ultimaAdmitida = -1;
    private long separacionMinimaMs = Long.MAX_VALUE;
    private int picoPorMinuto;

    public ChaosState(MockProperties propiedades) {
        this.latenciaInicial = propiedades.latencyMs();
        this.jitterInicial = propiedades.jitterMs();
        this.limiteInicial = propiedades.limitPerMinute();
        this.bloqueoAutomaticoInicial = propiedades.autoBlockSeconds();
        restablecerConfiguracion();
    }

    public enum Decision { PERMITIR, DENEGAR }

    /** Decide si una petición entra. Las denegadas por bloqueo no extienden el bloqueo. */
    public synchronized Decision admitir(long ahoraMs) {
        total++;
        if (ahoraMs < bloqueadoHasta) {
            return Decision.DENEGAR;
        }
        while (!ventana.isEmpty() && ventana.peekFirst() <= ahoraMs - VENTANA_MS) {
            ventana.pollFirst();
        }
        if (ventana.size() >= limitePorMinuto) {
            bloqueadoHasta = ahoraMs + bloqueoAutomaticoSegundos * 1000L;
            return Decision.DENEGAR;
        }
        ventana.addLast(ahoraMs);
        picoPorMinuto = Math.max(picoPorMinuto, ventana.size());
        if (ultimaAdmitida >= 0) {
            separacionMinimaMs = Math.min(separacionMinimaMs, ahoraMs - ultimaAdmitida);
        }
        ultimaAdmitida = ahoraMs;
        return Decision.PERMITIR;
    }

    public int entrar() {
        int actual = enVuelo.incrementAndGet();
        synchronized (this) {
            picoEnVuelo = Math.max(picoEnVuelo, actual);
        }
        return actual;
    }

    public void salir() {
        enVuelo.decrementAndGet();
    }

    public synchronized void registrarEstado(int estado) {
        porEstado.merge(estado, 1L, Long::sum);
    }

    public synchronized void bloquear(int segundos, long ahoraMs) {
        bloqueadoHasta = ahoraMs + segundos * 1000L;
    }

    public synchronized void restablecerEstadisticas() {
        ventana.clear();
        porEstado.clear();
        bloqueadoHasta = 0;
        total = 0;
        picoEnVuelo = 0;
        picoPorMinuto = 0;
        ultimaAdmitida = -1;
        separacionMinimaMs = Long.MAX_VALUE;
    }

    public void restablecerConfiguracion() {
        latenciaMs = latenciaInicial;
        jitterMs = jitterInicial;
        limitePorMinuto = limiteInicial;
        bloqueoAutomaticoSegundos = bloqueoAutomaticoInicial;
    }

    public void configurarLatencia(int base, int jitter) {
        latenciaMs = Math.max(0, base);
        jitterMs = Math.max(0, jitter);
    }

    public void configurarLimite(int porMinuto) {
        limitePorMinuto = Math.max(1, porMinuto);
    }

    public int latenciaMs() {
        return latenciaMs;
    }

    public int jitterMs() {
        return jitterMs;
    }

    public synchronized Map<String, Object> estadisticas(long ahoraMs) {
        while (!ventana.isEmpty() && ventana.peekFirst() <= ahoraMs - VENTANA_MS) {
            ventana.pollFirst();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalPeticiones", total);
        m.put("porEstadoHttp", new TreeMap<>(porEstado));
        m.put("enVueloAhora", enVuelo.get());
        m.put("picoEnVuelo", picoEnVuelo);
        m.put("peticionesUltimoMinuto", ventana.size());
        m.put("picoPeticionesPorMinuto", picoPorMinuto);
        m.put("separacionMinimaEntrePeticionesMs", separacionMinimaMs == Long.MAX_VALUE ? null : separacionMinimaMs);
        m.put("bloqueadoSegundosRestantes", Math.max(0, (bloqueadoHasta - ahoraMs + 999) / 1000));
        m.put("latenciaMs", latenciaMs);
        m.put("jitterMs", jitterMs);
        m.put("limitePorMinuto", limitePorMinuto);
        m.put("bloqueoAutomaticoSegundos", bloqueoAutomaticoSegundos);
        return m;
    }
}
