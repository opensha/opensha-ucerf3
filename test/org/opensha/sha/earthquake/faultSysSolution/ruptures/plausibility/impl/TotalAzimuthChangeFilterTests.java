package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl;

import static org.junit.Assert.*;

import org.junit.Test;
import scratch.UCERF3.inversion.laughTest.PlausibilityResult;

public class TotalAzimuthChangeFilterTests {

    public PlausibilityResult jump(double fromAzimuth, double[] toAzimuths, float threshold) {
        JumpDataMock data = new JumpDataMock(new double[]{fromAzimuth}, Double.MAX_VALUE, toAzimuths);
        
        TotalAzimuthChangeFilter jumpFilter = new TotalAzimuthChangeFilter(data.calc, threshold, true, true); // last two args are ignored by filter
        return jumpFilter.testJump(data.rupture, data.jump, false);
    }

    @Test
    public void testJump() {
        assertEquals(PlausibilityResult.PASS, jump(0, new double[]{10, 20, 30, 60}, 60));
        assertEquals(PlausibilityResult.FAIL_HARD_STOP, jump(0, new double[]{10, 20, 30, 61}, 60));

        assertEquals(PlausibilityResult.PASS, jump(-10, new double[]{10, 20, 30, 50}, 60));
        assertEquals(PlausibilityResult.FAIL_HARD_STOP, jump(-10, new double[]{10, 20, 30, 51}, 60));
    }
}
