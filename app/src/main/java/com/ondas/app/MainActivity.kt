@file:Suppress("DEPRECATION", "MissingPermission")

package com.ondas.app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        const val NOME = "OndasEstacionarias"
        const val REQ_PERM = 1
    }

    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var caracteristica: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var escaneando = false
    private var conectado = false
    private var vel = 0
    private var ultimoEnvio = 0L

    // fila de escritas (BLE não aceita várias escritas simultâneas)
    private val fila = ArrayDeque<String>()
    private var bombeando = false

    // views
    private lateinit var btnConectar: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtVel: TextView
    private lateinit var seek: SeekBar
    private val controles = mutableListOf<View>()

    // ---------------------------------------------------------------- UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun botao(texto: String, peso: Float = 1f, acao: () -> Unit): Button =
        Button(this).apply {
            text = texto
            isAllCaps = false
            setOnClickListener { acao() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
                .also { it.setMargins(dp(3), dp(3), dp(3), dp(3)) }
            controles.add(this)
        }

    private fun linha(vararg bs: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        bs.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        btnConectar = Button(this).apply {
            text = "Conectar à máquina"
            isAllCaps = false
            setOnClickListener { if (conectado || escaneando) desconectar() else iniciarConexao() }
        }
        raiz.addView(btnConectar)

        txtStatus = TextView(this).apply {
            text = "Desconectado"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        raiz.addView(txtStatus)

        txtVel = TextView(this).apply {
            text = "0%"
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        raiz.addView(txtVel)

        seek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val agora = System.currentTimeMillis()
                    val envia = agora - ultimoEnvio > 80
                    if (envia) ultimoEnvio = agora
                    setVel(p, envia)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { setVel(s.progress) }
            })
        }
        controles.add(seek)
        raiz.addView(seek)

        raiz.addView(linha(*listOf(0, 25, 50, 75).map { p -> botao("$p") { setVel(p) } }.toTypedArray()))
        raiz.addView(linha(
            botao("− 1%") { setVel(vel - 1) },
            botao("+ 1%") { setVel(vel + 1) }
        ))
        raiz.addView(linha(
            botao("Sentido ↻") { setVel(0, false); enviar("D1") },
            botao("Sentido ↺") { setVel(0, false); enviar("D0") }
        ))

        val parar = Button(this).apply {
            text = "PARAR MOTOR"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C62828")); cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { setVel(0, false); enviar("S", prioridade = true) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
            ).also { it.topMargin = dp(20) }
        }
        controles.add(parar)
        raiz.addView(parar)

        setContentView(ScrollView(this).apply { addView(raiz) })
        habilitar(false)
    }

    private fun habilitar(on: Boolean) = controles.forEach { it.isEnabled = on }

    private fun status(t: String) = ui.post { txtStatus.text = t }

    // ------------------------------------------------------- velocidade
    private fun setVel(v: Int, envia: Boolean = true) {
        vel = v.coerceIn(0, 100)
        txtVel.text = "$vel%"
        seek.progress = vel
        if (envia) enviar("V$vel")
    }

    // ------------------------------------------------------ permissões
    private fun permissoes(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun temPermissoes() = permissoes().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun iniciarConexao() {
        if (!temPermissoes()) { requestPermissions(permissoes(), REQ_PERM); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status("Ative o Bluetooth e tente de novo"); return }
        scanner = adapter.bluetoothLeScanner
        val filtro = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val cfg = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        escaneando = true
        btnConectar.text = "Cancelar"
        status("Procurando '$NOME'...")
        scanner?.startScan(listOf(filtro), cfg, scanCb)
        ui.postDelayed({
            if (escaneando) { pararScan(); status("Máquina não encontrada"); btnConectar.text = "Conectar à máquina" }
        }, 10_000)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ_PERM) {
            if (temPermissoes()) iniciarConexao() else status("Permissão de Bluetooth negada")
        }
    }

    // ------------------------------------------------------------- BLE
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (!escaneando) return
            pararScan()
            status("Conectando...")
            gatt = r.device.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(code: Int) {
            escaneando = false
            status("Falha no scan (código $code)")
            ui.post { btnConectar.text = "Conectar à máquina" }
        }
    }

    private fun pararScan() {
        if (escaneando) { try { scanner?.stopScan(scanCb) } catch (_: Exception) {} }
        escaneando = false
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, novo: Int) {
            if (novo == BluetoothProfile.STATE_CONNECTED) {
                status("Conectado, descobrindo serviços...")
                g.discoverServices()
            } else {
                g.close()
                gatt = null; caracteristica = null; conectado = false
                fila.clear()
                ui.post {
                    habilitar(false)
                    btnConectar.text = "Conectar à máquina"
                    setVel(0, false)
                }
                status("Desconectado")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val c = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (c == null) { status("Serviço da máquina não encontrado"); g.disconnect(); return }
            caracteristica = c
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            conectado = true
            ui.post { habilitar(true); btnConectar.text = "Desconectar" }
            status("Conectado a $NOME")
        }
    }

    private fun desconectar() {
        if (escaneando) { pararScan(); btnConectar.text = "Conectar à máquina"; status("Desconectado"); return }
        enviar("S", prioridade = true)
        ui.postDelayed({ gatt?.disconnect() }, 150)
    }

    // ------------------------------------------------- fila de escrita
    private fun enviar(cmd: String, prioridade: Boolean = false) {
        if (!conectado) return
        if (prioridade) { fila.clear(); fila.addFirst(cmd) } else {
            // comandos V repetidos: mantém só o último
            if (cmd.startsWith("V")) fila.removeAll { it.startsWith("V") }
            fila.addLast(cmd)
        }
        if (!bombeando) bombear()
    }

    private fun bombear() {
        val cmd = fila.removeFirstOrNull()
        val c = caracteristica
        if (cmd == null || c == null || !conectado) { bombeando = false; return }
        bombeando = true
        c.value = cmd.toByteArray()
        gatt?.writeCharacteristic(c)
        ui.postDelayed({ bombear() }, 35)
    }

    override fun onDestroy() {
        enviar("S", prioridade = true)
        pararScan()
        gatt?.close()
        super.onDestroy()
    }
}
