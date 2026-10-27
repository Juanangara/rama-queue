package io.ramaqueue.domain.policy;

import io.ramaqueue.domain.model.TipoTrabajo;
import io.ramaqueue.domain.policy.CircuitBreakerState.Estado;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienciaTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private final BackoffPolicy backoff = BackoffPolicy.porDefecto();

    // ─── Clasificación HTTP ──────────────────────────────────────────────────────

    @Test
    void clasificaLasRespuestasHttpSegunElTipoDeItem() {
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.SEARCH, 200)).isEqualTo(ResultadoHttp.OK);
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.SEARCH, 400)).isEqualTo(ResultadoHttp.EXCEDE_MIL);
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.DETALLE, 400)).isEqualTo(ResultadoHttp.RECHAZO_DEFINITIVO);
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.SEARCH, 404)).isEqualTo(ResultadoHttp.RECHAZO_DEFINITIVO);
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.LIST, 404)).isEqualTo(ResultadoHttp.LISTA_VACIA);
        assertThat(HttpOutcomeClassifier.clasificar(TipoTrabajo.DETALLE, 404)).isEqualTo(ResultadoHttp.RECHAZO_DEFINITIVO);
        for (TipoTrabajo tipo : TipoTrabajo.values()) {
            assertThat(HttpOutcomeClassifier.clasificar(tipo, 403)).isEqualTo(ResultadoHttp.BLOQUEADO);
            assertThat(HttpOutcomeClassifier.clasificar(tipo, 429)).isEqualTo(ResultadoHttp.BLOQUEADO);
            assertThat(HttpOutcomeClassifier.clasificar(tipo, 500)).isEqualTo(ResultadoHttp.TRANSITORIO);
            assertThat(HttpOutcomeClassifier.clasificar(tipo, 503)).isEqualTo(ResultadoHttp.TRANSITORIO);
            assertThat(HttpOutcomeClassifier.clasificar(tipo, 408)).isEqualTo(ResultadoHttp.TRANSITORIO);
            assertThat(HttpOutcomeClassifier.clasificar(tipo, HttpOutcomeClassifier.SIN_RESPUESTA))
                    .isEqualTo(ResultadoHttp.TRANSITORIO);
        }
    }

    // ─── Backoff ─────────────────────────────────────────────────────────────────

    @Test
    void elEnfriamientoCreceConCadaAperturaSeguidaHastaUnTope() {
        assertThat(backoff.enfriamiento(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(backoff.enfriamiento(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(backoff.enfriamiento(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(backoff.enfriamiento(4)).isEqualTo(Duration.ofMinutes(15));
        assertThat(backoff.enfriamiento(40)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void losReintentosUsanNivelesDeEsperaCrecientesConElUltimoComoTecho() {
        assertThat(backoff.esperaDeReintento(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.esperaDeReintento(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(backoff.esperaDeReintento(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(backoff.esperaDeReintento(9)).isEqualTo(Duration.ofMinutes(8));
        assertThat(backoff.indiceNivel(1)).isZero();
        assertThat(backoff.indiceNivel(9)).isEqualTo(2);
    }

    @Test
    void losIntentosSeAgotanAlLlegarAlMaximo() {
        assertThat(backoff.agotado(4)).isFalse();
        assertThat(backoff.agotado(5)).isTrue();
    }

    // ─── AIMD ────────────────────────────────────────────────────────────────────

    private final AdaptiveLimitPolicy aimd = AdaptiveLimitPolicy.porDefecto();

    @Test
    void ungBloqueoReduceLaConcurrenciaALaMitadYDuplicaLaSeparacion() {
        LimiteAdaptativo l = aimd.inicial();
        assertThat(l.maxEnVuelo()).isEqualTo(2);
        assertThat(l.intervaloMs()).isEqualTo(1_000);

        l = aimd.alBloqueo(l);
        assertThat(l.maxEnVuelo()).isEqualTo(1);
        assertThat(l.intervaloMs()).isEqualTo(2_000);

        l = aimd.alBloqueo(aimd.alBloqueo(aimd.alBloqueo(aimd.alBloqueo(l))));
        assertThat(l.maxEnVuelo()).isEqualTo(1);
        assertThat(l.intervaloMs()).isEqualTo(8_000);
    }

    @Test
    void unEstadoGuardadoSeAcotaALaConfiguracionVigente() {
        // El estado quedó guardado con una configuración más agresiva (150 ms, 8 en vuelo) que la actual (1000 ms, tope 4).
        LimiteAdaptativo viejo = new LimiteAdaptativo(8, 150, 12);
        assertThat(aimd.acotar(viejo)).isEqualTo(new LimiteAdaptativo(4, 1_000, 12));

        // Un valor válido no se toca, y uno por encima del máximo baja al máximo.
        LimiteAdaptativo valido = new LimiteAdaptativo(2, 4_000, 3);
        assertThat(aimd.acotar(valido)).isEqualTo(valido);
        assertThat(aimd.acotar(new LimiteAdaptativo(0, 90_000, 0))).isEqualTo(new LimiteAdaptativo(1, 8_000, 0));
    }

    @Test
    void trasUnaRachaDeExitosSeRecuperaPrimeroLaSeparacionYLuegoLaConcurrencia() {
        LimiteAdaptativo l = aimd.alBloqueo(aimd.alBloqueo(aimd.inicial())); // 1 en vuelo, 4000 ms
        assertThat(l.intervaloMs()).isEqualTo(4_000);

        l = exitos(l, 49);
        assertThat(l.intervaloMs()).isEqualTo(4_000);
        l = exitos(l, 1);
        assertThat(l.intervaloMs()).isEqualTo(3_000);
        assertThat(l.maxEnVuelo()).isEqualTo(1);

        l = exitos(l, 50);
        assertThat(l.intervaloMs()).isEqualTo(2_250);
        l = exitos(l, 50 * 3);
        assertThat(l.intervaloMs()).isEqualTo(1_000);
        assertThat(l.maxEnVuelo()).isEqualTo(1);

        l = exitos(l, 50);
        assertThat(l.maxEnVuelo()).isEqualTo(2);
        l = exitos(l, 50 * 10);
        assertThat(l.maxEnVuelo()).isEqualTo(4);
        assertThat(l.intervaloMs()).isEqualTo(1_000);
    }

    @Test
    void unBloqueoReiniciaLaRacha() {
        LimiteAdaptativo l = exitos(aimd.inicial(), 30);
        assertThat(l.rachaDeExitos()).isEqualTo(30);
        assertThat(aimd.alBloqueo(l).rachaDeExitos()).isZero();
    }

    private LimiteAdaptativo exitos(LimiteAdaptativo inicial, int n) {
        LimiteAdaptativo l = inicial;
        for (int i = 0; i < n; i++) {
            l = aimd.alExito(l);
        }
        return l;
    }

    // ─── Circuit breaker ─────────────────────────────────────────────────────────

    @Test
    void unBloqueoAbreElBreakerConElEnfriamientoDeLaPrimeraApertura() {
        CircuitBreakerState b = CircuitBreakerState.cerrado().alBloqueo(T0, backoff);

        assertThat(b.estado()).isEqualTo(Estado.ABIERTO);
        assertThat(b.aperturasSeguidas()).isEqualTo(1);
        assertThat(b.abiertoHasta()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
        assertThat(b.permitePeticiones(T0.plusSeconds(10))).isFalse();
        assertThat(b.restante(T0.plusSeconds(20))).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void losOtros403QueIbanEnVueloNoEscalanElEnfriamiento() {
        CircuitBreakerState abierto = CircuitBreakerState.cerrado().alBloqueo(T0, backoff);

        assertThat(abierto.alBloqueo(T0.plusSeconds(1), backoff)).isSameAs(abierto);
        assertThat(abierto.alBloqueo(T0.plusSeconds(30), backoff).aperturasSeguidas()).isEqualTo(1);
    }

    @Test
    void alTerminarElEnfriamientoPasaASemiabiertoYUnExitoLoCierra() {
        CircuitBreakerState abierto = CircuitBreakerState.cerrado().alBloqueo(T0, backoff);
        Instant despues = T0.plus(Duration.ofMinutes(2));

        assertThat(abierto.evaluar(despues.minusMillis(1)).estado()).isEqualTo(Estado.ABIERTO);
        CircuitBreakerState semiabierto = abierto.evaluar(despues);
        assertThat(semiabierto.estado()).isEqualTo(Estado.SEMIABIERTO);
        assertThat(abierto.permitePeticiones(despues)).isTrue();

        CircuitBreakerState cerrado = semiabierto.alExito();
        assertThat(cerrado.estado()).isEqualTo(Estado.CERRADO);
        assertThat(cerrado.aperturasSeguidas()).isZero();
    }

    @Test
    void siFallaLaSondaSeReabreConUnEnfriamientoMasLargo() {
        CircuitBreakerState semiabierto = CircuitBreakerState.cerrado().alBloqueo(T0, backoff)
                .evaluar(T0.plus(Duration.ofMinutes(2)));
        Instant ahora = T0.plus(Duration.ofMinutes(3));

        CircuitBreakerState reabierto = semiabierto.alBloqueo(ahora, backoff);

        assertThat(reabierto.estado()).isEqualTo(Estado.ABIERTO);
        assertThat(reabierto.aperturasSeguidas()).isEqualTo(2);
        assertThat(reabierto.abiertoHasta()).isEqualTo(ahora.plus(Duration.ofMinutes(4)));
    }

    @Test
    void unExitoRezagadoConElBreakerAbiertoNoLoCierra() {
        CircuitBreakerState abierto = CircuitBreakerState.cerrado().alBloqueo(T0, backoff);

        assertThat(abierto.alExito()).isSameAs(abierto);
        assertThat(CircuitBreakerState.cerrado().alExito().estado()).isEqualTo(Estado.CERRADO);
    }

    @Test
    void elBreakerCerradoSiemprePermitePeticiones() {
        assertThat(CircuitBreakerState.cerrado().permitePeticiones(T0)).isTrue();
        assertThat(CircuitBreakerState.cerrado().restante(T0)).isEqualTo(Duration.ZERO);
    }
}
