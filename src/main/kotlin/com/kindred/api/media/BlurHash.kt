package com.kindred.api.media

import com.sksamuel.scrimage.ImmutableImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.withSign

/**
 * BlurHash encoder (https://blurha.sh — public-domain algorithm). Small enough to
 * implement directly rather than pull a dependency. Call with an already-downscaled
 * image (~32px) — cost is O(width * height * cx * cy).
 */
object BlurHash {

    private const val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    fun encode(image: ImmutableImage, componentsX: Int = 4, componentsY: Int = 3): String {
        require(componentsX in 1..9 && componentsY in 1..9) { "components must be 1..9" }
        val width = image.width
        val height = image.height
        val linear = Array(width * height) { i ->
            val pixel = image.pixel(i % width, i / width)
            doubleArrayOf(srgbToLinear(pixel.red()), srgbToLinear(pixel.green()), srgbToLinear(pixel.blue()))
        }

        val factors = Array(componentsX * componentsY) { DoubleArray(3) }
        for (j in 0 until componentsY) {
            for (i in 0 until componentsX) {
                val normalisation = if (i == 0 && j == 0) 1.0 else 2.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (y in 0 until height) {
                    val basisY = cos(PI * j * y / height)
                    for (x in 0 until width) {
                        val basis = normalisation * cos(PI * i * x / width) * basisY
                        val px = linear[y * width + x]
                        r += basis * px[0]
                        g += basis * px[1]
                        b += basis * px[2]
                    }
                }
                val scale = 1.0 / (width * height)
                factors[j * componentsX + i] = doubleArrayOf(r * scale, g * scale, b * scale)
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)

        val sb = StringBuilder()
        sb.append(encode83(componentsX - 1 + (componentsY - 1) * 9, 1))

        val maxValue: Double
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { it.maxOf(::abs) }
            val quantisedMax = floor((actualMax * 166 - 0.5).coerceIn(0.0, 82.0)).toInt()
            maxValue = (quantisedMax + 1) / 166.0
            sb.append(encode83(quantisedMax, 1))
        } else {
            maxValue = 1.0
            sb.append(encode83(0, 1))
        }

        sb.append(encode83(encodeDc(dc), 4))
        for (factor in ac) {
            sb.append(encode83(encodeAc(factor, maxValue), 2))
        }
        return sb.toString()
    }

    private fun encodeDc(value: DoubleArray): Int =
        (linearToSrgb(value[0]) shl 16) + (linearToSrgb(value[1]) shl 8) + linearToSrgb(value[2])

    private fun encodeAc(value: DoubleArray, maxValue: Double): Int {
        fun quantise(v: Double): Int =
            floor((signPow(v / maxValue, 0.5) * 9 + 9.5).coerceIn(0.0, 18.0)).toInt()
        return quantise(value[0]) * 19 * 19 + quantise(value[1]) * 19 + quantise(value[2])
    }

    private fun signPow(value: Double, exp: Double): Double = abs(value).pow(exp).withSign(value)

    private fun srgbToLinear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (srgb * 255 + 0.5).toInt()
    }

    private fun encode83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / power83(length - i)) % 83
            sb.append(ALPHABET[digit])
        }
        return sb.toString()
    }

    private fun power83(exp: Int): Int {
        var result = 1
        repeat(exp) { result *= 83 }
        return result
    }
}
