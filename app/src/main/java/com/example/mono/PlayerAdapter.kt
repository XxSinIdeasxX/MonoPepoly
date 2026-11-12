package com.example.mono

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class PlayerAdapter(
    private val onClick: (Player) -> Unit
) : RecyclerView.Adapter<PlayerAdapter.PlayerViewHolder>() {

    private val players = mutableListOf<Player>()

    fun submitList(items: List<Player>) {
        players.clear()
        players.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player, parent, false)
        return PlayerViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(players[position])
    }

    override fun getItemCount(): Int = players.size

    class PlayerViewHolder(
        itemView: View,
        private val onClick: (Player) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.tv_player_icon)
        private val name: TextView = itemView.findViewById(R.id.tv_player_name)
        private val balance: TextView = itemView.findViewById(R.id.tv_player_balance)

        fun bind(player: Player) {
            name.text = player.name
            balance.text = if (player.bankrupt) "Bancarrota" else "$${player.balance}"
            itemView.alpha = if (player.bankrupt) 0.45f else 1f
            icon.text = player.tokenEmoji
            val color = runCatching { Color.parseColor(player.colorHex) }.getOrDefault(Color.GRAY)
            (itemView as MaterialCardView).apply {
                setCardBackgroundColor(color)
                strokeWidth = 0
            }
            name.setTextColor(textColorOn(color))
            balance.setTextColor(textColorOn(color))
            itemView.setOnClickListener { onClick(player) }
        }
    }
}
