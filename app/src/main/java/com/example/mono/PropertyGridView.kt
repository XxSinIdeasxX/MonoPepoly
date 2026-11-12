package com.example.mono

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources

class PropertyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val propertiesList = mutableListOf<Property>()
    private var playersMap = mapOf<String, Player>()
    private var filter: (Property) -> Boolean = { true }
    private var onPropertyClickListener: ((Property) -> Unit)? = null

    // Block Rects & Map
    private val blockRects = mutableMapOf<String, RectF>()

    // Paints
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val blockBgPaint =Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface)
        style = Paint.Style.FILL
    }

    private val colorBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val houseDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_house)
    private val hotelDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_hotel)

    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val ownerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(properties: List<Property>, players: List<Player>) {
        propertiesList.clear()
        // Only purchasable spaces; non-purchasable ones stay as empty slots
        propertiesList.addAll(properties.filter { it.isPurchasable })
        playersMap = players.associateBy { it.id }
        requestLayout()
        invalidate()
    }

    // Blocks that don't match are drawn dimmed so the matching ones stand out
    fun setFilter(matches: (Property) -> Boolean) {
        filter = matches
        invalidate()
    }

    fun setOnPropertyClickListener(listener: (Property) -> Unit) {
        this.onPropertyClickListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Float.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightMeasureSpec).toFloat()
        }
        setMeasuredDimension(width, layoutBlocks(width.toFloat(), maxHeight).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // No background: blocks sit directly on the screen's board gradient
        layoutBlocks(width.toFloat(), height.toFloat())
        for (prop in propertiesList) {
            val rect = blockRects[prop.id] ?: continue
            val dim = !filter(prop)
            val save = if (dim) canvas.saveLayerAlpha(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, 70) else -1
            drawMiniPropertyBlock(canvas, prop, rect)
            if (dim) canvas.restoreToCount(save)
        }
    }

    // One row per board side with its groups packed left to right (small gap between groups), then a railroad
    // column right after the widest row and a utility column after it; returns the total height needed.
    // Blocks keep a 4:5 shape, shrinking so everything fits in w x maxH, and the whole grid is centered horizontally.
    private fun layoutBlocks(w: Float, maxH: Float): Float {
        blockRects.clear()

        val byGroup = propertiesList.sortedBy { it.position }.groupBy { it.group }
        val railroads = byGroup[PropertyGroup.RAILROAD].orEmpty()
        val utilities = byGroup[PropertyGroup.UTILITY].orEmpty()
        val rows = ROW_GROUPS.map { groups -> groups.map { byGroup[it].orEmpty() }.filter { it.isNotEmpty() } }
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            return layoutPortrait(w, maxH, rows, railroads, utilities)
        }

        val padding = 10f
        val hGap = 10f // between cards of a row
        val vGap = 6f // between rows
        val groupGap = hGap * 3
        // The first group is padded to the widest first group (3 slots) so the divider between groups lines up in every row
        val firstSlots = rows.maxOf { it.first().size }
        fun pad(groups: List<List<Property>>) = if (groups.size > 1) firstSlots - groups.first().size else 0
        fun slots(groups: List<List<Property>>) = groups.sumOf { it.size } + pad(groups)
        // Gaps inside groups plus gaps between groups, i.e. the row width that doesn't scale with the blocks
        fun fixedWidth(groups: List<List<Property>>) =
            (slots(groups) - groups.size) * hGap + (groups.size - 1).coerceAtLeast(0) * groupGap

        // Each row plus the railroad and utility columns (+1 block and +groupGap each) must fit in the width
        val blockWidth = minOf(
            rows.minOf { (w - padding * 2 - fixedWidth(it) - groupGap * 2) / (slots(it) + 2) },
            (maxH - padding * 2 - vGap * (rows.size - 1)) / rows.size / 1.25f
        )
        val blockHeight = blockWidth * 1.25f
        // Utilities column right after the widest row, railroads after it
        val utilityX = rows.maxOf { slots(it) * blockWidth + fixedWidth(it) } + groupGap
        val railroadX = utilityX + blockWidth + groupGap
        val startX = (w - (railroadX + blockWidth)) / 2f

        // Each utility sits in the row of its own board side (Electric on the 2nd row, Water on the 3rd)
        utilities.forEach {
            val y = padding + ((it.position - 1) / 10).coerceIn(0, rows.lastIndex) * (blockHeight + vGap)
            blockRects[it.id] = RectF(startX + utilityX, y, startX + utilityX + blockWidth, y + blockHeight)
        }

        rows.forEachIndexed { row, groups ->
            val y = padding + row * (blockHeight + vGap)
            var x = startX
            groups.forEachIndexed { index, group ->
                group.forEach { prop ->
                    blockRects[prop.id] = RectF(x, y, x + blockWidth, y + blockHeight)
                    x += blockWidth + hGap
                }
                if (index == 0) x += pad(groups) * (blockWidth + hGap)
                x += groupGap - hGap
            }
            railroads.getOrNull(row)?.let {
                blockRects[it.id] = RectF(startX + railroadX, y, startX + railroadX + blockWidth, y + blockHeight)
            }
        }

        return padding * 2 + rows.size * blockHeight + (rows.size - 1) * vGap
    }

    // Portrait: the four board-side rows, then a fifth row with the railroads and utilities (no side columns)
    private fun layoutPortrait(w: Float, maxH: Float, rows: List<List<List<Property>>>, railroads: List<Property>, utilities: List<Property>): Float {
        val padding = 10f
        val hGap = 10f
        val vGap = 6f
        val groupGap = hGap * 3
        val firstSlots = rows.maxOf { it.first().size }
        fun pad(groups: List<List<Property>>) = if (groups.size > 1) (firstSlots - groups.first().size).coerceAtLeast(0) else 0
        fun slots(groups: List<List<Property>>) = groups.sumOf { it.size } + pad(groups)
        fun fixedWidth(groups: List<List<Property>>) =
            (slots(groups) - groups.size) * hGap + (groups.size - 1).coerceAtLeast(0) * groupGap
        val lastRow = listOf(railroads, utilities).filter { it.isNotEmpty() }
        val lines = rows + listOf(lastRow)
        val blockWidth = minOf(
            lines.minOf { (w - padding * 2 - fixedWidth(it)) / slots(it) },
            (maxH - padding * 2 - vGap * (lines.size - 1)) / lines.size / 1.25f
        )
        val blockHeight = blockWidth * 1.25f
        val startX = (w - lines.maxOf { slots(it) * blockWidth + fixedWidth(it) }) / 2f

        lines.forEachIndexed { row, groups ->
            val y = padding + row * (blockHeight + vGap)
            var x = startX
            groups.forEachIndexed { index, group ->
                group.forEach { prop ->
                    blockRects[prop.id] = RectF(x, y, x + blockWidth, y + blockHeight)
                    x += blockWidth + hGap
                }
                if (index == 0) x += pad(groups) * (blockWidth + hGap)
                x += groupGap - hGap
            }
        }
        return padding * 2 + lines.size * blockHeight + (lines.size - 1) * vGap
    }

    private fun drawMiniPropertyBlock(canvas: Canvas, prop: Property, rect: RectF) {
        val owner = playersMap[prop.ownerId]

        // Fill and color bar are clipped to the rounded card so the color follows the outline's curve
        val clip = Path().apply { addRoundRect(rect, 10f, 10f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        val fill = if (owner != null) {
            runCatching { ownerPaint.apply { color = Color.parseColor(owner.colorHex) } }.getOrDefault(blockBgPaint)
        } else {
            blockBgPaint
        }
        canvas.drawRect(rect, fill)

        // Color Bar Header
        val barHeight = rect.height() * 0.3f
        val barRect = RectF(rect.left, rect.bottom - barHeight, rect.right, rect.bottom)
        try {
            colorBarPaint.color = Color.parseColor(prop.customColorHex ?: prop.group.colorHex)
        } catch (_: Exception) {
            colorBarPaint.color = Color.GRAY
        }
        canvas.drawRect(barRect, colorBarPaint)
        canvas.restore()

        // Outline on top so the color bar doesn't cover it
        canvas.drawRoundRect(rect, 10f, 10f, outlinePaint)

        // Green house / red hotel icon, with house count
        val icon = if (prop.hasHotel) hotelDrawable else if (prop.houses > 0) houseDrawable else null
        if (icon != null) {
            // Size from the block's 4:5 box so icon + count stay above the color bar on short blocks
            val unit = minOf(rect.width(), rect.height() / 1.25f)
            val iconSize = (unit * 0.5f).toInt()
            val left = (rect.centerX() - iconSize / 2f).toInt()
            val top = (rect.top + rect.height() * 0.08f).toInt()
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
            if (!prop.hasHotel) {
                countPaint.textSize = unit * 0.3f
                countPaint.color = textColorOn(ownerPaint.color)
                canvas.drawText("${prop.houses}", rect.centerX(), top + iconSize + countPaint.textSize * 0.85f, countPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            val x = event.x
            val y = event.y
            // onMeasure may have left rects for other measure specs; hit-test against what is drawn
            layoutBlocks(width.toFloat(), height.toFloat())

            for ((propId, rect) in blockRects) {
                if (rect.contains(x, y)) {
                    val prop = propertiesList.firstOrNull { it.id == propId }
                    if (prop != null) {
                        onPropertyClickListener?.invoke(prop)
                        return true
                    }
                }
            }
        }
        return true
    }

    companion object {
        // Groups per row (one row per board side); railroads and utilities go in their own columns
        private val ROW_GROUPS = listOf(
            listOf(PropertyGroup.BROWN, PropertyGroup.LIGHT_BLUE),
            listOf(PropertyGroup.MAGENTA, PropertyGroup.ORANGE),
            listOf(PropertyGroup.RED, PropertyGroup.YELLOW),
            listOf(PropertyGroup.GREEN, PropertyGroup.DARK_BLUE)
        )
    }
}
