package com.goms.sample;

import java.util.List;

/** Small calculator used to try the Goms plug-in's Compile / Test / Fix with Claude buttons. */
public class Calc {

    public int add(int a, int b) {
        return a + b;
    }

    public int divide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("b must not be 0");
        }
        return a / b;
    }

    /** Average of the values; 0 for an empty list. */
    public double average(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return (double) total / values.size();
    }
}
