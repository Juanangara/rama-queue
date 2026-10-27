package io.ramaqueue.domain.policy;

/** Estado del límite adaptativo: peticiones en vuelo permitidas y separación mínima entre peticiones. */
public record LimiteAdaptativo(int maxEnVuelo, long intervaloMs, int rachaDeExitos) {
}
