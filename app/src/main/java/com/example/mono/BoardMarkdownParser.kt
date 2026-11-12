package com.example.mono

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object BoardMarkdownParser {

    fun parseFromAssets(context: Context, filename: String = "monopoly_board.md"): List<Property> {
        return try {
            val inputStream = context.assets.open(filename)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            parseMarkdown(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseMarkdown(content: String): List<Property> {
        val properties = mutableListOf<Property>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("|") || trimmed.contains("---|") || trimmed.startsWith("| Position")) {
                continue
            }

            val columns = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (columns.size >= 15) {
                try {
                    val position = columns[0].toInt()
                    val id = columns[1]
                    val name = columns[2]
                    val groupStr = columns[3]
                    val colorHex = columns[4]
                    val price = columns[5].toInt()
                    val baseRent = columns[6].toInt()
                    val rent1 = columns[7].toInt()
                    val rent2 = columns[8].toInt()
                    val rent3 = columns[9].toInt()
                    val rent4 = columns[10].toInt()
                    val rentHotel = columns[11].toInt()
                    val houseCost = columns[12].toInt()
                    val mortgage = columns[13].toInt()
                    val typeStr = columns[14]

                    val group = try {
                        PropertyGroup.valueOf(groupStr)
                    } catch (_: Exception) {
                        PropertyGroup.UTILITY
                    }

                    val spaceType = try {
                        SpaceType.valueOf(typeStr)
                    } catch (_: Exception) {
                        SpaceType.PROPERTY
                    }

                    val houseRent = listOf(rent1, rent2, rent3, rent4, rentHotel)

                    properties.add(
                        Property(
                            id = id,
                            name = name,
                            group = group,
                            price = price,
                            baseRent = baseRent,
                            houseRent = houseRent,
                            houseCost = houseCost,
                            mortgageValue = mortgage,
                            position = position,
                            spaceType = spaceType,
                            customColorHex = colorHex
                        )
                    )
                } catch (_: Exception) {
                    // Skip invalid line
                }
            }
        }
        return properties
    }
}
