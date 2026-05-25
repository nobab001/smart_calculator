package com.example.smartcalculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CalculatorEngineTest {

    @Test
    fun testBasicArithmetic() {
        // Addition
        assertEquals(5.0, CalculatorEngine.eval("2+3"), 0.0001)
        assertEquals("5", CalculatorEngine.formatResult(CalculatorEngine.eval("2+3")))

        // Subtraction
        assertEquals(2.0, CalculatorEngine.eval("5-3"), 0.0001)
        assertEquals("2", CalculatorEngine.formatResult(CalculatorEngine.eval("5-3")))

        // Multiplication
        assertEquals(15.0, CalculatorEngine.eval("3*5"), 0.0001)
        assertEquals("15", CalculatorEngine.formatResult(CalculatorEngine.eval("3*5")))

        // Division
        assertEquals(2.5, CalculatorEngine.eval("5/2"), 0.0001)
        assertEquals("2.5", CalculatorEngine.formatResult(CalculatorEngine.eval("5/2")))
    }

    @Test
    fun testChainedOperations() {
        // Chained operations with BODMAS order
        assertEquals(11.0, CalculatorEngine.eval("5+3*2"), 0.0001)
        assertEquals("11", CalculatorEngine.formatResult(CalculatorEngine.eval("5+3*2")))

        assertEquals(16.0, CalculatorEngine.eval("(5+3)*2"), 0.0001)
        assertEquals("16", CalculatorEngine.formatResult(CalculatorEngine.eval("(5+3)*2")))

        assertEquals(17.0, CalculatorEngine.eval("5*3+2"), 0.0001)
        assertEquals("17", CalculatorEngine.formatResult(CalculatorEngine.eval("5*3+2")))

        assertEquals(14.0, CalculatorEngine.eval("20/2-2*3+10"), 0.0001)
    }

    @Test
    fun testEdgeCases() {
        // Division by zero
        val divZero = CalculatorEngine.eval("5/0")
        assertTrue(divZero.isInfinite() || divZero.isNaN())
        assertEquals("Error", CalculatorEngine.formatResult(divZero))

        val nestedDivZero = CalculatorEngine.eval("10+(5/(2-2))")
        assertTrue(nestedDivZero.isNaN() || nestedDivZero.isInfinite())
        assertEquals("Error", CalculatorEngine.formatResult(nestedDivZero))

        // Floating-point precision
        val sum = CalculatorEngine.eval("0.1+0.2")
        assertEquals(0.3, sum, 0.0001)
        assertEquals("0.3", CalculatorEngine.formatResult(sum))

        // Very large numbers
        val largeMult = CalculatorEngine.eval("1000000*1000000")
        assertEquals(1000000000000.0, largeMult, 0.1)
        assertEquals("1000000000000", CalculatorEngine.formatResult(largeMult))

        // Formatting test
        assertEquals("1,234,567", CalculatorEngine.formatNumber("1234567"))
        assertEquals("1,234,567.89", CalculatorEngine.formatNumber("1234567.89"))
        assertEquals("-123", CalculatorEngine.formatNumber("-123"))
        assertEquals("0", CalculatorEngine.formatNumber(""))
    }

    @Test
    fun testOperatorMapping() {
        assertEquals("*", CalculatorEngine.toCalcOp("×"))
        assertEquals("/", CalculatorEngine.toCalcOp("÷"))
        assertEquals("-", CalculatorEngine.toCalcOp("−"))
        assertEquals("+", CalculatorEngine.toCalcOp("+"))
    }

    @Test
    fun testRandomFuzzAndStress() {
        val random = Random(42) // Fixed seed for reproducibility
        val operators = listOf("+", "-", "*", "/")
        var crashCount = 0
        val totalIterations = 10000

        for (i in 0 until totalIterations) {
            // Generate a random math expression of depth 1 to 4
            val depth = random.nextInt(4) + 1
            val expr = generateRandomExpr(depth, random, operators)
            
            try {
                val result = CalculatorEngine.eval(expr)
                val formatted = CalculatorEngine.formatResult(result)
                
                // Assertions to verify basic consistency:
                if (formatted != "Error") {
                    assertTrue(formatted.isNotEmpty())
                }
            } catch (e: Exception) {
                crashCount++
                System.err.println("CRASH occurred for expression: $expr")
                e.printStackTrace()
            }
        }
        
        assertEquals("Calculator engine crashed during fuzz testing!", 0, crashCount)
    }

    private fun generateRandomExpr(depth: Int, random: Random, ops: List<String>): String {
        if (depth <= 0) {
            return if (random.nextBoolean()) {
                // Return integer
                (random.nextInt(100) + 1).toString()
            } else {
                // Return decimal
                val base = random.nextInt(100) + 1
                val frac = random.nextInt(99) + 1
                "$base.$frac"
            }
        }

        val left = generateRandomExpr(depth - 1, random, ops)
        val right = generateRandomExpr(depth - 1, random, ops)
        val op = ops[random.nextInt(ops.size)]

        // Randomly wrap in parentheses
        return if (random.nextBoolean()) {
            "($left$op$right)"
        } else {
            "$left$op$right"
        }
    }
}
