package com.example.mono

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testMarkdownParsing() {
        val sampleMarkdown = """
            # Monopoly Board Test
            | Position | ID | Name | Group | ColorHex | Price | BaseRent | Rent1 | Rent2 | Rent3 | Rent4 | RentHotel | HouseCost | Mortgage | Type |
            |---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
            | 0 | go | Salida (GO) | NONE | #2E7D32 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | GO |
            | 1 | brown_1 | Av. Mediterráneo | BROWN | #8D6E63 | 60 | 2 | 10 | 30 | 90 | 160 | 250 | 50 | 30 | PROPERTY |
            | 5 | rr_1 | Ferrocarril Lectura | RAILROAD | #37474F | 200 | 25 | 50 | 100 | 200 | 0 | 0 | 0 | 100 | RAILROAD |
        """.trimIndent()

        val parsed = BoardMarkdownParser.parseMarkdown(sampleMarkdown)
        assertEquals(3, parsed.size)

        val prop1 = parsed[1]
        assertEquals("brown_1", prop1.id)
        assertEquals("Av. Mediterráneo", prop1.name)
        assertEquals(60, prop1.price)
        assertEquals(2, prop1.baseRent)
        assertEquals(PropertyGroup.BROWN, prop1.group)
        assertEquals(SpaceType.PROPERTY, prop1.spaceType)
    }
}
