package com.example.mono

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources

class MonopolyBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val propertiesMap = mutableMapOf<Int, Property>()
    private var playersList = listOf<Player>()
    private var onSpaceClickListener: ((Property) -> Unit)? = null
    private var onCenterClickListener: (() -> Unit)? = null

    // Drawables
    private val trainDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_train)
    private val electricDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_electric)
    private val waterDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_water)
    private val taxDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_tax)
    private val goDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_go)
    private val jailDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_jail)
    private val parkingDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_parking)
    private val goToJailDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_go_to_jail)
    private val houseDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_house)
    private val hotelDrawable: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_hotel)

    private val density = resources.displayMetrics.density
    // Width of the narrowest side tile; pins scale with it
    private var tileUnit = 0f

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.board_bg)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.board_line)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.board_line)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val centerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.board_center)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        textSize = 16f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var availableCount = 0
    private var statusTitle = "Listo para escanear tarjetas"
    private var statusHint = "Acerca una tarjeta NFC para seleccionarla"

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        isFakeBoldText = true
    }

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
    }

    private val colorBarPaint =Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ownerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pinBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val spaceRects = Array(40) { RectF() }
    private val centerRect = RectF()

    fun setBoardData(properties: List<Property>, players: List<Player>) {
        propertiesMap.clear()
        properties.forEach { propertiesMap[it.position] = it }
        this.playersList = players
        invalidate()
    }

    fun setAvailableCount(count: Int) {
        availableCount = count
        invalidate()
    }

    fun setStatus(title: String, hint: String) {
        statusTitle = title
        statusHint = hint
        invalidate()
    }

    fun setOnSpaceClickListener(listener: (Property) -> Unit) {
        this.onSpaceClickListener = listener
    }

    fun setOnCenterClickListener(listener: () -> Unit) {
        this.onCenterClickListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = width.toFloat()
        if (size <= 0) return

        // Increase space proportion (30% wider tiles for clearer view)
        val cornerSize = size * 0.18f

        // Draw Outer Background & Border
        canvas.drawRect(0f, 0f, size, size, bgPaint)

        // Draw Center Area
        centerRect.set(cornerSize, cornerSize, size - cornerSize, size - cornerSize)
        canvas.drawRect(centerRect, centerBgPaint)

        drawStatus(canvas, size)

        // Calculate & Draw Space Rectangles
        calculateSpaceRects(size, cornerSize)

        // Render each space (0 to 39)
        for (i in 0..39) {
            drawSpaceTile(canvas, i, spaceRects[i])
        }

        // Outlines last, on top of fills and color bars, so every line has the same weight: tiles (shared edges
        // are drawn at the same spot), the center and the outer frame (inset by half the stroke so it isn't clipped)
        for (i in 0..39) {
            if (!spaceRects[i].isEmpty) canvas.drawRect(spaceRects[i], borderPaint)
        }
        canvas.drawRect(centerRect, borderPaint)
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRect(half, half, size - half, size - half, borderPaint)

        // Render Player Pins on Board Spaces (like image 1)
        drawPlayerPins(canvas)
    }

    // NFC status centered in the board: bold title, wrapped help text and, below, the terminal hint
    private fun drawStatus(canvas: Canvas, size: Float) {
        val width = (size * 0.56f).toInt()
        fun layout(text: String, paint: TextPaint, maxLines: Int) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        statusPaint.textSize = size * 0.04f
        hintPaint.textSize = size * 0.03f
        subTextPaint.textSize = size * 0.024f
        val title = layout(statusTitle, statusPaint, 2)
        val hint = layout(statusHint, hintPaint, 4)
        val gap = size * 0.012f
        val footer = subTextPaint.textSize
        val total = title.height + gap + hint.height + gap * 2 + footer + footer * 1.4f

        canvas.save()
        canvas.translate((size - width) / 2f, (size - total) / 2f)
        title.draw(canvas)
        canvas.translate(0f, title.height + gap)
        hint.draw(canvas)
        canvas.translate(0f, hint.height + gap * 2 + footer * 0.85f)
        canvas.drawText("Toca el centro para abrir la Terminal", width / 2f, 0f, subTextPaint)
        canvas.drawText("$availableCount disponibles", width / 2f, footer * 1.4f, subTextPaint)
        canvas.restore()
    }

    private fun calculateSpaceRects(size: Float, cornerSize: Float) {
        // Corners: GO (bottom-right), Jail (bottom-left), Free Parking (top-left), Go To Jail (top-right)
        spaceRects[0].set(size - cornerSize, size - cornerSize, size, size)
        spaceRects[10].set(0f, size - cornerSize, cornerSize, size)
        spaceRects[20].set(0f, 0f, cornerSize, cornerSize)
        spaceRects[30].set(size - cornerSize, 0f, size, cornerSize)

        // Sides show purchasable spaces and Tax (tap to charge); Chance and Chest are hidden. They share the side's length
        val sideLength = size - 2 * cornerSize
        tileUnit = sideLength
        for (side in 0..3) {
            val positions = (side * 10 + 1)..(side * 10 + 9)
            positions.forEach { spaceRects[it].setEmpty() }
            val visible = positions.filter { pos ->
                propertiesMap[pos]?.let { it.isPurchasable || it.spaceType == SpaceType.TAX } == true
            }
            if (visible.isEmpty()) continue
            val unit = sideLength / visible.size
            tileUnit = minOf(tileUnit, unit)
            visible.forEachIndexed { k, pos ->
                val offset = cornerSize + k * unit
                when (side) {
                    // Bottom: right to left
                    0 -> spaceRects[pos].set(size - offset - unit, size - cornerSize, size - offset, size)
                    // Left: bottom to top
                    1 -> spaceRects[pos].set(0f, size - offset - unit, cornerSize, size - offset)
                    // Top: left to right
                    2 -> spaceRects[pos].set(offset, 0f, offset + unit, cornerSize)
                    // Right: top to bottom
                    else -> spaceRects[pos].set(size - cornerSize, offset, size, offset + unit)
                }
            }
        }
    }

    private fun drawSpaceTile(canvas: Canvas, position: Int, rect: RectF) {
        if (rect.isEmpty) return
        val prop = propertiesMap[position] ?: return

        // Determine owner
        val owner = playersList.firstOrNull { it.id == prop.ownerId }
        if (owner != null) {
            try {
                ownerPaint.color = Color.parseColor(owner.colorHex)
                canvas.drawRect(rect, ownerPaint)
            } catch (_: Exception) {
            }
        }

        // Draw Color Bar for properties
        if (prop.spaceType == SpaceType.PROPERTY || prop.spaceType == SpaceType.RAILROAD || prop.spaceType == SpaceType.UTILITY) {
            val barColorStr = prop.customColorHex ?: prop.group.colorHex
            try {
                colorBarPaint.color = Color.parseColor(barColorStr)
            } catch (_: Exception) {
                colorBarPaint.color = Color.GRAY
            }

            val barThickness = rect.height().coerceAtMost(rect.width()) * 0.32f
            val barRect = RectF()

            when {
                position in 1..9 -> {
                    barRect.set(rect.left, rect.top, rect.right, rect.top + barThickness)
                }
                position in 11..19 -> {
                    barRect.set(rect.right - barThickness, rect.top, rect.right, rect.bottom)
                }
                position in 21..29 -> {
                    barRect.set(rect.left, rect.bottom - barThickness, rect.right, rect.bottom)
                }
                position in 31..39 -> {
                    barRect.set(rect.left, rect.top, rect.left + barThickness, rect.bottom)
                }
            }
            if (!barRect.isEmpty) {
                canvas.drawRect(barRect, colorBarPaint)
                // Thin line on the bar's inner edge separates it from the tile body
                when {
                    position in 1..9 -> canvas.drawLine(barRect.left, barRect.bottom, barRect.right, barRect.bottom, dividerPaint)
                    position in 11..19 -> canvas.drawLine(barRect.left, barRect.top, barRect.left, barRect.bottom, dividerPaint)
                    position in 21..29 -> canvas.drawLine(barRect.left, barRect.top, barRect.right, barRect.top, dividerPaint)
                    position in 31..39 -> canvas.drawLine(barRect.right, barRect.top, barRect.right, barRect.bottom, dividerPaint)
                }
            }
        }

        // Draw Vector Icon for Special Spaces
        val iconDrawable = when (prop.spaceType) {
            SpaceType.RAILROAD -> trainDrawable
            SpaceType.UTILITY -> if (prop.id.contains("util_1")) electricDrawable else waterDrawable
            SpaceType.TAX -> taxDrawable
            else -> null
        }

        val unit = rect.width().coerceAtMost(rect.height())
        if (iconDrawable != null) {
            drawIcon(canvas, iconDrawable, rect.centerX(), rect.centerY(), unit * 0.45f)
        } else when (prop.spaceType) {
            SpaceType.GO -> drawCorner(canvas, rect, goDrawable, "SALIDA")
            SpaceType.JAIL -> drawCorner(canvas, rect, jailDrawable, "CÁRCEL")
            SpaceType.FREE_PARKING -> drawCorner(canvas, rect, parkingDrawable, "PARADA")
            SpaceType.GO_TO_JAIL -> drawCorner(canvas, rect, goToJailDrawable, "A CÁRCEL")
            else -> Unit
        }

        // Green houses / red hotel
        if (prop.hasHotel) {
            drawIcon(canvas, hotelDrawable, rect.centerX(), rect.centerY(), unit * 0.5f)
        } else if (prop.houses > 0) {
            // Line houses up along the tile's long side so 4 still fit at a readable size
            val vertical = rect.height() > rect.width()
            val houseSize = (unit * 0.45f).coerceAtMost(maxOf(rect.width(), rect.height()) / 4.4f)
            val spacing = houseSize * 0.1f
            val start = -(prop.houses * houseSize + (prop.houses - 1) * spacing) / 2f + houseSize / 2f
            for (h in 0 until prop.houses) {
                val offset = start + h * (houseSize + spacing)
                val cx = if (vertical) rect.centerX() else rect.centerX() + offset
                val cy = if (vertical) rect.centerY() + offset else rect.centerY()
                drawIcon(canvas, houseDrawable, cx, cy, houseSize)
            }
        }
    }

    private fun drawCorner(canvas: Canvas, rect: RectF, icon: Drawable?, label: String) {
        drawIcon(canvas, icon, rect.centerX(), rect.centerY() - rect.height() * 0.1f, rect.width() * 0.45f)
        textPaint.textSize = rect.width() * 0.13f
        canvas.drawText(label, rect.centerX(), rect.bottom - rect.height() * 0.12f, textPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable?, cx: Float, cy: Float, size: Float) {
        if (icon == null) return
        val half = size / 2f
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }

    private fun drawPlayerPins(canvas: Canvas) {
        val path = Path()

        playersList.forEach { player ->
            val rect = spaceRects.getOrNull(player.boardPosition) ?: return@forEach
            if (rect.isEmpty) return@forEach // Player saved on a hidden space (Chance, Chest)
            // Players sharing a space are laid side by side so none is hidden behind another
            val sharing = playersList.filter { it.boardPosition == player.boardPosition }
            val radius = tileUnit * if (sharing.size > 2) 0.22f else 0.3f
            val slot = sharing.indexOf(player) - (sharing.size - 1) / 2f
            try {
                pinPaint.color = Color.parseColor(player.colorHex)
            } catch (_: Exception) {
                pinPaint.color = Color.RED
            }

            val cx = rect.centerX() + slot * radius * 1.6f
            val cy = rect.centerY()

            // Draw Teardrop / Map Pin Marker (like image 1)
            val headY = cy - radius * 0.55f
            path.reset()
            // CCW like the triangle below: opposite windings cancel out where they overlap and leave a hole
            path.addCircle(cx, headY, radius, Path.Direction.CCW)
            path.moveTo(cx - radius, headY)
            path.lineTo(cx, cy + radius * 0.7f)
            path.lineTo(cx + radius, headY)
            path.close()

            canvas.drawPath(path, pinPaint)
            canvas.drawPath(path, pinBorderPaint)

            // Token emoji inside pin
            textPaint.textSize = radius
            canvas.drawText(player.tokenEmoji, cx, headY + radius * 0.35f, textPaint)
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

            if (centerRect.contains(x, y)) {
                onCenterClickListener?.invoke()
                return true
            }

            for (i in 0..39) {
                if (spaceRects[i].contains(x, y)) {
                    val prop = propertiesMap[i]
                    if (prop != null) {
                        onSpaceClickListener?.invoke(prop)
                        return true
                    }
                }
            }
        }
        return true
    }
}
