package com.example.smartcalculator

import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Shared math and calculation utility engine.
 */
object CalculatorEngine {

    /**
     * Evaluates a mathematical expression string using exp4j.
     * Returns Double.NaN if the expression is invalid or encounters errors (e.g., division by zero).
     */
    fun eval(expr: String): Double = try {
        ExpressionBuilder(expr).build().evaluate()
    } catch (_: Exception) {
        Double.NaN
    }

    /**
     * Converts display math operators to standard evaluation operators.
     */
    fun toCalcOp(op: String): String = when (op) {
        "×" -> "*"
        "÷" -> "/"
        "−" -> "-"
        else -> op
    }

    /**
     * Formats a raw Double result into a user-friendly display string.
     * If the value is NaN or Infinite, returns "Error".
     */
    fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val bd = BigDecimal(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 12) {
            BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        } else {
            plain
        }
    }

    /**
     * Adds digit separators (commas) to the integer part of the number string.
     */
    fun formatNumber(input: String): String {
        if (input.isEmpty()) return "0"
        if (input == "-" || input.endsWith(".")) return input
        return try {
            val parts = input.split(".")
            val intPart = parts[0].toLongOrNull()?.let { "%,d".format(it) } ?: parts[0]
            if (parts.size > 1) "$intPart.${parts[1]}" else intPart
        } catch (_: Exception) {
            input
        }
    }
}
