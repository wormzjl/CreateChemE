package com.wormzjl.createcheme.science.column.nextgen;

/**
 * Water saturation and caloric properties from IAPWS-IF97 Regions 1, 2, and 4.
 *
 * <p>The published equations use MPa and kJ/(kg K) as reducing units internally. This class accepts and returns
 * SI values: K, Pa, and J/mol. Its enthalpy methods retain the IAPWS-IF97 reference state; a caller which combines
 * water with a different component-datum convention must apply one common, phase-independent datum shift.
 * Region 3, the critical region, and the metastable-vapor supplementary equation are intentionally not represented.
 */
public final class If97Water {
    /** IAPWS-IF97 specific gas constant, in J/(kg K). */
    private static final double SPECIFIC_GAS_CONSTANT_JOULES_PER_KILOGRAM_KELVIN = 461.526;
    /** The package's canonical water molecular mass, used only to convert IF97 mass-specific values to J/mol. */
    private static final double WATER_MOLAR_MASS_KILOGRAMS_PER_MOL = 0.01801528;
    private static final double MEGAPASCAL_TO_PASCAL = 1_000_000.0;
    private static final double MINIMUM_CDU_TEMPERATURE_KELVIN = 298.15;
    private static final double MAXIMUM_CDU_TEMPERATURE_KELVIN = 900.0;
    private static final double MINIMUM_CDU_PRESSURE_PASCAL = 50_000.0;
    // A water-vapor partial pressure may be below the column's total-pressure envelope. Region 2 remains the
    // correct stable-vapor equation there; zero flow is handled by the caller and never passed as a state.
    private static final double MINIMUM_VAPOR_PARTIAL_PRESSURE_PASCAL = 1.0;
    private static final double MAXIMUM_UTILITY_PRESSURE_PASCAL = 10_000_000.0;
    private static final double REGION_ONE_MAXIMUM_TEMPERATURE_KELVIN = 623.15;

    public static final double CRITICAL_TEMPERATURE_KELVIN = 647.096;
    public static final double CRITICAL_PRESSURE_PASCAL = 22_064_000.0;

    // IAPWS-IF97 Region 4, Table 34, Eqs. (29)-(31).
    private static final double N1 = 1.1670521452767e3;
    private static final double N2 = -7.2421316703206e5;
    private static final double N3 = -1.7073846940092e1;
    private static final double N4 = 1.2020824702470e4;
    private static final double N5 = -3.2325550322333e6;
    private static final double N6 = 1.4915108613530e1;
    private static final double N7 = -4.8232657361591e3;
    private static final double N8 = 4.0511340542057e5;
    private static final double N9 = -2.3855557567849e-1;
    private static final double N10 = 6.5017534844798e2;
    private static final double REGION_FOUR_MINIMUM_PRESSURE_PASCAL = 611.212677444;

    // IAPWS-IF97 Region 1, Table 2, Eq. (7): gamma = sum(n * (7.1 - pi)^I * (tau - 1.222)^J).
    private static final int[] REGION_ONE_PRESSURE_EXPONENTS = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2,
            2, 2, 3, 3, 3, 4, 4, 4, 5, 8, 8, 21, 23, 29, 30, 31, 32
    };
    private static final int[] REGION_ONE_TEMPERATURE_EXPONENTS = {
            -2, -1, 0, 1, 2, 3, 4, 5, -9, -7, -1, 0, 1, 3, -3, 0, 1,
            3, 17, -4, 0, 6, -5, -2, 10, -8, -11, -6, -29, -31, -38, -39, -40, -41
    };
    private static final double[] REGION_ONE_COEFFICIENTS = {
            0.14632971213167,
            -0.84548187169114,
            -3.7563603672040,
            3.3855169168385,
            -0.95791963387872,
            0.15772038513228,
            -0.016616417199501,
            0.00081214629983568,
            0.00028319080123804,
            -0.00060706301565874,
            -0.018990068218419,
            -0.032529748770505,
            -0.021841717175414,
            -0.000052838357969930,
            -0.00047184321073267,
            -0.00030001780793026,
            0.000047661393906987,
            -0.0000044141845330846,
            -0.00000000000000072694996297594,
            -0.000031679644845054,
            -0.0000028270797985312,
            -0.00000000085205128120103,
            -0.0000022425281908000,
            -0.00000065171222895601,
            -0.00000000000014341729937924,
            -0.00000040516996860117,
            -0.0000000012734301741641,
            -0.00000000017424871230634,
            -0.00000000000000000068762131295531,
            0.000000000000000000014478307828521,
            0.000000000000000000000026335781662795,
            -0.000000000000000000000011947622640071,
            0.0000000000000000000000018228094581404,
            -0.000000000000000000000000093537087292458
    };

    // IAPWS-IF97 Region 2, Tables 10 and 11, Eqs. (15)-(17).
    private static final int[] REGION_TWO_IDEAL_TEMPERATURE_EXPONENTS = {0, 1, -5, -4, -3, -2, -1, 2, 3};
    private static final double[] REGION_TWO_IDEAL_COEFFICIENTS = {
            -9.6927686500217,
            10.0866555968018,
            -0.0056087911283020,
            0.071452738081455,
            -0.40710498223928,
            1.4240819171444,
            -4.3839511319450,
            -0.28408632460772,
            0.021268463753307
    };
    private static final int[] REGION_TWO_PRESSURE_EXPONENTS = {
            1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 5, 6, 6, 6,
            7, 7, 7, 8, 8, 9, 10, 10, 10, 16, 16, 18, 20, 20, 20, 21, 22, 23, 24, 24, 24
    };
    private static final int[] REGION_TWO_TEMPERATURE_EXPONENTS = {
            0, 1, 2, 3, 6, 1, 2, 4, 7, 36, 0, 1, 3, 6, 35, 1, 2, 3, 7, 3, 16, 35,
            0, 11, 25, 8, 36, 13, 4, 10, 14, 29, 50, 57, 20, 35, 48, 21, 53, 39, 26, 40, 58
    };
    private static final double[] REGION_TWO_COEFFICIENTS = {
            -0.0017731742473213,
            -0.017834862292358,
            -0.045996013696365,
            -0.057581259083432,
            -0.050325278727930,
            -0.000033032641670203,
            -0.00018948987516315,
            -0.0039392777243355,
            -0.043797295650573,
            -0.000026674547914087,
            0.000000020481737692309,
            0.00000043870667284435,
            -0.000032277677238570,
            -0.0015033924542148,
            -0.040668253562649,
            -0.00000000078847309559367,
            0.000000012790717852285,
            0.00000048225372718507,
            0.0000022922076337661,
            -0.000000000016714766451061,
            -0.0021171472321355,
            -23.895741934104,
            -0.0000000000000000059059564324270,
            -0.0000012621808899101,
            -0.038946842435739,
            0.000000000011256211360459,
            -8.2311340897998,
            0.000000019809712802088,
            0.00000000000000000010406965210174,
            -0.00000000000010234747095929,
            -0.0000000010018179379511,
            -0.000000000080882908646985,
            0.10693031879409,
            -0.33662250574171,
            0.00000000000000000000000089185845355421,
            0.00000000000030629316876232,
            -0.0000042002467698208,
            -0.000000000000000000000000059056029685639,
            0.0000037826947613457,
            -0.0000000000000012768608934681,
            0.000000000000000000000000000073087610595061,
            0.000000000000000055414715350778,
            -0.00000094369707241210
    };

    private If97Water() {}

    /**
     * IF97 Region-4 saturation pressure in Pa.
     *
     * @throws IllegalArgumentException if {@code temperatureKelvin} is non-finite or outside 273.15 K to the
     *                                  unsupported critical point
     */
    public static double saturationPressurePascal(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < 273.15
                || temperatureKelvin >= CRITICAL_TEMPERATURE_KELVIN) {
            throw new IllegalArgumentException("Water saturation temperature is outside IF97 Region 4");
        }
        double theta = temperatureKelvin + N9 / (temperatureKelvin - N10);
        double a = theta * theta + N1 * theta + N2;
        double b = N3 * theta * theta + N4 * theta + N5;
        double c = N6 * theta * theta + N7 * theta + N8;
        double pressureMegapascal = Math.pow(2.0 * c / (-b + Math.sqrt(b * b - 4.0 * a * c)), 4.0);
        return pressureMegapascal * MEGAPASCAL_TO_PASCAL;
    }

    /**
     * IF97 Region-4 saturation temperature in K, evaluated with the direct published backward equation.
     *
     * @throws IllegalArgumentException if {@code pressurePascal} is non-finite or outside the Region-4 range
     */
    public static double saturationTemperatureKelvin(double pressurePascal) {
        if (!Double.isFinite(pressurePascal) || pressurePascal < REGION_FOUR_MINIMUM_PRESSURE_PASCAL
                || pressurePascal >= CRITICAL_PRESSURE_PASCAL) {
            throw new IllegalArgumentException("Water saturation pressure is outside IF97 Region 4");
        }
        double beta = Math.pow(pressurePascal / MEGAPASCAL_TO_PASCAL, 0.25);
        double e = beta * beta + N3 * beta + N6;
        double f = N1 * beta * beta + N4 * beta + N7;
        double g = N2 * beta * beta + N5 * beta + N8;
        double d = 2.0 * g / (-f - Math.sqrt(f * f - 4.0 * e * g));
        return 0.5 * (N10 + d - Math.sqrt((N10 + d) * (N10 + d) - 4.0 * (N9 + N10 * d)));
    }

    /**
     * Region-1 liquid-water enthalpy in J/mol for a stable compressed or saturated-liquid state in the supported
     * CDU/utility envelope.
     *
     * @throws IllegalArgumentException if the state is non-finite, outside the envelope, or is not in Region 1
     */
    public static double liquidEnthalpyJoulesPerMol(double temperatureKelvin, double pressurePascal) {
        requireCduEnvelope(temperatureKelvin, pressurePascal);
        requireRegionOne(temperatureKelvin, pressurePascal);
        return regionOneSpecificEnthalpyJoulesPerKilogram(temperatureKelvin, pressurePascal)
                * WATER_MOLAR_MASS_KILOGRAMS_PER_MOL;
    }

    /**
     * Region-2 water-vapor enthalpy in J/mol for a stable superheated or saturated-vapor state in the supported
     * CDU/utility envelope.
     *
     * @throws IllegalArgumentException if the state is non-finite, outside the envelope, or is not in Region 2
     */
    public static double vaporEnthalpyJoulesPerMol(double temperatureKelvin, double pressurePascal) {
        requireVaporEnvelope(temperatureKelvin, pressurePascal);
        requireRegionTwo(temperatureKelvin, pressurePascal);
        return regionTwoSpecificEnthalpyJoulesPerKilogram(temperatureKelvin, pressurePascal)
                * WATER_MOLAR_MASS_KILOGRAMS_PER_MOL;
    }

    private static double regionOneSpecificEnthalpyJoulesPerKilogram(double temperatureKelvin, double pressurePascal) {
        double pi = pressurePascal / (16.53 * MEGAPASCAL_TO_PASCAL);
        double tau = 1386.0 / temperatureKelvin;
        double pressureTerm = 7.1 - pi;
        double temperatureTerm = tau - 1.222;
        double gammaTau = 0.0;
        for (int index = 0; index < REGION_ONE_COEFFICIENTS.length; index++) {
            int temperatureExponent = REGION_ONE_TEMPERATURE_EXPONENTS[index];
            gammaTau += REGION_ONE_COEFFICIENTS[index] * temperatureExponent
                    * Math.pow(pressureTerm, REGION_ONE_PRESSURE_EXPONENTS[index])
                    * Math.pow(temperatureTerm, temperatureExponent - 1);
        }
        return SPECIFIC_GAS_CONSTANT_JOULES_PER_KILOGRAM_KELVIN * temperatureKelvin * tau * gammaTau;
    }

    private static double regionTwoSpecificEnthalpyJoulesPerKilogram(double temperatureKelvin, double pressurePascal) {
        double pi = pressurePascal / MEGAPASCAL_TO_PASCAL;
        double tau = 540.0 / temperatureKelvin;
        double gammaTau = 0.0;
        for (int index = 0; index < REGION_TWO_IDEAL_COEFFICIENTS.length; index++) {
            int temperatureExponent = REGION_TWO_IDEAL_TEMPERATURE_EXPONENTS[index];
            gammaTau += REGION_TWO_IDEAL_COEFFICIENTS[index] * temperatureExponent
                    * Math.pow(tau, temperatureExponent - 1);
        }
        double temperatureTerm = tau - 0.5;
        for (int index = 0; index < REGION_TWO_COEFFICIENTS.length; index++) {
            int temperatureExponent = REGION_TWO_TEMPERATURE_EXPONENTS[index];
            gammaTau += REGION_TWO_COEFFICIENTS[index] * temperatureExponent
                    * Math.pow(pi, REGION_TWO_PRESSURE_EXPONENTS[index])
                    * Math.pow(temperatureTerm, temperatureExponent - 1);
        }
        return SPECIFIC_GAS_CONSTANT_JOULES_PER_KILOGRAM_KELVIN * temperatureKelvin * tau * gammaTau;
    }

    private static void requireCduEnvelope(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < MINIMUM_CDU_TEMPERATURE_KELVIN
                || temperatureKelvin > MAXIMUM_CDU_TEMPERATURE_KELVIN
                || !Double.isFinite(pressurePascal) || pressurePascal < MINIMUM_CDU_PRESSURE_PASCAL
                || pressurePascal > MAXIMUM_UTILITY_PRESSURE_PASCAL) {
            throw new IllegalArgumentException("Water state is outside the supported IF97 CDU envelope");
        }
    }

    private static void requireRegionOne(double temperatureKelvin, double pressurePascal) {
        if (temperatureKelvin > REGION_ONE_MAXIMUM_TEMPERATURE_KELVIN) {
            throw new IllegalArgumentException("Liquid water state requires unsupported IF97 Region 3");
        }
        if (pressurePascal < saturationPressurePascal(temperatureKelvin)) {
            throw new IllegalArgumentException("Liquid water state is below the IF97 Region-1 saturation boundary");
        }
    }

    private static void requireVaporEnvelope(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < MINIMUM_CDU_TEMPERATURE_KELVIN
                || temperatureKelvin > MAXIMUM_CDU_TEMPERATURE_KELVIN
                || !Double.isFinite(pressurePascal) || pressurePascal < MINIMUM_VAPOR_PARTIAL_PRESSURE_PASCAL
                || pressurePascal > MAXIMUM_UTILITY_PRESSURE_PASCAL) {
            throw new IllegalArgumentException("Water vapor state is outside the supported IF97 CDU envelope");
        }
    }

    private static void requireRegionTwo(double temperatureKelvin, double pressurePascal) {
        // The 10 MPa package cap is below the Region-2/3 B23 boundary for every T in (623.15, 863.15] K.
        if (temperatureKelvin <= REGION_ONE_MAXIMUM_TEMPERATURE_KELVIN
                && pressurePascal > saturationPressurePascal(temperatureKelvin)) {
            throw new IllegalArgumentException("Water vapor state is below the IF97 Region-2 saturation boundary");
        }
    }
}
