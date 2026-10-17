package com.goms.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class CalcTest {

    private final Calc calc = new Calc();

    @Test
    void add() {
        assertEquals(5, calc.add(2, 3));
    }

    @Test
    void divide() {
        assertEquals(3, calc.divide(7, 2));
    }

    @Test
    void divideByZero() {
        assertThrows(IllegalArgumentException.class, () -> calc.divide(1, 0));
    }

    @Test
    void average() {
        assertEquals(2.5, calc.average(List.of(1, 2, 3, 4)), 1e-9);
        assertEquals(0, calc.average(List.of()), 1e-9);
    }
}
