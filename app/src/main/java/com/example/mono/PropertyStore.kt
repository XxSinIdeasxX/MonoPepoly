package com.example.mono

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PropertyStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mono_properties", Context.MODE_PRIVATE)

    fun loadProperties(): MutableList<Property> {
        val defaultProperties = getDefaultProperties(context)
        val raw = prefs.getString(KEY_PROPERTIES, null) ?: return defaultProperties

        return try {
            val array = JSONArray(raw)
            val storedMap = mutableMapOf<String, PropertyState>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                storedMap[id] = PropertyState(
                    ownerId = if (obj.isNull("ownerId")) null else obj.getString("ownerId"),
                    houses = obj.optInt("houses", 0),
                    hasHotel = obj.optBoolean("hasHotel", false),
                    isMortgaged = obj.optBoolean("isMortgaged", false)
                )
            }

            defaultProperties.map { prop ->
                val state = storedMap[prop.id]
                if (state != null) {
                    prop.copy(
                        ownerId = state.ownerId,
                        houses = state.houses,
                        hasHotel = state.hasHotel,
                        isMortgaged = state.isMortgaged
                    )
                } else {
                    prop
                }
            }.toMutableList()
        } catch (_: Exception) {
            defaultProperties
        }
    }

    fun saveProperties(properties: List<Property>) {
        val array = JSONArray()
        properties.forEach { prop ->
            val obj = JSONObject()
                .put("id", prop.id)
                .put("ownerId", prop.ownerId ?: JSONObject.NULL)
                .put("houses", prop.houses)
                .put("hasHotel", prop.hasHotel)
                .put("isMortgaged", prop.isMortgaged)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PROPERTIES, array.toString()).apply()
    }

    fun resetProperties() {
        prefs.edit().remove(KEY_PROPERTIES).apply()
    }

    private data class PropertyState(
        val ownerId: String?,
        val houses: Int,
        val hasHotel: Boolean,
        val isMortgaged: Boolean
    )

    companion object {
        private const val KEY_PROPERTIES = "properties_json"

        fun getDefaultProperties(context: Context): MutableList<Property> {
            val parsed = BoardMarkdownParser.parseFromAssets(context)
            if (parsed.isNotEmpty()) {
                return parsed.toMutableList()
            }
            return fallbackProperties()
        }

        private fun fallbackProperties(): MutableList<Property> {
            return mutableListOf(
                Property("brown_1", "Av. Mediterráneo", PropertyGroup.BROWN, 60, 2, listOf(10, 30, 90, 160, 250), 50, 30, position = 1),
                Property("brown_2", "Av. Báltica", PropertyGroup.BROWN, 60, 4, listOf(20, 60, 180, 320, 450), 50, 30, position = 3),
                Property("lblue_1", "Av. Oriental", PropertyGroup.LIGHT_BLUE, 100, 6, listOf(30, 90, 270, 400, 550), 50, 50, position = 6),
                Property("lblue_2", "Av. Vermont", PropertyGroup.LIGHT_BLUE, 100, 6, listOf(30, 90, 270, 400, 550), 50, 50, position = 8),
                Property("lblue_3", "Av. Connecticut", PropertyGroup.LIGHT_BLUE, 120, 8, listOf(40, 100, 300, 450, 600), 50, 60, position = 9),
                Property("magenta_1", "Plaza San Carlos", PropertyGroup.MAGENTA, 140, 10, listOf(50, 150, 450, 625, 750), 100, 70, position = 11),
                Property("magenta_2", "Av. Estados Unidos", PropertyGroup.MAGENTA, 140, 10, listOf(50, 150, 450, 625, 750), 100, 70, position = 13),
                Property("magenta_3", "Av. Virginia", PropertyGroup.MAGENTA, 160, 12, listOf(60, 180, 500, 700, 900), 100, 80, position = 14),
                Property("orange_1", "Plaza St. James", PropertyGroup.ORANGE, 180, 14, listOf(70, 200, 550, 750, 950), 100, 90, position = 16),
                Property("orange_2", "Av. Tennessee", PropertyGroup.ORANGE, 180, 14, listOf(70, 200, 550, 750, 950), 100, 90, position = 18),
                Property("orange_3", "Av. Nueva York", PropertyGroup.ORANGE, 200, 16, listOf(80, 220, 600, 800, 1000), 100, 100, position = 19),
                Property("red_1", "Av. Kentucky", PropertyGroup.RED, 220, 18, listOf(90, 250, 700, 875, 1050), 150, 110, position = 21),
                Property("red_2", "Av. Indiana", PropertyGroup.RED, 220, 18, listOf(90, 250, 700, 875, 1050), 150, 110, position = 23),
                Property("red_3", "Av. Illinois", PropertyGroup.RED, 240, 20, listOf(100, 300, 750, 925, 1100), 150, 120, position = 24),
                Property("yellow_1", "Av. Atlántico", PropertyGroup.YELLOW, 260, 22, listOf(110, 330, 800, 975, 1150), 150, 130, position = 26),
                Property("yellow_2", "Av. Ventnor", PropertyGroup.YELLOW, 260, 22, listOf(110, 330, 800, 975, 1150), 150, 130, position = 27),
                Property("yellow_3", "Jardines Marvin", PropertyGroup.YELLOW, 280, 24, listOf(120, 360, 850, 1025, 1200), 150, 140, position = 29),
                Property("green_1", "Av. Pacífico", PropertyGroup.GREEN, 300, 26, listOf(130, 390, 900, 1100, 1275), 200, 150, position = 31),
                Property("green_2", "Av. Carolina Norte", PropertyGroup.GREEN, 300, 26, listOf(130, 390, 900, 1100, 1275), 200, 150, position = 32),
                Property("green_3", "Av. Pensilvania", PropertyGroup.GREEN, 320, 28, listOf(150, 450, 1000, 1200, 1400), 200, 160, position = 34),
                Property("dblue_1", "Paseo Tablado", PropertyGroup.DARK_BLUE, 350, 35, listOf(175, 500, 1100, 1300, 1500), 200, 175, position = 37),
                Property("dblue_2", "Plaza Parque", PropertyGroup.DARK_BLUE, 400, 50, listOf(200, 600, 1400, 1700, 2000), 200, 200, position = 39),
                Property("rr_1", "Ferrocarril Lectura", PropertyGroup.RAILROAD, 200, 25, listOf(50, 100, 200), 0, 100, position = 5),
                Property("rr_2", "Ferrocarril Pensilvania", PropertyGroup.RAILROAD, 200, 25, listOf(50, 100, 200), 0, 100, position = 15),
                Property("rr_3", "Ferrocarril B. & O.", PropertyGroup.RAILROAD, 200, 25, listOf(50, 100, 200), 0, 100, position = 25),
                Property("rr_4", "Ferrocarril Vía Rápida", PropertyGroup.RAILROAD, 200, 25, listOf(50, 100, 200), 0, 100, position = 35),
                Property("util_1", "Compañía de Luz", PropertyGroup.UTILITY, 150, 40, listOf(), 0, 75, position = 12),
                Property("util_2", "Compañía de Agua", PropertyGroup.UTILITY, 150, 40, listOf(), 0, 75, position = 28)
            )
        }
    }
}
