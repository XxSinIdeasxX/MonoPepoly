package com.example.mono

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class BoardAdapter(
    private val onPropertyClick: (Property) -> Unit,
    // Rent needs the whole board (color groups, railroad count), not just the filtered list shown here
    private val rentOf: (Property) -> Int
) : ListAdapter<Property?, RecyclerView.ViewHolder>(PropertyDiffCallback()) {

    private var playerMap: Map<String, Player> = emptyMap()

    fun updatePlayers(players: List<Player>) {
        this.playerMap = players.associateBy { it.id }
        notifyDataSetChanged()
    }

    fun submitGroupedList(list: List<Property>?, columns: Int) {
        if (list == null) {
            submitList(null)
            return
        }
        val sorted = list.sortedWith(
            compareBy<Property> { getGroupOrder(it.group) }.thenBy { it.position }
        )
        // Each color group starts a new grid row: groups are padded with empty slots (null) to a multiple of the columns
        submitList(sorted.groupBy { it.group }.values.flatMap { group ->
            group + List((columns - group.size % columns) % columns) { null }
        })
    }

    private fun getGroupOrder(group: PropertyGroup): Int {
        return when (group) {
            PropertyGroup.BROWN -> 1
            PropertyGroup.LIGHT_BLUE -> 2
            PropertyGroup.MAGENTA -> 3
            PropertyGroup.ORANGE -> 4
            PropertyGroup.RED -> 5
            PropertyGroup.YELLOW -> 6
            PropertyGroup.GREEN -> 7
            PropertyGroup.DARK_BLUE -> 8
            PropertyGroup.RAILROAD -> 9  // All 4 railroads grouped sequentially
            PropertyGroup.UTILITY -> 10  // Both utilities grouped sequentially
        }
    }

    override fun getItemViewType(position: Int): Int = if (getItem(position) == null) VIEW_GAP else VIEW_CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_GAP) return object : RecyclerView.ViewHolder(View(parent.context)) {}
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_property_card, parent, false)
        return PropertyViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val property = getItem(position) ?: return
        (holder as PropertyViewHolder).bind(property, playerMap[property.ownerId], rentOf(property), onPropertyClick)
    }

    class PropertyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardProperty: MaterialCardView = itemView.findViewById(R.id.card_property)
        private val viewGroupHeader: View = itemView.findViewById(R.id.view_group_header)
        private val tvPropertyName: TextView = itemView.findViewById(R.id.tv_property_name)
        private val tvPropertyPrice: TextView = itemView.findViewById(R.id.tv_property_price)
        private val tvPropertyRent: TextView = itemView.findViewById(R.id.tv_property_rent)
        private val cardOwnerBadge: MaterialCardView = itemView.findViewById(R.id.card_owner_badge)
        private val tvOwnerToken: TextView = itemView.findViewById(R.id.tv_owner_token)
        private val tvOwnerName: TextView = itemView.findViewById(R.id.tv_owner_name)
        private val tvBuildingsStatus: TextView = itemView.findViewById(R.id.tv_buildings_status)
        private val tvMortgagedStatus: TextView = itemView.findViewById(R.id.tv_mortgaged_status)

        fun bind(property: Property, owner: Player?, currentRent: Int, onPropertyClick: (Property) -> Unit) {
            tvPropertyName.text = property.name
            tvPropertyPrice.text = "$${property.price}"

            // Header color
            try {
                viewGroupHeader.setBackgroundColor(Color.parseColor(property.customColorHex ?: property.group.colorHex))
            } catch (_: Exception) {
                viewGroupHeader.setBackgroundColor(Color.GRAY)
            }

            // Rent text
            val rentStr = if (property.isMortgaged) {
                "Hipotecada (Rent: $0)"
            } else if (property.spaceType == SpaceType.UTILITY) {
                "Alquiler: según dados"
            } else if (property.ownerId != null) {
                "Alquiler actual: $$currentRent"
            } else {
                "Alquiler base: $${property.baseRent}"
            }
            tvPropertyRent.text = rentStr

            // Owner badge
            if (owner != null) {
                tvOwnerToken.text = owner.tokenEmoji
                tvOwnerName.text = owner.name
                try {
                    val ownerColor = Color.parseColor(owner.colorHex)
                    cardOwnerBadge.setCardBackgroundColor(ownerColor)
                    tvOwnerName.setTextColor(textColorOn(ownerColor))
                } catch (_: Exception) {
                    cardOwnerBadge.setCardBackgroundColor(Color.LTGRAY)
                    tvOwnerName.setTextColor(Color.BLACK)
                }
            } else {
                tvOwnerToken.text = "🏛️"
                tvOwnerName.text = "Banco (Disponible)"
                cardOwnerBadge.setCardBackgroundColor(itemView.context.getColor(R.color.frost))
                tvOwnerName.setTextColor(itemView.context.getColor(R.color.text_primary))
            }

            // Buildings status
            if (property.hasHotel) {
                tvBuildingsStatus.isVisible = true
                tvBuildingsStatus.text = "🏨 Hotel"
            } else if (property.houses > 0) {
                tvBuildingsStatus.isVisible = true
                val housesStr = "🏡".repeat(property.houses) + " ${property.houses} casas"
                tvBuildingsStatus.text = housesStr
            } else {
                tvBuildingsStatus.isVisible = false
            }

            // Mortgage status
            tvMortgagedStatus.isVisible = property.isMortgaged

            cardProperty.setOnClickListener {
                onPropertyClick(property)
            }
        }
    }

    // AsyncListDiffer matches null gap items itself; this callback only sees cards
    private class PropertyDiffCallback : DiffUtil.ItemCallback<Property?>() {
        override fun areItemsTheSame(oldItem: Property, newItem: Property): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Property, newItem: Property): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_CARD = 0
        private const val VIEW_GAP = 1
    }
}
