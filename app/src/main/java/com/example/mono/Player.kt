package com.example.mono

import android.graphics.Color
import androidx.core.graphics.ColorUtils

data class Player(
    val id: String,
    val name: String,
    val balance: Int,
    val tagId: String,
    val colorHex: String = "#3F51B5",
    val tokenEmoji: String = "🎩",
    val boardPosition: Int = 0,
    val bankrupt: Boolean = false
)

// Ink on light player colors (e.g. Ámbar), white on the rest
fun textColorOn(color: Int): Int =
    if (ColorUtils.calculateLuminance(color) > 0.4) Color.parseColor("#1E1C1A") else Color.WHITE
