package io.ramaqueue.infrastructure.out.messaging;

import io.ramaqueue.domain.model.WorkItem;

import java.time.Duration;

/** Nombres de la topología de RabbitMQ (guía §7). */
public final class Colas {

    public static final String EXCHANGE_TRABAJO = "rama.work";
    public static final String EXCHANGE_ESPERA = "rama.retry";
    public static final String EXCHANGE_DLX = "rama.dlx";
    public static final String EXCHANGE_EVENTOS = "rama.events";

    /** Grupos de trabajo; también son las claves de enrutamiento de {@code rama.work} y {@code rama.dlx}. */
    public static final String BUSQUEDA = "search";
    public static final String DETALLE = "detail";

    public static final String TRABAJO_BUSQUEDA = "search.fetch";
    public static final String TRABAJO_DETALLE = "detail.fetch";
    public static final String DLQ_BUSQUEDA = "search.dlq";
    public static final String DLQ_DETALLE = "detail.dlq";

    /** Prioridad máxima de la cola de búsqueda: la profundidad del árbol (0..5) cabe con holgura. */
    public static final int PRIORIDAD_MAXIMA = 10;

    private Colas() {
    }

    public static String grupoDe(WorkItem item) {
        return item.tipo().esDeBusqueda() ? BUSQUEDA : DETALLE;
    }

    public static String trabajo(String grupo) {
        return grupo + ".fetch";
    }

    public static String dlq(String grupo) {
        return grupo + ".dlq";
    }

    /** Cola de espera con TTL: {@code search.retry.30s}, {@code detail.retry.2m}… */
    public static String espera(String grupo, Duration duracion) {
        return grupo + ".retry." + etiqueta(duracion);
    }

    static String etiqueta(Duration d) {
        long segundos = d.toSeconds();
        if (segundos % 3600 == 0) {
            return (segundos / 3600) + "h";
        }
        return segundos % 60 == 0 ? (segundos / 60) + "m" : segundos + "s";
    }
}
