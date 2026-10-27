package io.ramaqueue.infrastructure.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Conversión entre {@link Instant} y {@code timestamptz} (microsegundos de precisión en Postgres). */
final class Tiempo {

    private Tiempo() {
    }

    static OffsetDateTime aBd(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    static Instant deBd(OffsetDateTime valor) {
        return valor == null ? null : valor.toInstant();
    }
}
