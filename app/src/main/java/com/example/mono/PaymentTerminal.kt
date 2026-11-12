package com.example.mono

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

// Card-style payment terminal: LCD amount + messages on the left, keypad + actions on the right.
// It stays open while a card is awaited and shows help, confirmations and balances in place.
class PaymentTerminal(private val activity: Activity, private val listener: Listener) {

    enum class Kind { CHARGE, DEPOSIT, TRANSFER, PASS_GO }

    class BalanceChange(val name: String, val from: Int, val to: Int)

    interface Listener {
        fun onAction(kind: Kind, amount: Int)
        fun onReset()
        fun onCancel() // closed while a card was awaited
    }

    private enum class State { ENTERING, WAITING, DONE }

    private var dialog: Dialog? = null
    private var state = State.ENTERING
    private var input = ""
    private var token = 0 // invalidates pending auto-close when a new step starts
    private var genericEntry = false
    private var headerSubtitle = ""

    private lateinit var display: SevenSegmentView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var stateIcon: ImageView
    private lateinit var message: TextView
    private lateinit var changes: LinearLayout
    private lateinit var keypad: View
    private lateinit var actions: LinearLayout
    private lateinit var cancel: View
    private lateinit var reset: View
    private var pulse: ObjectAnimator? = null

    val isOpen get() = dialog?.isShowing == true

    // The banker may scan a card before choosing amount and operation (only on the generic terminal)
    val acceptsPreScan get() = isOpen && state == State.ENTERING && genericEntry

    fun showScanned(name: String?) {
        subtitle.text = if (name == null) headerSubtitle else "Tarjeta: $name"
    }

    // Banker types the amount; `confirm` (e.g. utility rent) replaces the generic action buttons
    fun showEntry(
        titleText: String,
        subtitleText: String,
        confirmLabel: String? = null,
        initialAmount: Int = 0,
        onConfirm: ((Int) -> Unit)? = null
    ) {
        ensureDialog()
        state = State.ENTERING
        genericEntry = confirmLabel == null
        token++
        input = if (initialAmount > 0) initialAmount.toString() else ""
        setHeader(titleText, subtitleText)
        updateDisplay()
        showMessage(
            R.drawable.ic_contactless,
            if (confirmLabel != null) "Ingresa el monto y confirma" else "Ingresa el monto y elige la operación",
            Color.WHITE, pulsing = false
        )
        changes.removeAllViews()
        keypad.alpha = 1f
        setKeypadEnabled(true)
        cancel.isVisible = false
        reset.isVisible = confirmLabel == null
        actions.isVisible = true
        actions.removeAllViews()
        if (confirmLabel != null && onConfirm != null) {
            actions.addView(actionButton(confirmLabel, R.drawable.ic_check) { amount -> onConfirm(amount) })
        } else {
            actions.addView(actionButton("Cobrar", R.drawable.ic_charge) { listener.onAction(Kind.CHARGE, it) })
            actions.addView(actionButton("Depositar", R.drawable.ic_deposit) { listener.onAction(Kind.DEPOSIT, it) })
            actions.addView(actionButton("Transferir", R.drawable.ic_transfer) { listener.onAction(Kind.TRANSFER, it) })
            actions.addView(actionButton("Salida", R.drawable.ic_go) { listener.onAction(Kind.PASS_GO, GO_AMOUNT) })
        }
    }

    // A card is awaited: pulsing contactless icon with the help text, keypad locked
    fun showWaiting(titleText: String, help: String, amount: Int) {
        ensureDialog()
        state = State.WAITING
        token++
        setHeader(titleText, "Terminal de pago")
        display.setValue(amount)
        showMessage(R.drawable.ic_contactless, help, Color.WHITE, pulsing = true)
        changes.removeAllViews()
        keypad.alpha = 0.3f
        setKeypadEnabled(false)
        actions.isVisible = false
        reset.isVisible = false
        cancel.isVisible = true
    }

    fun showError(text: String) {
        if (!isOpen) return
        showMessage(R.drawable.ic_contactless, text, Color.parseColor("#FF6B6B"), pulsing = true)
        ObjectAnimator.ofFloat(display, View.TRANSLATION_X, 0f, -16f, 16f, -10f, 10f, -4f, 4f, 0f).setDuration(380).start()
    }

    fun showSuccess(text: String, balances: List<BalanceChange>) {
        if (!isOpen) return
        state = State.DONE
        val mine = ++token
        subtitle.text = "Operación completada"
        cancel.isVisible = false
        showMessage(R.drawable.ic_check, text, Color.parseColor("#3CCB7A"), pulsing = false)
        stateIcon.scaleX = 0f
        stateIcon.scaleY = 0f
        stateIcon.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(450).start()
        changes.removeAllViews()
        balances.forEachIndexed { i, change ->
            // Green when money comes in, red when it goes out
            val tint = when {
                change.to > change.from -> Color.parseColor("#3CCB7A")
                change.to < change.from -> Color.parseColor("#FF6B6B")
                else -> Color.WHITE
            }
            val row = TextView(activity).apply {
                setTextColor(tint)
                textSize = 15f
                alpha = 0f
                translationY = 16f
            }
            changes.addView(row)
            row.animate().alpha(1f).translationY(0f).setStartDelay(150L * i + 200).setDuration(300).start()
            ValueAnimator.ofInt(change.from, change.to).apply {
                duration = 900
                startDelay = 150L * i + 300
                addUpdateListener { row.text = String.format(Locale.US, "%s  $%,d", change.name, it.animatedValue as Int) }
                row.text = String.format(Locale.US, "%s  $%,d", change.name, change.from)
                // The color only lasts while the numbers run: it fades to white with the final balance
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        ObjectAnimator.ofArgb(row, "textColor", tint, Color.WHITE).setDuration(300).start()
                    }
                })
                start()
            }
        }
        display.postDelayed({ if (token == mine) dialog?.dismiss() }, 3000)
    }

    fun close() {
        state = State.ENTERING // already handled, so closing doesn't report a cancel
        dialog?.dismiss()
    }

    private fun ensureDialog() {
        if (isOpen) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_payment_terminal, null)
        display = view.findViewById(R.id.seven_segment)
        title = view.findViewById(R.id.tv_term_title)
        subtitle = view.findViewById(R.id.tv_term_subtitle)
        stateIcon = view.findViewById(R.id.iv_term_state)
        message = view.findViewById(R.id.tv_term_message)
        changes = view.findViewById(R.id.ll_term_changes)
        keypad = view.findViewById(R.id.ll_keypad)
        actions = view.findViewById(R.id.ll_term_actions)
        cancel = view.findViewById(R.id.btn_term_cancel)
        reset = view.findViewById(R.id.btn_term_reset)
        buildKeypad(keypad as LinearLayout)
        cancel.setOnClickListener { dialog?.dismiss() }
        reset.setOnClickListener {
            state = State.ENTERING
            dialog?.dismiss()
            listener.onReset()
        }

        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                pulse?.cancel()
                val wasWaiting = state == State.WAITING
                state = State.ENTERING
                dialog = null
                if (wasWaiting) listener.onCancel()
            }
            show()
        }
    }

    private fun setHeader(t: String, s: String) {
        headerSubtitle = s
        title.text = t
        subtitle.text = s
    }

    private fun showMessage(icon: Int, text: String, color: Int, pulsing: Boolean) {
        pulse?.cancel()
        stateIcon.setImageResource(icon)
        stateIcon.imageTintList = ColorStateList.valueOf(color)
        stateIcon.alpha = 1f
        stateIcon.scaleX = 1f
        stateIcon.scaleY = 1f
        message.setTextColor(color)
        message.text = text
        message.alpha = 0f
        message.animate().alpha(1f).setDuration(250).start()
        if (pulsing) {
            pulse = ObjectAnimator.ofFloat(stateIcon, View.ALPHA, 1f, 0.25f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun updateDisplay() = display.setValue(input.toIntOrNull() ?: 0)

    private fun buildKeypad(container: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Salir", "0", "⌫"))
        rows.forEach { labels ->
            val row = LinearLayout(activity)
            labels.forEach { label ->
                val key = MaterialButton(activity).apply {
                    text = label
                    textSize = if (label == "Salir") 13f else 18f
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = (12 * density).toInt()
                    val exit = label == "Salir"
                    backgroundTintList = ColorStateList.valueOf(activity.getColor(if (exit) R.color.close_red else R.color.key_bg))
                    setTextColor(activity.getColor(if (exit) R.color.white else R.color.text_primary))
                    if (exit) {
                        // Word on one line that shrinks to fit the key instead of wrapping ("Sali / r")
                        setPadding(0, 0, 0, 0)
                        maxLines = 1
                        includeFontPadding = false
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 16, 1, TypedValue.COMPLEX_UNIT_SP)
                    }
                    setOnClickListener { onKey(label) }
                }
                row.addView(key, LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                    setMargins((3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt())
                })
            }
            container.addView(row)
        }
    }

    private fun onKey(label: String) {
        if (label == "Salir") {
            dialog?.dismiss()
            return
        }
        if (state != State.ENTERING) return
        input = when (label) {
            "⌫" -> input.dropLast(1)
            else -> if (input.length < SevenSegmentView.DIGITS) (input + label).trimStart('0') else input
        }
        updateDisplay()
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        val group = keypad as LinearLayout
        for (r in 0 until group.childCount) {
            val row = group.getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) row.getChildAt(k).isEnabled = enabled
        }
    }

    private fun actionButton(label: String, icon: Int, onClick: (Int) -> Unit): View {
        val item = activity.layoutInflater.inflate(R.layout.item_terminal_action, actions, false)
        item.findViewById<ImageView>(R.id.iv_action_icon).setImageResource(icon)
        item.findViewById<TextView>(R.id.tv_action_label).text = label
        item.setOnClickListener {
            val amount = input.toIntOrNull() ?: 0
            if (label != "Salida" && amount <= 0) {
                showError("Ingresa una cantidad válida")
                return@setOnClickListener
            }
            onClick(amount)
        }
        return item
    }

    companion object {
        const val GO_AMOUNT = 200
    }
}
