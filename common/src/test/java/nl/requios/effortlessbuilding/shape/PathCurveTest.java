package nl.requios.effortlessbuilding.shape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathCurveTest {

    @Test
    void straightLineHasEvenStops() {
        List<double[]> stops = PathCurve.stops(List.of(new double[]{0, 0, 0}, new double[]{20, 0, 0}), 5);
        assertEquals(5, stops.size(), "0, 5, 10, 15, 20");
        for (int i = 0; i < stops.size(); i++) {
            assertEquals(i * 5, stops.get(i)[0], 0.2);
            assertEquals(0, stops.get(i)[2], 1e-6);
            assertTrue(stops.get(i)[3] > 0, "heading east");
        }
    }

    @Test
    void curvePassesThroughEveryPointAndEndsOnTheLast() {
        List<double[]> points = List.of(new double[]{0, 0, 0}, new double[]{10, 0, 10}, new double[]{20, 0, 0});
        List<double[]> stops = PathCurve.stops(points, 1);
        double[] last = stops.getLast();
        assertEquals(20, last[0], 1e-6);
        assertEquals(0, last[2], 1e-6);
        // Some stop lands on the middle point
        assertTrue(stops.stream().anyMatch(s -> Math.abs(s[0] - 10) < 0.8 && Math.abs(s[2] - 10) < 0.8));
        // Along the curve, so there are more stops than the straight distance would give
        assertTrue(stops.size() > 21);
    }
}
