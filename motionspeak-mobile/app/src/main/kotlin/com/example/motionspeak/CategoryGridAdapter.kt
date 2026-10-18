package com.example.motionspeak

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Word Bank category cards (spec §4–5).
 *
 * Color scheme:
 *   - Favorites: always the brand teal (ms_brand / ms_cat_teal_*).
 *   - All Words: sky-blue slot.
 *   - Regular categories: assigned a hue from the blue→teal→violet spectrum
 *     by cycling through the SPECTRUM list in order, so adjacent cards always
 *     differ.  The assignment is deterministic and position-stable.
 */
class CategoryGridAdapter(
    private val items: MutableList<CategoryGridItem>,
    private val onClick: (CategoryGridItem) -> Unit,
) : RecyclerView.Adapter<CategoryGridAdapter.CardViewHolder>() {

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardCategory)
        val icon: ImageView = view.findViewById(R.id.ivCategoryIcon)
        val name: TextView = view.findViewById(R.id.tvCategoryName)
        val count: TextView = view.findViewById(R.id.tvCategoryCount)
    }

    /**
     * One entry in the colour spectrum. Each slot carries three resolved
     * colour ints so bind() never calls ContextCompat.getColor() at draw time.
     */
    private data class Slot(val bg: Int, val text: Int, val textSecondary: Int)

    /** Resolved once per adapter, not per bind. */
    private var spectrum: List<Slot>? = null

    private fun spectrum(context: Context): List<Slot> = spectrum ?: run {
        fun c(id: Int) = ContextCompat.getColor(context, id)
        listOf(
            // blue
            Slot(c(R.color.ms_cat_blue_bg),   c(R.color.ms_cat_blue_text),   c(R.color.ms_cat_blue_text_secondary)),
            // sky
            Slot(c(R.color.ms_cat_sky_bg),    c(R.color.ms_cat_sky_text),    c(R.color.ms_cat_sky_text_secondary)),
            // teal (brand)
            Slot(c(R.color.ms_cat_teal_bg),   c(R.color.ms_cat_teal_text),   c(R.color.ms_cat_teal_text_secondary)),
            // cyan-teal
            Slot(c(R.color.ms_cat_cyan_bg),   c(R.color.ms_cat_cyan_text),   c(R.color.ms_cat_cyan_text_secondary)),
            // indigo
            Slot(c(R.color.ms_cat_indigo_bg), c(R.color.ms_cat_indigo_text), c(R.color.ms_cat_indigo_text_secondary)),
            // violet
            Slot(c(R.color.ms_cat_violet_bg), c(R.color.ms_cat_violet_text), c(R.color.ms_cat_violet_text_secondary)),
            // purple-violet
            Slot(c(R.color.ms_cat_purple_bg), c(R.color.ms_cat_purple_text), c(R.color.ms_cat_purple_text_secondary)),
        ).also { spectrum = it }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder =
        CardViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_category_card, parent, false))

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val item = items[position]
        val spec = spectrum(holder.itemView.context)

        // Favorites/All Words are literal labels; server categories are title-cased
        // so a long all-caps name doesn't fill the card edge to edge.
        val displayText = if (item.isFavorites || item.isAllWords) item.displayName else item.displayName.toTitleCase()
        // A single word must never wrap (Android would hyphenate it mid-word).
        holder.name.maxLines = if (displayText.contains(' ')) 2 else 1
        holder.name.text = displayText
        holder.count.text = "${item.wordCount} word${if (item.wordCount != 1) "s" else ""}"

        // Pick colour slot:
        //   Favorites → teal (index 2 in the spectrum, same hue as the brand)
        //   All Words  → sky (index 1)
        //   Regular    → cycle through the full spectrum by position, skipping 0
        //                so regular cards start at blue then wrap around
        val slot: Slot = when {
            item.isFavorites -> spec[2]  // teal — Favorites retains the brand colour
            item.isAllWords  -> spec[1]  // sky blue
            else -> {
                // Regular categories start at slot 0 (blue), cycling through all slots.
                // We subtract the 2 fixed header cards (Favorites + All Words) when
                // they are present, but the simplest stable mapping is just position mod size.
                spec[position % spec.size]
            }
        }

        holder.card.setCardBackgroundColor(slot.bg)
        holder.name.setTextColor(slot.text)
        holder.count.setTextColor(slot.textSecondary)
        holder.icon.setImageResource(
            when {
                item.isFavorites -> R.drawable.ic_star_fill
                item.isAllWords  -> R.drawable.ic_book_line
                else             -> R.drawable.ic_tag_line
            }
        )
        holder.icon.imageTintList = ColorStateList.valueOf(slot.text)

        holder.card.contentDescription = "$displayText, ${holder.count.text}"
        holder.card.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<CategoryGridItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = items[oldPos]
                val b = newItems[newPos]
                return a.isFavorites == b.isFavorites &&
                    a.isAllWords == b.isAllWords &&
                    a.displayName.equals(b.displayName, ignoreCase = true)
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }
}
