package com.jagabocil.app

import android.content.Context
import android.hardware.ConsumerIrManager

object IrCodes {
    fun pattern(brand: String): IntArray {
        val code = when (brand) { "samsung" -> 0xE0E040BFL; "lg" -> 0x20DF10EFL; else -> error("Profil TV belum dipilih.") }
        val samsung = brand == "samsung"
        val pulses = mutableListOf(if (samsung) 4500 else 9000, 4500)
        for (bit in 31 downTo 0) { pulses += 560; pulses += if ((code shr bit) and 1L == 1L) 1690 else 560 }
        pulses += 560
        return pulses.toIntArray()
    }
}
class Infrared(context: Context) {
    private val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    val available get() = manager?.hasIrEmitter() == true
    fun check(brand: String) {
        check(available) { "HP ini tidak punya IR blaster. Gunakan HP dengan infrared untuk mengontrol TV." }
        IrCodes.pattern(brand)
        check(manager?.carrierFrequencies?.any { 38_000 in it.minFrequency..it.maxFrequency } == true) { "Infrared HP ini tidak mendukung frekuensi TV 38 kHz." }
    }
    fun power(brand: String) { check(brand); manager!!.transmit(38_000, IrCodes.pattern(brand)) }
}
