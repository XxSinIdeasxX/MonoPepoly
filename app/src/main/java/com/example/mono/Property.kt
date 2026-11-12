package com.example.mono

data class Property(
    val id: String,
    val name: String,
    val group: PropertyGroup,
    val price: Int,
    val baseRent: Int,
    val houseRent: List<Int>, // 1 house, 2 houses, 3 houses, 4 houses, 1 hotel
    val houseCost: Int,
    val mortgageValue: Int,
    val ownerId: String? = null,
    val houses: Int = 0,
    val hasHotel: Boolean = false,
    val isMortgaged: Boolean = false,
    val position: Int = 0,
    val spaceType: SpaceType = SpaceType.PROPERTY,
    val customColorHex: String? = null
) {
    // Rent the owner charges right now; `all` is the whole board, needed for color-group and railroad-count rules.
    // Utilities return baseRent here, but their real rent depends on the dice and is typed in by the banker.
    fun getCurrentRent(all: List<Property>): Int {
        if (isMortgaged || ownerId == null || !isPurchasable) return 0
        return when {
            hasHotel -> houseRent.getOrElse(4) { baseRent }
            houses in 1..4 -> houseRent.getOrElse(houses - 1) { baseRent }
            // 1 railroad: base; 2-4 railroads: houseRent[0..2] ($50 / $100 / $200)
            spaceType == SpaceType.RAILROAD -> ownerCount(all).let { if (it <= 1) baseRent else houseRent.getOrElse(it - 2) { baseRent } }
            // Classic rule: owning the whole color group doubles the rent on unbuilt lots
            ownerHasFullGroup(all) -> baseRent * 2
            else -> baseRent
        }
    }

    // How many spaces of this type (railroads, utilities...) the owner has
    fun ownerCount(all: List<Property>): Int =
        if (ownerId == null) 0 else all.count { it.spaceType == spaceType && it.ownerId == ownerId }

    fun ownerHasFullGroup(all: List<Property>): Boolean =
        ownerId != null && spaceType == SpaceType.PROPERTY &&
            all.filter { it.spaceType == SpaceType.PROPERTY && it.group == group }.all { it.ownerId == ownerId }

    val isPurchasable: Boolean
        get() = spaceType == SpaceType.PROPERTY || spaceType == SpaceType.RAILROAD || spaceType == SpaceType.UTILITY

    val isOwned: Boolean get() = ownerId != null
}
