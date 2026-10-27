package io.ramaqueue.mock.dataset;

import java.util.List;
import java.util.SplittableRandom;

/** Generador de nombres ficticios con distribución tipo Zipf (unos pocos muy comunes, muchos raros). */
final class Nombres {

    private static final List<String> NOMBRES = List.of(
            "JUAN", "CARLOS", "JOSE", "LUIS", "MARIA", "ANA", "LUZ", "JORGE", "ANDRES", "DIEGO",
            "CAMILA", "SANDRA", "PEDRO", "MIGUEL", "FERNANDO", "ALEJANDRO", "PAOLA", "CLAUDIA", "DANIEL", "JAIRO",
            "MARTHA", "NELSON", "GLORIA", "EDWIN", "DIANA", "RICARDO", "PATRICIA", "SERGIO", "LAURA", "OSCAR",
            "ADRIANA", "HUGO", "ROSA", "MARIO", "NUBIA", "JAVIER", "ELENA", "WILSON", "YOLANDA", "FELIPE");

    private static final List<String> APELLIDOS = List.of(
            "GARCIA", "RODRIGUEZ", "MARTINEZ", "LOPEZ", "GONZALEZ", "HERNANDEZ", "PEREZ", "SANCHEZ", "RAMIREZ", "TORRES",
            "DIAZ", "RAMOS", "GOMEZ", "MORENO", "JIMENEZ", "CASTRO", "VARGAS", "ROJAS", "ORTIZ", "SILVA",
            "MUNOZ", "CASTILLO", "MENDOZA", "OSPINA", "CARDONA", "GUTIERREZ", "SUAREZ", "RIOS", "VALENCIA", "MEJIA",
            "QUINTERO", "BEDOYA", "ARIAS", "RESTREPO", "PARRA", "CORREA", "ZAPATA", "GIRALDO", "AGUDELO", "DUQUE",
            "PINEDA", "LONDONO", "MONTOYA", "BETANCUR", "ALVAREZ", "HERRERA", "MEDINA", "CORTES", "PAEZ", "NARANJO",
            "OCHOA", "SALAZAR", "ECHEVERRI", "VELEZ", "BOHORQUEZ", "CARVAJAL", "LEON", "BARRERA", "CABRERA", "ACOSTA");

    private static final List<String> PREFIJOS = List.of(
            "INVERSIONES", "COMERCIALIZADORA", "CONSTRUCCIONES", "DISTRIBUIDORA", "SERVICIOS", "TRANSPORTES",
            "AGROPECUARIA", "COOPERATIVA", "FUNDACION", "CORPORACION", "INDUSTRIAS", "SOLUCIONES");

    private static final List<String> NUCLEOS = List.of(
            "ANDINA", "DEL VALLE", "LA ESPERANZA", "SANTA ROSA", "EL DORADO", "DEL CARIBE", "LOS ANDES", "NUEVO HORIZONTE",
            "DEL PACIFICO", "SAN JORGE", "CENTRAL", "ORIENTAL", "LA PALMA", "EL PORVENIR", "BUENAVISTA", "TIERRA NUEVA",
            "MONTECARLO", "ALTAMIRA", "LAS ACACIAS", "PUNTA BRAVA", "RIO CLARO", "SOL NACIENTE", "VILLA REAL", "LOS ROBLES");

    private static final List<String> SUFIJOS = List.of("S.A.S", "LTDA", "S.A", "S.A.S", "E.U");

    private static final Zipf ZIPF_NOMBRES = new Zipf(NOMBRES.size(), 0.7);
    private static final Zipf ZIPF_APELLIDOS = new Zipf(APELLIDOS.size(), 1.0);
    private static final Zipf ZIPF_NUCLEOS = new Zipf(NUCLEOS.size(), 1.0);

    private Nombres() {
    }

    static String persona(SplittableRandom r) {
        StringBuilder sb = new StringBuilder(NOMBRES.get(ZIPF_NOMBRES.muestra(r)));
        if (r.nextInt(10) < 6) {
            sb.append(' ').append(NOMBRES.get(ZIPF_NOMBRES.muestra(r)));
        }
        sb.append(' ').append(APELLIDOS.get(ZIPF_APELLIDOS.muestra(r)));
        sb.append(' ').append(APELLIDOS.get(ZIPF_APELLIDOS.muestra(r)));
        return sb.toString();
    }

    static String empresa(SplittableRandom r) {
        return PREFIJOS.get(r.nextInt(PREFIJOS.size())) + " " + NUCLEOS.get(ZIPF_NUCLEOS.muestra(r))
                + " " + SUFIJOS.get(r.nextInt(SUFIJOS.size()));
    }

    static String juez(SplittableRandom r) {
        return NOMBRES.get(r.nextInt(NOMBRES.size())) + " " + APELLIDOS.get(r.nextInt(APELLIDOS.size()))
                + " " + APELLIDOS.get(r.nextInt(APELLIDOS.size()));
    }

    /** Muestreo Zipf por búsqueda binaria sobre la distribución acumulada. */
    private static final class Zipf {
        private final double[] acumulada;

        Zipf(int n, double exponente) {
            acumulada = new double[n];
            double suma = 0;
            for (int i = 0; i < n; i++) {
                suma += 1.0 / Math.pow(i + 1, exponente);
                acumulada[i] = suma;
            }
            for (int i = 0; i < n; i++) {
                acumulada[i] /= suma;
            }
        }

        int muestra(SplittableRandom r) {
            double u = r.nextDouble();
            int lo = 0;
            int hi = acumulada.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (acumulada[mid] < u) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }
}
