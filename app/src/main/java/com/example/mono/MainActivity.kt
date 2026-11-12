package com.example.mono

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.widget.HorizontalScrollView
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var playerStore: PlayerStore
    private lateinit var playerAdapter: PlayerAdapter
    private val players = mutableListOf<Player>()

    private lateinit var propertyStore: PropertyStore
    private lateinit var boardAdapter: BoardAdapter
    private lateinit var monopolyBoardView: MonopolyBoardView
    private lateinit var propertyGridView: PropertyGridView
    private val properties = mutableListOf<Property>()

    private var nfcAdapter: NfcAdapter? = null
    private var pendingAction: PendingAction = PendingAction.Idle
    private var gameStarted = false
    private var initialBalance: Int = STARTING_BALANCE
    private var currentFilter: BoardFilter = BoardFilter.All
    private var scannedPlayerId: String? = null // card scanned on the terminal before choosing the operation
    private lateinit var terminal: PaymentTerminal

    private lateinit var statusText: TextView
    private lateinit var emptyPlayersText: TextView
    private lateinit var pregameActions: android.view.View
    private lateinit var initialBalanceText: TextView
    private lateinit var initialBalanceCard: android.view.View

    private val prefs by lazy { getSharedPreferences("mono_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        savedInstanceState?.let {
            gameStarted = it.getBoolean(STATE_GAME_STARTED)
            screen = Screen.valueOf(it.getString(STATE_SCREEN, Screen.CARDS.name))
            browseScreen = Screen.valueOf(it.getString(STATE_BROWSE_SCREEN, Screen.CARDS.name))
        }

        playerStore = PlayerStore(this)
        players.addAll(playerStore.loadPlayers())

        propertyStore = PropertyStore(this)
        properties.addAll(propertyStore.loadProperties())

        statusText = findViewById(R.id.tv_status)
        emptyPlayersText = findViewById(R.id.tv_empty_players)
        pregameActions = findViewById(R.id.layout_pregame_actions)
        initialBalanceText = findViewById(R.id.tv_initial_balance)
        initialBalanceCard = findViewById(R.id.card_initial_balance)
        monopolyBoardView = findViewById(R.id.monopoly_board_view)
        propertyGridView = findViewById(R.id.property_grid_view)

        initialBalance = prefs.getInt(KEY_INITIAL_BALANCE, STARTING_BALANCE)
        updateInitialBalanceText()

        // Players RecyclerView setup
        val recyclerPlayers = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_players)
        recyclerPlayers.layoutManager = LinearLayoutManager(this)
        playerAdapter = PlayerAdapter { player ->
            showPlayerOptions(player)
        }
        recyclerPlayers.adapter = playerAdapter

        // Monopoly Board RecyclerView setup
        val recyclerBoard = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_board_properties)
        recyclerBoard.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.property_columns))
        boardAdapter = BoardAdapter(
            onPropertyClick = { property -> showPropertyOptions(property) },
            rentOf = { property -> property.getCurrentRent(properties) }
        )
        recyclerBoard.adapter = boardAdapter

        // Monopoly Top-Down Board View touch callback
        monopolyBoardView.setOnSpaceClickListener { property ->
            showPropertyOptions(property)
        }
        terminal = PaymentTerminal(this, object : PaymentTerminal.Listener {
            override fun onAction(kind: PaymentTerminal.Kind, amount: Int) = startTerminalAction(kind, amount)
            override fun onReset() = confirmResetGame()
            override fun onCancel() {
                pendingAction = PendingAction.Idle
                setNfcStatus("Listo", "Operación cancelada")
            }
        })
        monopolyBoardView.setOnCenterClickListener {
            showTerminalDialog()
        }
        propertyGridView.setOnPropertyClickListener { property ->
            showPropertyOptions(property)
        }

        setupViewToggle()
        refreshPlayers()
        refreshBoard()

        findViewById<MaterialButton>(R.id.btn_reset_game).setOnClickListener { confirmResetGame() }
        // The picker is hidden once the game starts, so it can only be tapped before
        initialBalanceCard.setOnClickListener { showInitialBalanceDialog() }
        updateGameState()

        findViewById<MaterialButton>(R.id.btn_add_player).setOnClickListener {
            showAddPlayerDialog()
        }

        findViewById<MaterialButton>(R.id.btn_start_game).setOnClickListener {
            if (players.isEmpty()) {
                showToast("Agrega al menos un jugador")
                return@setOnClickListener
            }
            gameStarted = true
            updateGameState()
            setNfcStatus("Modo juego activo", "Toca el centro del tablero para abrir la terminal")
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setNfcStatus("NFC no disponible", "Este dispositivo no soporta NFC")
        } else if (nfcAdapter?.isEnabled == false) {
            setNfcStatus("NFC desactivado", "Activa NFC en ajustes para continuar")
        }
    }

    // Rotation recreates the activity: keep the game phase and the open screen
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GAME_STARTED, gameStarted)
        outState.putString(STATE_SCREEN, screen.name)
        outState.putString(STATE_BROWSE_SCREEN, browseScreen.name)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val tagId = tagIdToHex(tag)
        when (val action = pendingAction) {
            is PendingAction.AddPlayer -> handleAddPlayer(tag, tagId, action)
            is PendingAction.Charge -> handleCharge(tag, tagId, action.amount, action.position)
            is PendingAction.Deposit -> handleDepositScan(tag, tagId, action.amount)
            is PendingAction.TransferFromAwaitTag -> handleTransferFrom(tag, tagId, action.amount)
            is PendingAction.TransferToAwaitTag -> handleTransferTo(tag, tagId, action)
            PendingAction.PassGoAwaitTag -> handlePassGoScan(tag, tagId)
            is PendingAction.BuyProperty -> handleBuyPropertyScan(tag, tagId, action.propertyId)
            is PendingAction.PayRent -> handlePayRentScan(tag, tagId, action.propertyId, action.amount)
            PendingAction.Idle -> if (terminal.acceptsPreScan) handlePreScan(tag, tagId) else handleInfoScan(tag, tagId)
        }
    }

    private fun handleAddPlayer(tag: Tag, tagId: String, action: PendingAction.AddPlayer) {
        val existingId = readPlayerId(tag)
        if (existingId != null && players.any { it.id == existingId }) {
            runOnUiThread {
                showToast("Esta tarjeta ya está registrada")
                setNfcStatus("Listo", "Selecciona otra operación")
            }
            pendingAction = PendingAction.Idle
            return
        }

        if (players.any { it.tagId == tagId }) {
            runOnUiThread {
                showToast("Esta tarjeta ya está registrada")
                setNfcStatus("Listo", "Selecciona otra operación")
            }
            pendingAction = PendingAction.Idle
            return
        }

        val playerId = UUID.randomUUID().toString()
        val nextIndex = players.size
        val token = PlayerStore.PLAYER_TOKENS[nextIndex % PlayerStore.PLAYER_TOKENS.size]
        val player = Player(playerId, action.name, initialBalance, tagId, action.colorHex, token)
        players.add(player)
        playerStore.savePlayers(players)

        val wrote = writePlayerIdToTag(tag, playerId)
        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            if (wrote) {
                showToast("Jugador ${action.name} agregado")
            } else {
                showToast("Jugador agregado, pero no se pudo escribir la tarjeta")
            }
            setNfcStatus("Listo", "Jugador registrado correctamente")
        }
        pendingAction = PendingAction.Idle
    }

    // Starts waiting for a card: shared status line plus the terminal showing the amount and help text
    private fun awaitTag(action: PendingAction, title: String, help: String, amount: Int) {
        pendingAction = action
        setNfcStatus(title, help)
        runOnUiThread { terminal.showWaiting(title, help, amount) }
    }

    private fun change(before: Player, after: Player) = PaymentTerminal.BalanceChange(after.name, before.balance, after.balance)

    // Confirmation goes in the terminal while it is open, a toast otherwise (runs on the UI thread)
    private fun report(message: String, vararg changes: PaymentTerminal.BalanceChange) {
        if (terminal.isOpen) terminal.showSuccess(message, changes.toList()) else showToast(message)
    }

    // Recoverable problem: the card can simply be scanned again
    private fun retry(message: String) {
        runOnUiThread { if (terminal.isOpen) terminal.showError(message) else showToast(message) }
    }

    // Unrecoverable problem: drop the pending operation
    private fun abort(message: String) {
        pendingAction = PendingAction.Idle
        runOnUiThread {
            terminal.close()
            showToast(message)
        }
    }

    private fun handlePreScan(tag: Tag, tagId: String) {
        val player = findPlayerByTag(tag, tagId)
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (player.bankrupt) {
            retry("${player.name} está en bancarrota")
            return
        }
        scannedPlayerId = player.id
        runOnUiThread { terminal.showScanned("${player.tokenEmoji} ${player.name}") }
    }

    private fun startTerminalAction(kind: PaymentTerminal.Kind, amount: Int) {
        // A card scanned beforehand is the target (charge, deposit, GO) or the sender (transfer)
        val scanned = if (terminal.isOpen) players.firstOrNull { it.id == scannedPlayerId } else null
        scannedPlayerId = null
        if (scanned != null) {
            when (kind) {
                PaymentTerminal.Kind.CHARGE -> applyCharge(scanned, amount, null)
                PaymentTerminal.Kind.DEPOSIT -> applyDeposit(scanned, amount)
                PaymentTerminal.Kind.PASS_GO -> applyPassGo(scanned)
                PaymentTerminal.Kind.TRANSFER -> awaitTag(
                    PendingAction.TransferToAwaitTag(scanned.id, amount),
                    "Transferencia", "${scanned.name} envía $$amount. Acerca la tarjeta del jugador que recibe", amount
                )
            }
            return
        }
        when (kind) {
            PaymentTerminal.Kind.CHARGE ->
                awaitTag(PendingAction.Charge(amount), "Cobro", "Acerca la tarjeta del jugador a cobrar", amount)
            PaymentTerminal.Kind.DEPOSIT ->
                awaitTag(PendingAction.Deposit(amount), "Depósito", "Acerca la tarjeta del jugador que recibe", amount)
            PaymentTerminal.Kind.TRANSFER ->
                awaitTag(PendingAction.TransferFromAwaitTag(amount), "Transferencia", "Acerca la tarjeta del jugador que envía", amount)
            PaymentTerminal.Kind.PASS_GO ->
                awaitTag(PendingAction.PassGoAwaitTag, "Salida", "Acerca la tarjeta del jugador para cobrar $${PaymentTerminal.GO_AMOUNT}", amount)
        }
    }

    // position: the tax space being paid, so the token follows where the player landed (null for a plain charge)
    private fun handleCharge(tag: Tag, tagId: String, amount: Int, position: Int?) {
        val player = findPlayerByTag(tag, tagId)
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }
        applyCharge(player, amount, position)
    }

    private fun applyCharge(player: Player, amount: Int, position: Int?) {
        if (!ensureFunds(player, amount)) return
        val updated = player.copy(balance = player.balance - amount, boardPosition = position ?: player.boardPosition)
        replacePlayer(updated)
        pendingAction = PendingAction.Idle
        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Listo", "Cobro registrado")
            report("Cobro a ${player.name} por $$amount", change(player, updated))
        }
    }

    private fun handleDepositScan(tag: Tag, tagId: String, amount: Int) {
        val player = findPlayerByTag(tag, tagId)
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (player.bankrupt) {
            retry("${player.name} está en bancarrota")
            return
        }
        applyDeposit(player, amount)
    }

    private fun applyDeposit(player: Player, amount: Int) {
        val updated = player.copy(balance = player.balance + amount)
        replacePlayer(updated)
        pendingAction = PendingAction.Idle
        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Listo", "Depósito confirmado")
            report("Depósito de $$amount a ${player.name}", change(player, updated))
        }
    }

    private fun handlePassGoScan(tag: Tag, tagId: String) {
        val player = findPlayerByTag(tag, tagId)
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (player.bankrupt) {
            retry("${player.name} está en bancarrota")
            return
        }
        applyPassGo(player)
    }

    private fun applyPassGo(player: Player) {
        // Passing GO doesn't mean landing on it: the token stays put
        val updated = player.copy(balance = player.balance + PaymentTerminal.GO_AMOUNT)
        replacePlayer(updated)
        pendingAction = PendingAction.Idle
        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Listo", "Cobro de Salida ($${PaymentTerminal.GO_AMOUNT}) abonado a ${player.name}")
            report("${player.name} recibió $${PaymentTerminal.GO_AMOUNT} por pasar por Salida", change(player, updated))
        }
    }

    private fun handleBuyPropertyScan(tag: Tag, tagId: String, propertyId: String) {
        val player = findPlayerByTag(tag, tagId)
        val property = properties.firstOrNull { it.id == propertyId }
        if (!gameStarted) {
            abort("Inicia el juego para comprar propiedades")
            return
        }
        if (property == null) {
            abort("Propiedad no válida")
            return
        }
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }

        if (!ensureFunds(player, property.price)) return
        val updatedPlayer = player.copy(balance = player.balance - property.price, boardPosition = property.position)
        val updatedProperty = property.copy(ownerId = player.id)

        replacePlayer(updatedPlayer)
        replaceProperty(updatedProperty)
        pendingAction = PendingAction.Idle

        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Compra registrada", "${property.name} ahora es de ${player.name}")
            report("${player.name} compró ${property.name} por $${property.price}", change(player, updatedPlayer))
        }
    }

    private fun handlePayRentScan(tag: Tag, tagId: String, propertyId: String, rentAmount: Int) {
        val tenant = findPlayerByTag(tag, tagId)
        val property = properties.firstOrNull { it.id == propertyId }
        val owner = players.firstOrNull { it.id == property?.ownerId }

        if (property == null || owner == null) {
            abort("Propiedad o dueño no válido")
            return
        }
        if (tenant == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (tenant.id == owner.id) {
            retry("${tenant.name} es el dueño de esta propiedad")
            return
        }
        if (rentAmount <= 0) {
            abort("No hay alquiler por pagar")
            return
        }

        if (!ensureFunds(tenant, rentAmount, owner)) return
        val tenantUpdated = tenant.copy(balance = tenant.balance - rentAmount, boardPosition = property.position)
        val ownerUpdated = owner.copy(balance = owner.balance + rentAmount)

        replacePlayer(tenantUpdated)
        replacePlayer(ownerUpdated)
        pendingAction = PendingAction.Idle

        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Alquiler pagado", "${tenant.name} -> $${rentAmount} -> ${owner.name}")
            report(
                "${tenant.name} pagó $${rentAmount} de alquiler a ${owner.name}",
                change(tenant, tenantUpdated), change(owner, ownerUpdated)
            )
        }
    }

    private fun handleTransferFrom(tag: Tag, tagId: String, amount: Int) {
        val player = findPlayerByTag(tag, tagId)
        if (player == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (player.bankrupt) {
            retry("${player.name} está en bancarrota")
            return
        }
        awaitTag(
            PendingAction.TransferToAwaitTag(player.id, amount),
            "Transferencia", "${player.name} envía $$amount. Acerca la tarjeta del jugador que recibe", amount
        )
    }

    private fun handleTransferTo(tag: Tag, tagId: String, action: PendingAction.TransferToAwaitTag) {
        val receiver = findPlayerByTag(tag, tagId)
        val sender = players.firstOrNull { it.id == action.fromId }
        if (receiver == null || sender == null) {
            retry("Tarjeta no registrada")
            return
        }
        if (receiver.id == sender.id) {
            retry("Selecciona un jugador distinto")
            return
        }
        if (receiver.bankrupt) {
            retry("${receiver.name} está en bancarrota")
            return
        }
        if (!ensureFunds(sender, action.amount, receiver)) return
        val senderUpdated = sender.copy(balance = sender.balance - action.amount)
        val receiverUpdated = receiver.copy(balance = receiver.balance + action.amount)
        replacePlayer(senderUpdated)
        replacePlayer(receiverUpdated)
        pendingAction = PendingAction.Idle
        runOnUiThread {
            refreshPlayers()
            refreshBoard()
            setNfcStatus("Listo", "Transferencia completada")
            report(
                "Transferencia de ${sender.name} a ${receiver.name}",
                change(sender, senderUpdated), change(receiver, receiverUpdated)
            )
        }
    }

    private fun handleInfoScan(tag: Tag, tagId: String) {
        val player = findPlayerByTag(tag, tagId)
        runOnUiThread {
            if (player == null) {
                setNfcStatus("Tarjeta desconocida", "Registra la tarjeta desde Agregar jugador")
            } else {
                val ownedProps = properties.count { it.ownerId == player.id }
                setNfcStatus("Jugador detectado", "${player.tokenEmoji} ${player.name} · $${player.balance} · $ownedProps propiedades")
            }
        }
    }

    private fun showAddPlayerDialog() {
        val taken = players.map { it.colorHex }
        var colorHex = PlayerStore.PLAYER_COLORS.firstOrNull { it !in taken } ?: PlayerStore.PLAYER_COLORS.first()
        val token = PlayerStore.PLAYER_TOKENS[players.size % PlayerStore.PLAYER_TOKENS.size]
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        gamePanel("Nuevo jugador") { dialog ->
            fun circle(color: Int, stroke: Int, width: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(width, stroke)
            }
            // Preview of the token the player will get, with the chosen color as a small circle at its corner
            val colorDot = View(this).apply { background = circle(Color.parseColor(colorHex), getColor(R.color.ink), dp(3)) }
            val avatar = FrameLayout(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = token
                    textSize = 44f
                    gravity = Gravity.CENTER
                    background = circle(0x22FFFFFF, Color.TRANSPARENT, 0)
                }, FrameLayout.LayoutParams(dp(96), dp(96)))
                addView(colorDot, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END))
            }
            val nameField = EditText(this).apply {
                hint = "Nombre del jugador"
                setHintTextColor(0x80FFFFFF.toInt())
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                maxLines = 1
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                background = panelBg(0x22FFFFFF)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            fun label(t: String) = TextView(this).apply {
                text = t
                textSize = 12f
                alpha = 0.7f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(avatar, LinearLayout.LayoutParams(dp(96), dp(96)))
                addView(nameField, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
                addView(label("Elige un color y acerca la tarjeta NFC"), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
                addView(colorPicker(colorHex, taken, onDark = true) {
                    colorHex = it
                    colorDot.background = circle(Color.parseColor(it), getColor(R.color.ink), dp(3))
                })
                addView(MaterialButton(this@MainActivity).apply {
                    text = "Escanear tarjeta"
                    isAllCaps = false
                    setIconResource(R.drawable.ic_contactless)
                    iconTint = ColorStateList.valueOf(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.action))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        val name = nameField.text?.toString()?.trim().orEmpty()
                        if (name.isBlank()) {
                            showToast("Ingresa un nombre válido")
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        pendingAction = PendingAction.AddPlayer(name, colorHex)
                        setNfcStatus("Registrando", "Acerca la tarjeta NFC de $name")
                    }
                }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
                addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Cancelar"
                    isAllCaps = false
                    strokeWidth = 0
                    setTextColor(Color.WHITE)
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(match, wrap))
            }
        }
    }

    // Tax: the banker types the amount, then the terminal waits for the card
    private fun chargeTax(taxSpace: Property) {
        terminal.showEntry(taxSpace.name, "Ingresa el impuesto a pagar", "Cobrar") { amount ->
            awaitTag(PendingAction.Charge(amount, taxSpace.position), taxSpace.name, "Acerca la tarjeta del jugador para pagar", amount)
        }
    }

    // Utility rent depends on the dice roll, so the banker types the amount on the terminal keypad
    private fun showUtilityRentDialog(property: Property, owner: Player?) {
        if (owner == null) return
        val ownedUtilities = property.ownerCount(properties)
        val multiplier = if (ownedUtilities >= 2) 10 else 4
        terminal.showEntry(
            "Alquiler de ${property.name}",
            "Dados × $multiplier (${owner.name} tiene $ownedUtilities servicio${if (ownedUtilities > 1) "s" else ""})",
            "Cobrar"
        ) { amount -> startRentCharge(property, owner, amount) }
    }

    private fun startRentCharge(property: Property, owner: Player?, amount: Int) {
        awaitTag(
            PendingAction.PayRent(property.id, amount),
            "Alquiler de ${property.name}", "Acerca la tarjeta del inquilino para pagar $$amount a ${owner?.name}", amount
        )
    }

    private fun showPropertyOptions(property: Property) {
        val owner = players.firstOrNull { it.id == property.ownerId }

        if (property.spaceType == SpaceType.GO) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Casilla: Salida (GO)")
                .setMessage("Cobra $200 de salario al pasar por la Salida.")
                .setPositiveButton("Cobrar $200 con NFC") { _, _ ->
                    startTerminalAction(PaymentTerminal.Kind.PASS_GO, PaymentTerminal.GO_AMOUNT)
                }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }

        if (property.spaceType == SpaceType.TAX) {
            chargeTax(property)
            return
        }

        if (!property.isPurchasable) {
            MaterialAlertDialogBuilder(this)
                .setTitle(property.name)
                .setMessage("Casilla especial del tablero: ${property.spaceType}")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        val ink = getColor(R.color.ink)
        val green = Color.parseColor("#2E7D32")
        val red = getColor(R.color.close_red)
        val rows = mutableListOf<List<CardAction>>()

        if (owner == null) {
            // Properties can't be bought or assigned while the game is in preparation
            if (gameStarted) {
                rows += listOf(CardAction("Comprar con NFC  $${property.price}", R.drawable.ic_contactless, green) {
                    awaitTag(PendingAction.BuyProperty(property.id), "Comprar ${property.name}", "Acerca la tarjeta NFC del comprador", property.price)
                })
                rows += listOf(CardAction("Asignar a un jugador", R.drawable.ic_person_add, ink) { showAssignPropertyDialog(property) })
            }
        } else {
            val rent = property.getCurrentRent(properties)
            if (property.spaceType == SpaceType.UTILITY) {
                if (!property.isMortgaged) rows += listOf(CardAction("Cobrar alquiler (dados)", R.drawable.ic_contactless, ink) {
                    showUtilityRentDialog(property, owner)
                })
            } else if (rent > 0) {
                rows += listOf(CardAction("Cobrar alquiler  $$rent", R.drawable.ic_contactless, ink) {
                    startRentCharge(property, owner, rent)
                })
            }
            // Build and sell share a row: green house to build, red house to sell
            val buildRow = mutableListOf<CardAction>()
            if (!property.hasHotel && property.houseCost > 0 && !property.isMortgaged && property.ownerHasFullGroup(properties)) {
                buildRow += if (property.houses < 4) {
                    CardAction("Construir casa\n$${property.houseCost}", R.drawable.ic_house, green) { buildHouse(property, owner) }
                } else {
                    CardAction("Construir hotel\n$${property.houseCost}", R.drawable.ic_hotel, green) { buildHotel(property, owner) }
                }
            }
            if (property.houses > 0 || property.hasHotel) {
                buildRow += CardAction("Vender\n+$${property.houseCost / 2}", R.drawable.ic_sell, red) { sellBuilding(property, owner) }
            }
            if (buildRow.isNotEmpty()) rows += buildRow
            rows += listOf(
                if (property.isMortgaged) CardAction("Deshipotecar  $${(property.mortgageValue * 1.1).toInt()}", R.drawable.ic_mortgage, ink) {
                    unmortgageProperty(property, owner)
                } else CardAction("Hipotecar  +$${property.mortgageValue}", R.drawable.ic_mortgage, ink) { mortgageProperty(property, owner) }
            )
            rows += listOf(
                CardAction("Transferir", R.drawable.ic_transfer, ink) { showTransferPropertyDialog(property) },
                CardAction("Al banco", R.drawable.ic_bank, ink) { returnPropertyToBank(property) }
            )
        }

        // Rent table, plus which row is being charged now (same rules as Property.getCurrentRent)
        val owned = property.ownerCount(properties)
        val (rentRows, currentRow) = when (property.spaceType) {
            SpaceType.UTILITY -> listOf(
                RentRow("1 servicio", "dados × 4"), RentRow("2 servicios", "dados × 10")
            ) to owned - 1
            SpaceType.RAILROAD -> (listOf(property.baseRent) + property.houseRent.take(3))
                .mapIndexed { i, rent -> RentRow("${i + 1} ferrocarril${if (i > 0) "es" else ""}", "$$rent") } to owned - 1
            else -> {
                val rows = listOf(
                    RentRow("Alquiler", "$${property.baseRent}"),
                    RentRow("Grupo completo", "$${property.baseRent * 2}")
                ) + property.houseRent.take(4).mapIndexed { i, rent -> RentRow("", "$$rent", houses = i + 1) } +
                    RentRow("", "$${property.houseRent.getOrElse(4) { 0 }}", hotel = true)
                val current = when {
                    property.hasHotel -> rows.lastIndex
                    property.houses > 0 -> property.houses + 1
                    property.ownerHasFullGroup(properties) -> 1
                    else -> 0
                }
                rows to current
            }
        }
        val details = deedBody(property, rentRows, if (owner != null && !property.isMortgaged) currentRow else -1)
        val deedColor = try {
            Color.parseColor(property.customColorHex ?: property.group.colorHex)
        } catch (_: Exception) {
            Color.GRAY
        }
        val deedTextColor = if (property.group.isDarkText) getColor(R.color.ink) else Color.WHITE

        showCardDialog(cardHeader("TÍTULO DE PROPIEDAD", property.name, deedColor, deedTextColor, details, ownerBadge(owner)), rows)
    }


    private fun showAssignPropertyDialog(property: Property) {
        if (!gameStarted) {
            showToast("Inicia el juego para comprar propiedades")
            return
        }
        if (players.isEmpty()) {
            showToast("Primero registra jugadores")
            return
        }
        val names = players.map { "${it.tokenEmoji} ${it.name}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Comprar / Asignar ${property.name}")
            .setItems(names) { _, which ->
                val player = players[which]
                if (!ensureFunds(player, property.price)) return@setItems
                val updatedPlayer = player.copy(balance = player.balance - property.price, boardPosition = property.position)
                val updatedProperty = property.copy(ownerId = player.id)
                replacePlayer(updatedPlayer)
                replaceProperty(updatedProperty)
                refreshPlayers()
                refreshBoard()
                showToast("${player.name} obtuvo ${property.name}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTransferPropertyDialog(property: Property) {
        val availablePlayers = players.filter { it.id != property.ownerId }
        if (availablePlayers.isEmpty()) {
            showToast("No hay otros jugadores disponibles")
            return
        }
        val names = availablePlayers.map { "${it.tokenEmoji} ${it.name}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Transferir ${property.name}")
            .setItems(names) { _, which ->
                val newOwner = availablePlayers[which]
                val updatedProperty = property.copy(ownerId = newOwner.id)
                replaceProperty(updatedProperty)
                refreshBoard()
                showToast("${property.name} fue transferida a ${newOwner.name}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun buildHouse(property: Property, owner: Player?) {
        if (owner == null || !property.ownerHasFullGroup(properties)) return
        val cost = property.houseCost
        if (!ensureFunds(owner, cost)) return
        val updatedOwner = owner.copy(balance = owner.balance - cost)
        val updatedProp = property.copy(houses = property.houses + 1)
        replacePlayer(updatedOwner)
        replaceProperty(updatedProp)
        refreshPlayers()
        refreshBoard()
        showBuildConfirmation(R.drawable.ic_house, "Casa construida", property.name, "${owner.name}  -$$cost", updatedProp.houses)
    }

    private fun buildHotel(property: Property, owner: Player?) {
        if (owner == null || !property.ownerHasFullGroup(properties)) return
        val cost = property.houseCost
        if (!ensureFunds(owner, cost)) return
        val updatedOwner = owner.copy(balance = owner.balance - cost)
        val updatedProp = property.copy(houses = 0, hasHotel = true)
        replacePlayer(updatedOwner)
        replaceProperty(updatedProp)
        refreshPlayers()
        refreshBoard()
        showBuildConfirmation(R.drawable.ic_hotel, "¡Hotel construido!", property.name, "${owner.name}  -$$cost", 1)
    }

    // Blocks an operation the payer can't cover: instead of going negative, offers mortgaging or bankruptcy
    private fun ensureFunds(payer: Player, amount: Int, creditor: Player? = null): Boolean {
        if (payer.bankrupt) {
            retry("${payer.name} está en bancarrota")
            return false
        }
        if (payer.balance >= amount) return true
        pendingAction = PendingAction.Idle
        runOnUiThread {
            terminal.close()
            showInsufficientFunds(payer.id, amount, creditor?.id)
        }
        return false
    }

    private fun showInsufficientFunds(payerId: String, amount: Int, creditorId: String?) {
        val payer = players.firstOrNull { it.id == payerId } ?: return
        val creditor = players.firstOrNull { it.id == creditorId }
        val missing = amount - payer.balance
        if (missing <= 0) {
            showToast("${payer.name} ya puede pagar: repite la operación")
            return
        }
        val mortgageable = properties.filter { it.ownerId == payer.id && !it.isMortgaged && it.houses == 0 && !it.hasHotel }
        val hasBuildings = properties.any { it.ownerId == payer.id && (it.houses > 0 || it.hasHotel) }
        gamePanel(null) { dialog ->
            fun label(t: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
                text = t
                textSize = size
                this.alpha = alpha
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            fun button(t: String, fill: Int, onClick: () -> Unit) = MaterialButton(this).apply {
                text = t
                isAllCaps = false
                backgroundTintList = ColorStateList.valueOf(fill)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            val match = LinearLayout.LayoutParams.MATCH_PARENT
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Saldo insuficiente", 20f, bold = true))
                addView(label("${payer.name} necesita $$amount y tiene $${payer.balance}", 14f, alpha = 0.85f),
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
                addView(label("Faltan $$missing", 16f, bold = true), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
                if (mortgageable.isNotEmpty()) {
                    addView(button("Hipotecar propiedades", getColor(R.color.action)) { showMortgagePicker(payer.id, amount, creditor?.id) },
                        LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
                } else if (hasBuildings) {
                    addView(label("Vende edificios para poder hipotecar", 12f, alpha = 0.7f),
                        LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
                }
                addView(button("Declarar bancarrota", getColor(R.color.close_red)) { confirmBankruptcy(payer.id, creditor?.id) },
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
                addView(button("Cancelar", Color.TRANSPARENT) {}, LinearLayout.LayoutParams(match, wrap))
            }
        }
    }

    // Mortgage picker: each row shows what the bank pays; afterwards the shortfall is checked again
    private fun showMortgagePicker(payerId: String, amount: Int, creditorId: String?) {
        val options = properties.filter { it.ownerId == payerId && !it.isMortgaged && it.houses == 0 && !it.hasHotel }
        gamePanel("Hipotecar") { dialog ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                options.forEach { property ->
                    addView(panelRow(null, "${property.name}  +$${property.mortgageValue}", Color.TRANSPARENT, false) {
                        dialog.dismiss()
                        mortgageProperty(property, players.firstOrNull { it.id == payerId })
                        showInsufficientFunds(payerId, amount, creditorId)
                    })
                }
            }
        }
    }

    private fun confirmBankruptcy(playerId: String, creditorId: String?) {
        val player = players.firstOrNull { it.id == playerId } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Declarar bancarrota")
            .setMessage("${player.name} queda fuera del juego y sus propiedades pasan a " +
                (players.firstOrNull { it.id == creditorId }?.name ?: "el Banco") + ". ¿Continuar?")
            .setPositiveButton("Declarar") { _, _ -> declareBankruptcy(playerId, creditorId) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Properties go to the creditor (keeping mortgages) or back to the bank; buildings are removed either way
    private fun declareBankruptcy(playerId: String, creditorId: String?) {
        val player = players.firstOrNull { it.id == playerId } ?: return
        val creditor = players.firstOrNull { it.id == creditorId && it.id != playerId }
        properties.filter { it.ownerId == player.id }.forEach {
            replaceProperty(
                if (creditor != null) it.copy(ownerId = creditor.id, houses = 0, hasHotel = false)
                else it.copy(ownerId = null, houses = 0, hasHotel = false, isMortgaged = false)
            )
        }
        if (creditor != null && player.balance > 0) replacePlayer(creditor.copy(balance = creditor.balance + player.balance))
        replacePlayer(player.copy(balance = 0, bankrupt = true))
        refreshPlayers()
        refreshBoard()
        showToast("${player.name} se declaró en bancarrota")
    }

    // Confirmation after buying a house/hotel: the icons pop in one by one, then it closes by itself
    private fun showBuildConfirmation(icon: Int, title: String, property: String, detail: String, count: Int) {
        gamePanel(null) { dialog ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val icons = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER }
                repeat(count) { i ->
                    icons.addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon)
                        scaleX = 0f
                        scaleY = 0f
                        // The newest house (last) pops in last; earlier ones are already there
                        animate().scaleX(1f).scaleY(1f).setStartDelay(if (i == count - 1) 150L else 0L).setDuration(450)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                    }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
                addView(icons)
                fun line(t: String, size: Float, bold: Boolean, alpha: Float) = TextView(this@MainActivity).apply {
                    text = t
                    textSize = size
                    this.alpha = 0f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                    animate().alpha(alpha).setStartDelay(350).setDuration(300).start()
                }
                addView(line(title, 20f, true, 1f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
                addView(line(property, 15f, false, 0.85f))
                addView(line(detail, 13f, false, 0.6f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 1800)
            }
        }
    }

    private fun sellBuilding(property: Property, owner: Player?) {
        if (owner == null) return
        val refund = property.houseCost / 2
        val updatedOwner = owner.copy(balance = owner.balance + refund)
        val updatedProp = if (property.hasHotel) {
            property.copy(hasHotel = false, houses = 4)
        } else {
            property.copy(houses = (property.houses - 1).coerceAtLeast(0))
        }
        replacePlayer(updatedOwner)
        replaceProperty(updatedProp)
        refreshPlayers()
        refreshBoard()
        showToast("Edificio vendido en ${property.name} (+$${refund})")
    }

    private fun mortgageProperty(property: Property, owner: Player?) {
        if (owner == null) return
        val amount = property.mortgageValue
        val updatedOwner = owner.copy(balance = owner.balance + amount)
        val updatedProp = property.copy(isMortgaged = true)
        replacePlayer(updatedOwner)
        replaceProperty(updatedProp)
        refreshPlayers()
        refreshBoard()
        showToast("${property.name} hipotecada por $${amount}")
    }

    private fun unmortgageProperty(property: Property, owner: Player?) {
        if (owner == null) return
        val cost = (property.mortgageValue * 1.1).toInt()
        if (!ensureFunds(owner, cost)) return
        val updatedOwner = owner.copy(balance = owner.balance - cost)
        val updatedProp = property.copy(isMortgaged = false)
        replacePlayer(updatedOwner)
        replaceProperty(updatedProp)
        refreshPlayers()
        refreshBoard()
        showToast("${property.name} deshipotecada por $${cost}")
    }

    private fun returnPropertyToBank(property: Property) {
        val updatedProp = property.copy(ownerId = null, houses = 0, hasHotel = false, isMortgaged = false)
        replaceProperty(updatedProp)
        refreshBoard()
        showToast("${property.name} devuelta al Banco")
    }

    private enum class Screen { CARDS, LIST, GAME }

    private var screen = Screen.CARDS
    private var browseScreen = Screen.CARDS // last cards/list view, restored when leaving Juego

    private fun setupViewToggle() {
        findViewById<MaterialButton>(R.id.btn_toggle_view).setOnClickListener {
            browseScreen = if (browseScreen == Screen.CARDS) Screen.LIST else Screen.CARDS
            screen = browseScreen
            showScreen()
        }
        findViewById<MaterialButton>(R.id.btn_game).setOnClickListener {
            screen = if (screen == Screen.GAME) browseScreen else Screen.GAME
            showScreen()
        }
        findViewById<MaterialButton>(R.id.btn_filters).setOnClickListener { showFiltersMenu() }
        updateFiltersButton()
        showScreen()
    }

    private fun showScreen() {
        propertyGridView.isVisible = screen == Screen.CARDS
        // In portrait the game screen is just the players and their buttons
        monopolyBoardView.isVisible = !(screen == Screen.GAME &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
        findViewById<View>(R.id.scroll_tab_content).isVisible = screen != Screen.CARDS
        findViewById<View>(R.id.rv_board_properties).isVisible = screen == Screen.LIST
        findViewById<View>(R.id.layout_players).isVisible = screen == Screen.GAME
        findViewById<View>(R.id.btn_filters).isVisible = screen != Screen.GAME
        findViewById<View>(R.id.layout_game_bar).isVisible = screen == Screen.GAME
        // Like the compact/comfortable view button of the FAzE project: shows the icon of the current view
        findViewById<MaterialButton>(R.id.btn_toggle_view).setIconResource(
            if (browseScreen == Screen.CARDS) R.drawable.ic_view_cards else R.drawable.ic_view_list
        )
        findViewById<MaterialButton>(R.id.btn_game).isChecked = screen == Screen.GAME
    }

    // Non-floating translucent window: it covers the whole screen, navigation bar included (a floating Dialog leaves it black)
    private fun fullScreenDialog() = Dialog(this, R.style.Theme_Mono_FullScreenDialog).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    // Full-screen dialogs extend under the system bars; otherwise the navigation bar area shows up as a black strip
    private fun makeEdgeToEdge(dialog: Dialog) {
        val window = dialog.window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Game-style panel centered on screen (dark card like the terminal); tapping outside closes it
    private fun gamePanel(title: String?, light: Boolean = false, build: (Dialog) -> View): Dialog {
        val dialog = fullScreenDialog()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            if (title != null) addView(TextView(this@MainActivity).apply {
                text = title.uppercase()
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (light) getColor(R.color.ink) else Color.WHITE)
            })
            addView(
                ScrollView(this@MainActivity).apply { addView(build(dialog)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = if (title != null) dp(12) else 0 }
            )
        }
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(if (light) R.color.surface else R.color.ink))
            radius = dp(20).toFloat()
            cardElevation = dp(8).toFloat()
            strokeWidth = 0 // no outline
            isClickable = true // swallows taps so only the space around closes the dialog
            addView(body)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()) // the dim: a full-screen window has none of its own
            setOnClickListener { dialog.dismiss() }
            addView(card, FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        makeEdgeToEdge(dialog)
        dialog.show()
        return dialog
    }

    private fun playerColor(player: Player) = runCatching { Color.parseColor(player.colorHex) }.getOrDefault(Color.GRAY)

    private fun panelBg(fill: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), stroke)
    }

    // Pill-shaped option row: icon + label, outlined in `outline`, filled when selected
    private fun panelRow(icon: String?, label: String, outline: Int, selected: Boolean, fill: Int? = null, onClick: () -> Unit) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            // A player row is filled with the player's color (no outline); the selected row shows a check mark
            background = panelBg(fill ?: if (selected) getColor(R.color.accent_mid) else 0x22FFFFFF)
            icon?.let { addView(TextView(this@MainActivity).apply { text = it; textSize = 22f }) }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(fill?.let { textColorOn(it) } ?: Color.WHITE)
                if (icon != null) setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(fill?.let { textColorOn(it) } ?: Color.WHITE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }

    private fun showFiltersMenu() {
        gamePanel("Filtrar propiedades") { dialog ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fun option(label: String, outline: Int, filter: BoardFilter, fill: Int? = null) =
                    addView(panelRow(null, label, outline, currentFilter == filter, fill) {
                        currentFilter = filter
                        updateFiltersButton()
                        refreshBoard()
                        dialog.dismiss()
                    })
                option("Todas", Color.WHITE, BoardFilter.All)
                option("Disponibles", Color.WHITE, BoardFilter.Available)
                players.sortedBy { it.name }.forEach { option(it.name, Color.TRANSPARENT, BoardFilter.Owner(it.id), playerColor(it)) }
            }
        }
    }

    // The filtered player may have been deleted since: fall back to Todas
    private fun ensureFilterValid() {
        val f = currentFilter
        if (f is BoardFilter.Owner && players.none { it.id == f.playerId }) currentFilter = BoardFilter.All
        updateFiltersButton()
    }

    private fun updateFiltersButton() {
        val label = when (val f = currentFilter) {
            BoardFilter.All -> "Todas"
            BoardFilter.Available -> "Disponibles"
            is BoardFilter.Owner -> players.firstOrNull { it.id == f.playerId }?.name ?: "Todas"
        }
        findViewById<MaterialButton>(R.id.btn_filters).text = "$label ▾"
    }

    private fun showTerminalDialog() {
        if (!gameStarted) {
            showToast("Inicia el juego para usar la terminal")
            return
        }
        terminal.showEntry("Banco Monopoly", "Terminal de pago · ${players.size} jugadores")
    }

    // Card dialog: the card floats on its own over the dimmed screen, with floating action buttons beside it
    private class CardAction(val label: String, val icon: Int, val tint: Int, val run: () -> Unit)

    // Each row holds one full-width button or two side by side
    private fun showCardDialog(card: View, rows: List<List<CardAction>>) {
        val labels = rows.flatten()
        val density = resources.displayMetrics.density
        val dialog = fullScreenDialog()
        fun actionButton(action: CardAction) = MaterialButton(this).apply {
            text = action.label
            isAllCaps = false
            textSize = 13f
            maxLines = 2
            setIconResource(action.icon)
            iconTint = ColorStateList.valueOf(action.tint)
            iconSize = (20 * density).toInt()
            iconPadding = (8 * density).toInt()
            setPadding((10 * density).toInt(), paddingTop, (10 * density).toInt(), paddingBottom)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_soft))
            setTextColor(getColor(R.color.ink))
            cornerRadius = (12 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                action.run()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEach { row ->
                addView(LinearLayout(this@MainActivity).apply {
                    row.forEachIndexed { i, action ->
                        addView(actionButton(action), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (i > 0) marginStart = (8 * density).toInt()
                        })
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (4 * density).toInt()
                })
            }
        }
        // Right column: the actions scroll when they don't fit the landscape height
        val actionsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // swallows taps so only the empty space around closes the dialog
            addView(ScrollView(this@MainActivity).apply { addView(buttons) })
        }
        card.isClickable = true
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val content = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false // lets the owner badge stick out past the card's right edge
            // The card scrolls when it is taller than the screen (landscape)
            addView(
                ScrollView(this@MainActivity).apply {
                    clipChildren = false
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(card)
                },
                LinearLayout.LayoutParams((280 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            // No actions (e.g. buying disabled in preparation): just the centered card
            if (labels.isNotEmpty()) addView(
                actionsColumn,
                LinearLayout.LayoutParams(
                    (if (portrait) 280 else 240).times(density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (portrait) topMargin = (16 * density).toInt() else marginStart = (16 * density).toInt()
                }
            )
        }
        // Full-screen root: a tap anywhere that isn't the card or the buttons (including the gap between them) closes it
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()) // the dim: a full-screen window has none of its own
            setOnClickListener { dialog.dismiss() }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        makeEdgeToEdge(dialog)
        dialog.show()
    }

    private class RentRow(val label: String, val amount: String, val houses: Int = 0, val hotel: Boolean = false)

    // Owner tag for the deed's top-right corner: the player's name in their color, or "Disponible" if the bank holds it
    private fun ownerBadge(owner: Player?): View {
        val color = owner?.let { playerColor(it) } ?: getColor(R.color.frost)
        return TextView(this).apply {
            text = owner?.let { "${it.tokenEmoji} ${it.name}" } ?: "Disponible"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(owner?.let { textColorOn(color) } ?: getColor(R.color.text_primary))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(12).toFloat()
            }
        }
    }

    // Deed body: rent table (label/houses left, amount right, current row highlighted) and key figures
    private fun deedBody(property: Property, rows: List<RentRow>, currentRow: Int): View {
        fun text(t: String, size: Float, bold: Boolean = false, color: Int = getColor(R.color.ink), alpha: Float = 1f) =
            TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                this.alpha = alpha
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
        fun icons(res: Int, count: Int) = LinearLayout(this).apply {
            repeat(count) {
                addView(ImageView(this@MainActivity).apply { setImageResource(res) }, LinearLayout.LayoutParams(dp(18), dp(18)))
            }
        }
        val rentTable = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            rows.forEachIndexed { i, row ->
                val current = i == currentRow
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    if (current) background = GradientDrawable().apply {
                        setColor(getColor(R.color.mint))
                        cornerRadius = dp(8).toFloat()
                    }
                    val left: View = when {
                        row.hotel -> icons(R.drawable.ic_hotel, 1)
                        row.houses > 0 -> icons(R.drawable.ic_house, row.houses)
                        else -> text(row.label, 14f, current, alpha = if (current) 1f else 0.75f)
                    }
                    addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(row.amount, 15f, bold = true, alpha = if (current) 1f else 0.85f))
                })
                // Light divider between rows that aren't touching the highlight
                if (i < rows.lastIndex && i != currentRow && i + 1 != currentRow) addView(View(this@MainActivity).apply {
                    setBackgroundColor(0x14000000)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(10); marginEnd = dp(10) })
            }
        }
        fun figure(value: String, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text(value, 16f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(label, 11f, alpha = 0.6f).apply { gravity = Gravity.CENTER })
        }
        val figures = LinearLayout(this).apply {
            setPadding(0, dp(12), 0, dp(12))
            val items = buildList {
                add("$${property.price}" to "Precio")
                add("$${property.mortgageValue}" to "Hipoteca")
                if (property.spaceType == SpaceType.PROPERTY) add("$${property.houseCost}" to "Casa / hotel")
            }
            items.forEach { (v, l) -> addView(figure(v, l), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        val status = listOfNotNull(
            if (property.isMortgaged) "HIPOTECADA" else null
        ).joinToString(" · ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rentTable)
            addView(View(this@MainActivity).apply { setBackgroundColor(0x22000000) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) })
            addView(figures)
            if (status.isNotEmpty()) addView(text(status, 12f, bold = true,
                color = if (property.isMortgaged) getColor(R.color.close_red) else getColor(R.color.ink)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // Board-game card: white card with a colored framed box (like a title deed) plus optional details below
    private fun cardHeader(caption: String, title: String, bgColor: Int, textColor: Int, details: View? = null, badge: View? = null): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val header = TextView(this).apply {
            text = SpannableString("$caption\n$title").apply {
                setSpan(RelativeSizeSpan(0.6f), 0, caption.length, 0)
            }
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 8 * density
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(header)
            if (details != null) addView(details)
        }
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 16 * density
            cardElevation = 0f // the card's shadow is clipped by the ScrollView and shows up as a dark line below it
            addView(body)
        }
        if (badge == null) return card
        // The card clips its children, so the badge lives in a wrapper to be able to stick out past the right edge
        return FrameLayout(this).apply {
            clipChildren = false
            addView(card)
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(6), -dp(10), 0) })
        }
    }

    private fun refreshBoard() {
        boardAdapter.updatePlayers(players)
        val purchasableOnly = properties.filter { it.isPurchasable }
        val matches: (Property) -> Boolean = when (val f = currentFilter) {
            BoardFilter.All -> { _ -> true }
            BoardFilter.Available -> { p -> p.ownerId == null }
            is BoardFilter.Owner -> { p -> p.ownerId == f.playerId }
        }
        boardAdapter.submitGroupedList(purchasableOnly.filter(matches), resources.getInteger(R.integer.property_columns))
        propertyGridView.setFilter(matches)

        monopolyBoardView.setBoardData(properties, players)
        propertyGridView.setData(properties, players)

        monopolyBoardView.setAvailableCount(purchasableOnly.count { it.ownerId == null })
    }

    private fun showInitialBalanceDialog() {
        terminal.showEntry("Saldo inicial", "Monto con el que inician los jugadores", "Guardar", initialBalance) { amount ->
            initialBalance = amount
            prefs.edit().putInt(KEY_INITIAL_BALANCE, initialBalance).apply()
            updateInitialBalanceText()
            terminal.showSuccess("Saldo inicial: $$amount", emptyList())
        }
    }

    // WhatsApp-style profile: big icon with the color as a small circle at its bottom right, name with a pencil below
    private fun showPlayerOptions(player: Player) {
        gamePanel(null, light = true) { dialog ->
            fun circle(fill: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(strokeWidth, stroke)
            }
            fun edit(onEdit: () -> Unit): () -> Unit = {
                dialog.dismiss()
                onEdit()
            }
            val avatar = FrameLayout(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = player.tokenEmoji
                    textSize = 56f
                    gravity = Gravity.CENTER
                    background = circle(0x14000000, Color.TRANSPARENT, 0)
                    contentDescription = "Cambiar icono"
                    setOnClickListener { edit { showPlayerTokenDialog(player) }() }
                }, FrameLayout.LayoutParams(dp(120), dp(120)))
                addView(View(this@MainActivity).apply {
                    background = circle(playerColor(player), getColor(R.color.surface), dp(3))
                    contentDescription = "Cambiar color"
                    setOnClickListener { edit { showPlayerColorDialog(player) }() }
                }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END))
            }
            val nameRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setOnClickListener { edit { showEditPlayerDialog(player) }() }
                addView(TextView(this@MainActivity).apply {
                    text = player.name
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.ink))
                })
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_edit)
                    setColorFilter(getColor(R.color.ink))
                    contentDescription = "Editar nombre"
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(avatar, LinearLayout.LayoutParams(dp(120), dp(120)))
                addView(nameRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(16) })
                addView(MaterialButton(this@MainActivity).apply {
                    text = "Eliminar"
                    isAllCaps = false
                    setIconResource(R.drawable.ic_trash)
                    iconTint = ColorStateList.valueOf(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.close_red))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        dialog.dismiss()
                        confirmDeletePlayer(player)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(20) })
            }
        }
    }

    // Token picker: the 8 pieces; those used by other players are dimmed and disabled
    private fun showPlayerTokenDialog(player: Player) {
        val taken = players.filter { it.id != player.id }.map { it.tokenEmoji }
        gamePanel("Icono de ${player.name}", light = true) { dialog ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                PlayerStore.PLAYER_TOKENS.chunked(4).forEach { rowTokens ->
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        rowTokens.forEach { token ->
                            val available = token !in taken
                            val current = token == player.tokenEmoji
                            addView(TextView(this@MainActivity).apply {
                                text = token
                                textSize = 28f
                                gravity = Gravity.CENTER
                                alpha = if (available) 1f else 0.25f
                                isEnabled = available
                                background = panelBg(
                                    if (current) getColor(R.color.accent_soft) else 0x14000000,
                                    if (current) getColor(R.color.accent_mid) else Color.TRANSPARENT
                                )
                                setOnClickListener {
                                    replacePlayer(player.copy(tokenEmoji = token))
                                    refreshPlayers()
                                    refreshBoard()
                                    dialog.dismiss()
                                }
                            }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                        }
                    })
                }
            }
        }
    }

    private fun showPlayerColorDialog(player: Player) {
        var colorHex = player.colorHex
        val taken = players.filter { it.id != player.id }.map { it.colorHex }
        MaterialAlertDialogBuilder(this)
            .setTitle("Color de ${player.name}")
            .setView(colorPicker(colorHex, taken) { colorHex = it })
            .setPositiveButton("Guardar") { _, _ ->
                replacePlayer(player.copy(colorHex = colorHex))
                refreshPlayers()
                refreshBoard()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Suggested accent colors plus a "+" that opens the custom picker; colors used by other players are dimmed and disabled
    private fun colorPicker(selected: String, taken: Collection<String>, onDark: Boolean = false, onPick: (String) -> Unit): View {
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val gap = (8 * density).toInt()
        val swatches = mutableListOf<Pair<String, View>>()
        var picked = selected
        var customView: View? = null
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        // 16 suggestions + "+" wrap into a grid: 3 rows in portrait, 2 in landscape
        val row = android.widget.GridLayout(this).apply { columnCount = if (portrait) 6 else 9 }

        fun highlight() = swatches.forEach { (hex, view) ->
            (view.background as GradientDrawable).setStroke(
                if (hex.equals(picked, ignoreCase = true)) (3 * density).toInt() else 0, getColor(if (onDark) R.color.white else R.color.text_primary)
            )
        }
        fun swatch(name: String, hex: String): View {
            val view = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                }
                contentDescription = name
                setOnClickListener {
                    picked = hex
                    onPick(hex)
                    highlight()
                }
            }
            swatches += hex to view
            return view
        }
        fun lp() = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = gap
            bottomMargin = gap // keeps the two rows of the grid apart
        }
        // A color outside the suggested ones takes a single extra slot before the "+"
        fun setCustom(hex: String) {
            customView?.let {
                row.removeView(it)
                swatches.removeAll { (_, v) -> v === it }
            }
            customView = swatch("Personalizado", hex).also { row.addView(it, row.childCount, lp()) }
        }

        // Colors already used by other players are not suggested
        PlayerStore.PLAYER_PALETTE.filter { (_, hex) -> taken.none { it.equals(hex, ignoreCase = true) } }
            .forEach { (name, hex) -> row.addView(swatch(name, hex), lp()) }
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(getColor(if (onDark) R.color.white else R.color.text_primary))
            setPadding(gap, gap, gap, gap)
            contentDescription = "Color personalizado"
            setOnClickListener {
                ColorPickerDialog(this@MainActivity, taken, picked) { hex ->
                    setCustom(hex)
                    picked = hex
                    onPick(hex)
                    highlight()
                }.show()
            }
        }
        row.addView(plus, lp())
        if (PlayerStore.PLAYER_PALETTE.none { it.second.equals(selected, ignoreCase = true) }) {
            runCatching { setCustom(selected) }
        }
        highlight()
        val sidePad = if (onDark) 0 else 24
        return FrameLayout(this).apply {
            setPadding((sidePad * density).toInt(), (16 * density).toInt(), (sidePad * density).toInt(), 0)
            addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun showEditPlayerDialog(player: Player) {
        val inputLayout = TextInputLayout(this)
        inputLayout.hint = "Nombre del jugador"
        val editText = EditText(this)
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        editText.setText(player.name)
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Editar jugador")
            .setView(inputLayout)
            .setPositiveButton("Guardar") { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    showToast("Ingresa un nombre válido")
                    return@setPositiveButton
                }
                val updated = player.copy(name = name)
                replacePlayer(updated)
                refreshPlayers()
                refreshBoard()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeletePlayer(player: Player) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar jugador")
            .setMessage("¿Eliminar el registro de ${player.name}?")
            .setPositiveButton("Eliminar") { _, _ ->
                players.removeAll { it.id == player.id }
                playerStore.savePlayers(players)
                refreshPlayers()
                refreshBoard()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmResetGame() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reiniciar juego")
            .setMessage("¿Restablecer saldos al valor inicial y liberar todas las propiedades al Banco?")
            .setPositiveButton("Reiniciar") { _, _ ->
                resetGame()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Before the game: initial balance picker + setup buttons; during it: just a small green note
    private fun updateGameState() {
        pregameActions.isVisible = !gameStarted
        initialBalanceCard.isVisible = !gameStarted
        findViewById<View>(R.id.tv_initial_balance_label).isVisible = !gameStarted
        statusText.text = if (gameStarted) "● Juego activo" else "Preparación"
        statusText.setTextColor(getColor(if (gameStarted) R.color.action else R.color.slate))
    }

    private fun updateInitialBalanceText() {
        initialBalanceText.text = "$${initialBalance}"
    }

    private fun resetGame() {
        players.replaceAll { it.copy(balance = initialBalance, boardPosition = 0, bankrupt = false) }
        playerStore.savePlayers(players)

        propertyStore.resetProperties()
        properties.clear()
        properties.addAll(propertyStore.loadProperties())

        refreshPlayers()
        refreshBoard()

        gameStarted = false
        updateGameState()
        setNfcStatus("Listo", "Listo para escanear tarjetas")
        pendingAction = PendingAction.Idle
    }

    private fun refreshPlayers() {
        playerAdapter.submitList(players.sortedBy { it.name })
        ensureFilterValid()
        emptyPlayersText.isVisible = players.isEmpty()
    }

    private fun replacePlayer(updated: Player) {
        val index = players.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            players[index] = updated
            playerStore.savePlayers(players)
        }
    }

    private fun replaceProperty(updated: Property) {
        val index = properties.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            properties[index] = updated
            propertyStore.saveProperties(properties)
        }
    }

    private fun findPlayerByTag(tag: Tag, tagId: String): Player? {
        val storedId = readPlayerId(tag)
        return if (storedId != null) {
            players.firstOrNull { it.id == storedId }
        } else {
            players.firstOrNull { it.tagId == tagId }
        }
    }

    private fun setNfcStatus(status: String, hint: String) {
        runOnUiThread {
            monopolyBoardView.setStatus(status, hint)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun tagIdToHex(tag: Tag): String {
        val bytes = tag.id
        return bytes.joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
    }

    private fun readPlayerId(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage ?: return null
            message.records.firstNotNullOfOrNull { record ->
                readTextRecord(record)?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writePlayerIdToTag(tag: Tag, playerId: String): Boolean {
        val textRecord = createTextRecord("$TAG_PREFIX$playerId")
        val message = NdefMessage(arrayOf(textRecord))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) return false
                if (ndef.maxSize < message.toByteArray().size) return false
                ndef.writeNdefMessage(message)
                true
            } catch (_: Exception) {
                false
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            }
        }

        val formatable = NdefFormatable.get(tag) ?: return false
        return try {
            formatable.connect()
            formatable.format(message)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                formatable.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun createTextRecord(text: String): NdefRecord {
        val language = "es"
        val langBytes = language.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = (langBytes.size and 0x3F).toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            NdefRecord.RTD_TEXT,
            ByteArray(0),
            payload
        )
    }

    private fun readTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload ?: return null
        if (payload.isEmpty()) return null
        val languageLength = payload[0].toInt() and 0x3F
        val textStart = 1 + languageLength
        if (textStart >= payload.size) return null
        return try {
            String(payload, textStart, payload.size - textStart, Charset.forName("UTF-8"))
        } catch (_: Exception) {
            null
        }
    }

    private sealed interface BoardFilter {
        data object All : BoardFilter
        data object Available : BoardFilter
        data class Owner(val playerId: String) : BoardFilter
    }

    private sealed interface PendingAction {
        data object Idle : PendingAction
        data class AddPlayer(val name: String, val colorHex: String) : PendingAction
        data class Charge(val amount: Int, val position: Int? = null) : PendingAction
        data class Deposit(val amount: Int) : PendingAction
        data class TransferFromAwaitTag(val amount: Int) : PendingAction
        data class TransferToAwaitTag(val fromId: String, val amount: Int) : PendingAction
        data object PassGoAwaitTag : PendingAction
        data class BuyProperty(val propertyId: String) : PendingAction
        data class PayRent(val propertyId: String, val amount: Int) : PendingAction
    }

    companion object {
        private const val STATE_GAME_STARTED = "game_started"
        private const val STATE_SCREEN = "screen"
        private const val STATE_BROWSE_SCREEN = "browse_screen"
        private const val STARTING_BALANCE = 1500
        private const val TAG_PREFIX = "mono:"
        private const val KEY_INITIAL_BALANCE = "initial_balance"
    }
}
