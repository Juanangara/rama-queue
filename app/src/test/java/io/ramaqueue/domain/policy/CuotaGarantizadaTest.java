package io.ramaqueue.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CuotaGarantizadaTest {

    @Test
    void conCiclosDeUnMensajeLaCuotaDelVeintiCincoPorCientoEsUnoDeCadaCuatroNoTodos() {
        CuotaGarantizada cuota = new CuotaGarantizada(0.25, 10);

        int total = 0;
        for (int ciclo = 0; ciclo < 100; ciclo++) {
            total += cuota.asignar(1);
        }

        // Con ceil(1 * 0,25) = 1 serían 100 de 100: el sesgo que dejaba sin turnos al trabajo pequeño.
        assertThat(total).isEqualTo(25);
    }

    @Test
    void conCiclosDeCualquierTamanoLaProporcionALargoPlazoEsLaPedida() {
        CuotaGarantizada cuota = new CuotaGarantizada(0.25, 10);
        int[] tamanos = {1, 2, 3, 7, 1, 1, 5, 2, 10, 1};

        int cupoTotal = 0;
        int garantizados = 0;
        for (int vuelta = 0; vuelta < 40; vuelta++) {
            for (int cupo : tamanos) {
                cupoTotal += cupo;
                garantizados += cuota.asignar(cupo);
            }
        }

        assertThat(garantizados).isBetween((int) (cupoTotal * 0.25) - 1, (int) (cupoTotal * 0.25) + 1);
    }

    @Test
    void nuncaAsignaMasDelCupoDelCiclo() {
        CuotaGarantizada cuota = new CuotaGarantizada(1.0, 10);

        for (int i = 0; i < 20; i++) {
            assertThat(cuota.asignar(3)).isLessThanOrEqualTo(3);
        }
        assertThat(cuota.asignar(3)).isEqualTo(3); // fracción 1 = todo el cupo
    }

    @Test
    void elCreditoAcumuladoTieneTope() {
        CuotaGarantizada cuota = new CuotaGarantizada(0.5, 4);

        for (int i = 0; i < 100; i++) {
            cuota.asignar(0); // sin cupo no se acumula nada
        }
        assertThat(cuota.asignar(1)).isZero(); // 0,5 de crédito: aún no completa un ítem
        assertThat(cuota.asignar(1)).isEqualTo(1);
    }

    @Test
    void conFraccionCeroNuncaAsigna() {
        CuotaGarantizada cuota = new CuotaGarantizada(0.0, 10);

        assertThat(cuota.asignar(50)).isZero();
    }
}
