package com.example.mono

import android.graphics.Color

// Color palette ported from the Kaliman projects (color-palette.ts / color.ts): twelve hues, each with a light, medium
// and dark tone, plus neutrals. The UI greens come from the app icon.
object ColorPalette {

    val HUES = listOf(
        listOf("#ff6b6b", "#dd0f0f", "#8c1c13"), // rojos
        listOf("#ff9f43", "#e8590c", "#a8400a"), // naranjas
        listOf("#ffd166", "#f2a900", "#a86f00"), // ámbares
        listOf("#d9e86a", "#9cc21a", "#5f7d0e"), // limas
        listOf("#8ac926", "#4f8a5b", "#2f5d3a"), // verdes
        listOf("#75d1a9", "#2a9d8f", "#1b6b63"), // turquesas
        listOf("#67e8f9", "#06b6d4", "#0e7490"), // cianes
        listOf("#4cc9f0", "#3a86ff", "#1d4ed8"), // azules
        listOf("#818cf8", "#4f46e5", "#3730a3"), // índigos
        listOf("#b197fc", "#8338ec", "#5a1fa8"), // violetas
        listOf("#f783ac", "#d6336c", "#9c1f4c"), // rosas
        listOf("#c08a5a", "#8d5a3b", "#5a3a24") // cafés
    )

    val NEUTRALS = listOf("#ffffff", "#e6e7ea", "#858585", "#2c2c2c", "#000000")

    // The greens of the app icon, free for players now that the interface uses yellow/orange
    val UI_GREENS = listOf("#d5f46a", "#87cd51", "#5ab85f", "#2d995e", "#189162", "#1f7443", "#0b6341")

    const val TONES_PER_HUE = 5

    // 65 colors: each hue goes from pastel to very dark (pastel and very dark are computed), neutrals at the end
    val BASE_PALETTE: List<String> =
        HUES.flatMap { (light, mid, dark) -> listOf(mix(light, "#ffffff", 0.4f), light, mid, dark, mix(dark, "#000000", 0.5f)) } + NEUTRALS

    private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    // #rrggbb in lowercase, or null when it isn't a valid color
    fun normalize(value: String?): String? {
        val clean = value?.trim() ?: return null
        if (!HEX.matches(clean)) return null
        val lower = clean.lowercase()
        return if (lower.length == 4) "#" + lower.drop(1).map { "$it$it" }.joinToString("") else lower
    }

    // Mixes `color` with `base`: amount 0 = only base, 1 = only color
    fun mix(color: String, base: String, amount: Float): String {
        val c = Color.parseColor(color)
        val b = Color.parseColor(base)
        fun ch(shift: Int): Int {
            val cv = (c shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (bv + (cv - bv) * amount).toInt().coerceIn(0, 255)
        }
        return toHex(Color.rgb(ch(16), ch(8), ch(0)))
    }

    fun toHex(color: Int): String = String.format("#%06x", color and 0xFFFFFF)

    // Thirteen tones from lightest to darkest; the middle one is the color itself
    fun tonesOf(color: String): List<String> {
        val steps = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f)
        val lighter = steps.take(5).reversed().map { mix(color, "#fafafa", 1 - it) } // the 6th is almost white
        val darker = steps.map { mix(color, "#0d0d0d", 1 - it) }
        return lighter + color + darker
    }
}
