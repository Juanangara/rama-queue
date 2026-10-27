package io.ramaqueue.infrastructure.out.rama;

/** Auditoría de cada petición saliente: la evidencia de qué tan amable fue el sistema con el servidor. */
public interface RegistroDePeticiones {

    void registrar(String endpoint, Integer estadoHttp, String resultado, long duracionMs);
}
