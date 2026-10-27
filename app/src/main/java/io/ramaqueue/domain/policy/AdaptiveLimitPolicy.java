package io.ramaqueue.domain.policy;

/**
 * AIMD: ante un bloqueo se reduce a la mitad la concurrencia y se duplica la separación entre peticiones;
 * tras una racha larga de éxitos se recupera primero la separación (de a 25 %) y solo después la concurrencia (de a 1).
 */
public record AdaptiveLimitPolicy(
        int enVueloInicial,
        int enVueloTope,
        long intervaloBaseMs,
        long intervaloMaximoMs,
        int exitosParaSubir) {

    public static AdaptiveLimitPolicy porDefecto() {
        return new AdaptiveLimitPolicy(2, 4, 1_000, 8_000, 50);
    }

    public LimiteAdaptativo inicial() {
        return new LimiteAdaptativo(enVueloInicial, intervaloBaseMs, 0);
    }

    /**
     * Ajusta un estado guardado a los límites vigentes. Si la configuración cambió entre dos arranques (p. ej. un intervalo
     * base más amable), el estado viejo no puede saltarse el nuevo suelo ni el nuevo tope.
     */
    public LimiteAdaptativo acotar(LimiteAdaptativo guardado) {
        return new LimiteAdaptativo(
                Math.min(enVueloTope, Math.max(1, guardado.maxEnVuelo())),
                Math.min(intervaloMaximoMs, Math.max(intervaloBaseMs, guardado.intervaloMs())),
                guardado.rachaDeExitos());
    }

    public LimiteAdaptativo alBloqueo(LimiteAdaptativo actual) {
        return new LimiteAdaptativo(
                Math.max(1, actual.maxEnVuelo() / 2),
                Math.min(intervaloMaximoMs, actual.intervaloMs() * 2),
                0);
    }

    public LimiteAdaptativo alExito(LimiteAdaptativo actual) {
        int racha = actual.rachaDeExitos() + 1;
        if (racha < exitosParaSubir) {
            return new LimiteAdaptativo(actual.maxEnVuelo(), actual.intervaloMs(), racha);
        }
        if (actual.intervaloMs() > intervaloBaseMs) {
            return new LimiteAdaptativo(
                    actual.maxEnVuelo(), Math.max(intervaloBaseMs, actual.intervaloMs() * 3 / 4), 0);
        }
        return new LimiteAdaptativo(Math.min(enVueloTope, actual.maxEnVuelo() + 1), actual.intervaloMs(), 0);
    }
}
