package com.example.mono

enum class PropertyGroup(val displayName: String, val colorHex: String, val isDarkText: Boolean = false) {
    BROWN("Marrón", "#8D6E63"),
    LIGHT_BLUE("Azul Claro", "#4FC3F7", true),
    MAGENTA("Rosa / Violeta", "#E91E63"),
    ORANGE("Naranja", "#FF9800", true),
    RED("Rojo", "#F44336"),
    YELLOW("Amarillo", "#FDD835", true),
    GREEN("Verde", "#4CAF50"),
    DARK_BLUE("Azul Oscuro", "#1A237E"),
    RAILROAD("Ferrocarril", "#37474F"),
    UTILITY("Servicios", "#78909C")
}
