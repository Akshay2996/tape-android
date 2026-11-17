package com.tape.measure.domain.shape

import com.tape.measure.domain.model.UnitSystem

/**
 * Formats [value] (SI units for its [quantity]) for display.
 *
 * Small areas and volumes read better in small units ("1362 cm²", not
 * "0.14 m²"), so CM and IN switch to the larger unit only from 1 m² / 1 m³
 * up. [reference] decides which side of that switch to use: pass the reading
 * a tolerance belongs to, so "2.40 m² ±0.05 m²" never becomes "±500 cm²".
 */
fun UnitSystem.format(quantity: Quantity, value: Float, reference: Float = value): String {
    val small = reference < 1f
    return when (quantity) {
        Quantity.LENGTH -> format(value)
        Quantity.ANGLE  -> "%.1f°".format(value)
        Quantity.AREA   -> when (this) {
            UnitSystem.CM -> if (small) "%.0f cm²".format(value * 1e4f) else "%.2f m²".format(value)
            UnitSystem.M  -> (if (small) "%.3f m²" else "%.2f m²").format(value)
            UnitSystem.IN -> if (small) "%.1f in²".format(value * 1550.0031f) else "%.2f ft²".format(value * 10.7639f)
            UnitSystem.FT -> "%.2f ft²".format(value * 10.7639f)
        }
        Quantity.VOLUME -> when (this) {
            UnitSystem.CM -> if (small) "%.0f cm³".format(value * 1e6f) else "%.2f m³".format(value)
            UnitSystem.M  -> (if (small) "%.3f m³" else "%.2f m³").format(value)
            UnitSystem.IN -> if (small) "%.0f in³".format(value * 61023.7f) else "%.2f ft³".format(value * 35.3147f)
            UnitSystem.FT -> "%.2f ft³".format(value * 35.3147f)
        }
    }
}

/** The reading's value, e.g. "84000 cm³". */
fun Reading.formatValue(unit: UnitSystem): String = unit.format(quantity, value)

/** The reading's error bound, e.g. "±2900 cm³", or null when there isn't one. */
fun Reading.formatTolerance(unit: UnitSystem): String? =
    tolerance?.let { "±" + unit.format(quantity, it, reference = value) }
