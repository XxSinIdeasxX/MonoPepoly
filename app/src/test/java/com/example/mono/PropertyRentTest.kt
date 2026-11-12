package com.example.mono

import org.junit.Assert.assertEquals
import org.junit.Test

class PropertyRentTest {

    private fun lot(id: String, owner: String?, houses: Int = 0, hotel: Boolean = false, mortgaged: Boolean = false) =
        Property(id, id, PropertyGroup.BROWN, 60, 2, listOf(10, 30, 90, 160, 250), 50, 30,
            ownerId = owner, houses = houses, hasHotel = hotel, isMortgaged = mortgaged)

    private fun railroad(id: String, owner: String?) =
        Property(id, id, PropertyGroup.RAILROAD, 200, 25, listOf(50, 100, 200), 0, 100,
            ownerId = owner, spaceType = SpaceType.RAILROAD)

    @Test
    fun colorLot_baseRentWithoutFullGroup() {
        val board = listOf(lot("a", "p1"), lot("b", "p2"))
        assertEquals(2, board[0].getCurrentRent(board))
    }

    @Test
    fun colorLot_doubleRentWithFullGroup() {
        val board = listOf(lot("a", "p1"), lot("b", "p1"))
        assertEquals(4, board[0].getCurrentRent(board))
    }

    @Test
    fun colorLot_housesAndHotelOverrideGroupBonus() {
        val board = listOf(lot("a", "p1", houses = 3), lot("b", "p1", hotel = true))
        assertEquals(90, board[0].getCurrentRent(board))
        assertEquals(250, board[1].getCurrentRent(board))
    }

    @Test
    fun railroad_rentDependsOnHowManyTheOwnerHas() {
        val owners = listOf("p1", "p2", "p2", "p2")
        val board = owners.mapIndexed { i, owner -> railroad("rr_$i", owner) }
        assertEquals(25, board[0].getCurrentRent(board))
        assertEquals(100, board[1].getCurrentRent(board))

        val allMine = (0..3).map { railroad("rr_$it", "p1") }
        assertEquals(200, allMine[0].getCurrentRent(allMine))
        val twoMine = listOf(railroad("rr_0", "p1"), railroad("rr_1", "p1"), railroad("rr_2", null))
        assertEquals(50, twoMine[0].getCurrentRent(twoMine))
    }

    @Test
    fun noRentWhenMortgagedOrUnowned() {
        val board = listOf(lot("a", "p1", mortgaged = true), lot("b", null))
        assertEquals(0, board[0].getCurrentRent(board))
        assertEquals(0, board[1].getCurrentRent(board))
    }
}
