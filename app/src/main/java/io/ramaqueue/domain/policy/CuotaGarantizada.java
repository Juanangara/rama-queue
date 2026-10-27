package io.ramaqueue.domain.policy;

/**
 * Convierte una fracción (p. ej. 25 %) en una cantidad entera de ítems por ciclo <b>sin sesgo de redondeo</b>. Los ciclos del relay
 * suelen ser de uno o dos mensajes (se libera un hueco en el broker cada vez que un consumidor toma uno): con {@code ceil(cupo * fracción)}
 * una fracción del 25 % se convertiría en el 100 % de cada ciclo de un mensaje. Aquí el sobrante se acumula como crédito y se gasta
 * cuando completa un ítem, de modo que a largo plazo la cuota es exactamente la fracción pedida, sea cual sea el tamaño de los ciclos.
 *
 * <p>Con estado (el crédito) y sin hilos propios: se usa desde el relay, que corre en un solo hilo; aun así es seguro compartirlo.
 */
public final class CuotaGarantizada {

    private final double fraccion;
    private final double creditoMaximo;
    private double credito;

    /** @param creditoMaximo tope del crédito acumulado: evita una ráfaga enorme después de un tiempo sin actividad */
    public CuotaGarantizada(double fraccion, double creditoMaximo) {
        this.fraccion = Math.min(1.0, Math.max(0.0, fraccion));
        this.creditoMaximo = Math.max(1.0, creditoMaximo);
    }

    /** Cuántos de los {@code cupo} ítems de este ciclo corresponden a la cuota garantizada. */
    public synchronized int asignar(int cupo) {
        if (cupo <= 0 || fraccion == 0) {
            return 0;
        }
        credito = Math.min(creditoMaximo, credito + cupo * fraccion);
        int enteros = Math.min(cupo, (int) Math.floor(credito));
        credito -= enteros;
        return enteros;
    }
}
