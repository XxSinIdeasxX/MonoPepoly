package com.example.mono

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Custom color picker in the style of the Kaliman color field: recents, base palette, tones of the current color and
// hexadecimal input, plus an "advanced controls" section with hue / saturation / brightness bars that paint the colors
// they represent. Colors used by other players can't be picked.
class ColorPickerDialog(
    private val activity: Activity,
    private val taken: Collection<String>,
    initial: String,
    private val onPicked: (String) -> Unit
) {
    private val density = activity.resources.displayMetrics.density
    private val prefs = activity.getSharedPreferences("mono_settings", Context.MODE_PRIVATE)
    private var current = ColorPalette.normalize(initial) ?: "#3a86ff"
    private val hsv = FloatArray(3)
    private var updating = false

    private lateinit var preview: View
    private lateinit var hexInput: EditText
    private lateinit var hue: SeekBar
    private lateinit var saturation: SeekBar
    private lateinit var brightness: SeekBar
    private lateinit var tones: ViewGroup
    private lateinit var note: TextView
    private var useButton: Button? = null

    private fun dp(value: Int) = (value * density).toInt()

    private val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    private val sidePad = if (portrait) 12 else 20

    // Base columns: one per hue, plus the neutrals column except in portrait where it doesn't fit
    private val columns = ColorPalette.HUES.size + if (portrait) 0 else 1

    // Swatch size that fits the base columns in the dialog width (26dp when there is room, e.g. landscape)
    // The dialog has ~36dp of margin on each side of the screen (measured on a 411dp wide phone)
    private val availDp = minOf(activity.resources.displayMetrics.widthPixels / density - 72f, 560f) - 2 * sidePad
    private val sideColumnDp = 190f
    private val cell: Int =
        ((if (portrait) availDp else availDp - sideColumnDp - 16f) / columns - 2f).coerceIn(16f, 26f).toInt()

    // Tones (5 lighter, the color, 6 darker = 12): one row in portrait, two rows of 6 beside the base grid in landscape
    private val toneColumns = if (portrait) 12 else 6
    private val toneCell: Int = ((if (portrait) availDp else sideColumnDp) / toneColumns - 2f).coerceIn(14f, 32f).toInt()

    fun show() {
        // Portrait: one column. Landscape: base grid on the left, everything else on the right
        val content = LinearLayout(activity).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        // Advanced controls go below everything, spanning the full width of the dialog
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(sidePad), dp(12), dp(sidePad), 0)
            addView(content)
        }
        val side = if (portrait) content else LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        preview = View(activity)
        hexInput = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "#ff4444"
            filters = arrayOf(InputFilter.LengthFilter(7))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updating) return
                    ColorPalette.normalize(s?.toString())?.let { set(it, fromHex = true) }
                }
            })
        }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(dp(if (portrait) 56 else 44), dp(if (portrait) 56 else 44)))
            addView(hexInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
        note = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.close_red))
            textSize = 12f
        }
        val baseSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Base"))
            addView(baseGrid())
        }
        tones = if (portrait) LinearLayout(activity) else GridLayout(activity).apply { columnCount = toneColumns }

        // Advanced controls: collapsed by default
        val advanced = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        hue = bar("Matiz", 360, advanced)
        saturation = bar("Saturación", 1000, advanced)
        brightness = bar("Brillo", 1000, advanced)
        val toggle = TextView(activity).apply {
            text = "Controles avanzados ▾"
            textSize = 13f
            setTextColor(activity.getColor(R.color.accent_dark))
            setPadding(0, dp(14), 0, dp(8))
            setOnClickListener {
                advanced.isVisible = !advanced.isVisible
                text = if (advanced.isVisible) "Controles avanzados ▴" else "Controles avanzados ▾"
            }
        }

        if (portrait) {
            content.addView(top)
            content.addView(note)
            content.addView(baseSection)
            content.addView(label("Tonos"))
            content.addView(tones)
        } else {
            content.addView(baseSection)
            side.addView(top)
            side.addView(note)
            side.addView(label("Tonos"))
            side.addView(tones)
            content.addView(side, LinearLayout.LayoutParams(dp(sideColumnDp.toInt()), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
            })
        }
        outer.addView(toggle)
        outer.addView(advanced)

        set(current)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Color personalizado")
            .setView(ScrollView(activity).apply { addView(outer) })
            .setPositiveButton("Usar") { _, _ ->
                onPicked(current)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            useButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            refreshUse()
        }
        dialog.show()
    }

    // A named bar whose track is painted with the colors it controls (see updateTracks)
    private fun bar(name: String, max: Int, parent: LinearLayout): SeekBar {
        parent.addView(TextView(activity).apply {
            text = name
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_secondary))
        })
        val bar = SeekBar(activity).apply {
            this.max = max
            setPadding(dp(12), 0, dp(12), 0)
            splitTrack = false
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), activity.getColor(R.color.ink))
                setSize(dp(24), dp(24))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val color = Color.HSVToColor(floatArrayOf(hue.progress.toFloat(), saturation.progress / 1000f, brightness.progress / 1000f))
                    set(ColorPalette.toHex(color), fromSlider = true)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
        return bar
    }

    private fun track(vararg colors: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = 6 * density
        setSize(-1, dp(12))
    }

    // Hue is the fixed rainbow; saturation and brightness show what the bar would give at the current values
    private fun updateTracks() {
        val h = hue.progress.toFloat()
        val s = saturation.progress / 1000f
        val v = brightness.progress / 1000f
        hue.progressDrawable = track(*IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) })
        saturation.progressDrawable = track(Color.HSVToColor(floatArrayOf(h, 0f, v)), Color.HSVToColor(floatArrayOf(h, 1f, v)))
        brightness.progressDrawable = track(Color.BLACK, Color.HSVToColor(floatArrayOf(h, s, 1f)))
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(activity.getColor(R.color.text_secondary))
        setPadding(0, dp(12), 0, dp(4))
    }

    // Updates every part of the dialog from the new color, except the one the change came from
    private fun set(hex: String, fromHex: Boolean = false, fromSlider: Boolean = false) {
        current = hex
        updating = true
        preview.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(Color.parseColor(hex))
        }
        if (!fromHex) hexInput.setText(hex)
        if (!fromSlider) {
            Color.colorToHSV(Color.parseColor(hex), hsv)
            hue.progress = hsv[0].toInt().coerceIn(0, 360)
            saturation.progress = (hsv[1] * 1000).toInt().coerceIn(0, 1000)
            brightness.progress = (hsv[2] * 1000).toInt().coerceIn(0, 1000)
        }
        updateTracks()
        tones.removeAllViews()
        ColorPalette.tonesOf(hex).forEach { tones.addView(swatch(it, toneCell)) }
        updating = false
        refreshUse()
    }

    private fun refreshUse() {
        val used = current in taken
        note.text = if (used) "Este color ya lo usa otro jugador" else ""
        useButton?.isEnabled = !used
    }

    private fun swatch(hex: String, size: Int): View = View(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(Color.parseColor(hex))
            if (hex == this@ColorPickerDialog.current) setStroke(dp(2), activity.getColor(R.color.text_primary))
            else setStroke(dp(1), activity.getColor(R.color.board_line))
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
        contentDescription = "Usar $hex"
        setOnClickListener { set(hex) }
    }

    // One column per hue (pastel to very dark), neutrals as the last column
    private fun baseGrid(): View {
        val grid = GridLayout(activity).apply { columnCount = columns }
        for (r in 0 until ColorPalette.TONES_PER_HUE) {
            for (c in 0 until columns) {
                val hex = ColorPalette.BASE_PALETTE[c * ColorPalette.TONES_PER_HUE + r]
                grid.addView(swatch(hex, cell))
            }
        }
        return grid
    }
}
