package uk.ac.ebi.tsc.tesk.tes.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

/** Normalisation of CPU cores (issue #44) */
public class TesResourcesTest {

    @Test
    public void cpuCores_null() {
        assertNull(new TesResources().cpuCores(null).getCpuCores());
    }

    @Test
    public void cpuCores_keptAsGiven() {
        BigDecimal given = new BigDecimal("1.250");
        TesResources resources = new TesResources();
        assertSame(resources, resources.cpuCores(given));
        assertSame(given, resources.getCpuCores());
    }

    @Test
    public void cpuCores_trailingZerosBeyondThreeDecimals() {
        assertEquals(
                "0.5",
                new TesResources().cpuCores(new BigDecimal("0.5000")).getCpuCores().toString());
    }

    @Test
    public void cpuCores_exponentOfWholeNumberRemoved() {
        assertEquals(
                "1000",
                new TesResources().cpuCores(new BigDecimal("1e3")).getCpuCores().toString());
    }

    /** Too many digits for {@code @Digits} anyway: kept with the exponent, not written out */
    @Test
    public void cpuCores_largeNumberKeptWithExponent() {
        assertEquals(
                "1E+8",
                new TesResources().cpuCores(new BigDecimal("1e8")).getCpuCores().toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cpuCores_hugeNumberRefused() {
        new TesResources().setCpuCores(new BigDecimal("1e10"));
    }

    @Test
    public void cpuCores_zeroInAnyNotation() {
        for (String zero : new String[] {"0e10", "0e-10", "0.0000"}) {
            assertEquals(
                    zero,
                    "0",
                    new TesResources().cpuCores(new BigDecimal(zero)).getCpuCores().toString());
        }
    }

    /** The backend parameters and their strictness count for equality (issue #145) */
    @Test
    public void backendParameters_accessorsAndEquality() {
        Map<String, String> parameters = Collections.singletonMap("VmSize", "Standard_D64_v3");
        TesResources resources = new TesResources();
        assertSame(resources, resources.backendParameters(parameters));
        assertSame(resources, resources.backendParametersStrict(true));
        assertSame(parameters, resources.getBackendParameters());
        assertEquals(Boolean.TRUE, resources.getBackendParametersStrict());
        TesResources same = new TesResources();
        same.setBackendParameters(parameters);
        same.setBackendParametersStrict(true);
        assertEquals(resources, same);
        assertEquals(resources.hashCode(), same.hashCode());
        assertEquals(resources.toString(), same.toString());
        assertTrue(resources.toString().contains("backendParametersStrict: true"));
        assertNotEquals(resources, new TesResources().backendParametersStrict(true));
        assertNotEquals(resources, new TesResources().backendParameters(parameters));
        assertNotEquals(
                new TesResources(), new TesResources().zones(Collections.singletonList("z")));
    }
}
