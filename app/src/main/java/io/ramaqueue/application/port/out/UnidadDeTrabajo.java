package io.ramaqueue.application.port.out;

import java.util.function.Supplier;

/** Frontera transaccional. Todo lo que se ejecute dentro se confirma o se deshace junto. */
public interface UnidadDeTrabajo {

    <T> T ejecutar(Supplier<T> trabajo);

    default void ejecutar(Runnable trabajo) {
        ejecutar(() -> {
            trabajo.run();
            return null;
        });
    }
}
