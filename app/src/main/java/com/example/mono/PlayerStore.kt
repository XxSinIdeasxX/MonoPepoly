package com.example.mono

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlayerStore(context: Context) {
    private val prefs = context.getSharedPreferences("mono_players", Context.MODE_PRIVATE)

    fun loadPlayers(): MutableList<Player> {
        val raw = prefs.getString(KEY_PLAYERS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val players = mutableListOf<Player>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val defaultColor = PLAYER_COLORS[i % PLAYER_COLORS.size]
                val defaultToken = PLAYER_TOKENS[i % PLAYER_TOKENS.size]
                players.add(
                    Player(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        balance = obj.getInt("balance"),
                        tagId = obj.getString("tagId"),
                        colorHex = obj.optString("colorHex", defaultColor),
                        tokenEmoji = obj.optString("tokenEmoji", defaultToken),
                        boardPosition = obj.optInt("boardPosition", 0),
                        bankrupt = obj.optBoolean("bankrupt", false)
                    )
                )
            }
            players
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun savePlayers(players: List<Player>) {
        val array = JSONArray()
        players.forEach { player ->
            val obj = JSONObject()
                .put("id", player.id)
                .put("name", player.name)
                .put("balance", player.balance)
                .put("tagId", player.tagId)
                .put("colorHex", player.colorHex)
                .put("tokenEmoji", player.tokenEmoji)
                .put("boardPosition", player.boardPosition)
                .put("bankrupt", player.bankrupt)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PLAYERS, array.toString()).apply()
    }

    companion object {
        private const val KEY_PLAYERS = "players_json"

        // Suggested player colors: HUES tones plus the greens of the app icon, which the UI no longer uses.
        // Any other color can be picked with the custom picker.
        val PLAYER_PALETTE = listOf(
            "Rojo" to "#dd0f0f",
            "Azul" to "#3a86ff",
            "Índigo" to "#4f46e5",
            "Violeta" to "#8338ec",
            "Rosa" to "#d6336c",
            "Cian" to "#06b6d4",
            "Verde" to "#2d995e",
            "Lima" to "#87cd51",
            "Naranja" to "#e8590c",
            "Ámbar" to "#f2a900",
            "Turquesa" to "#14b8a6",
            "Magenta" to "#c026d3",
            "Marrón" to "#8a5a3c",
            "Pizarra" to "#64748b",
            "Vino" to "#8c1c13",
            "Marino" to "#1e3a8a"
        )

        val PLAYER_COLORS = PLAYER_PALETTE.map { it.second }

        val PLAYER_TOKENS = listOf(
            "🎩", "🐕", "🚗", "🚢", "🐱", "🦖", "👞", "🦆"
        )
    }
}
