package com.altradits.kengele.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/** Point-in-time radio state for the status screen. */
data class MeshSnapshot(
    val running: Boolean,
    val advertising: Boolean,
    val scanning: Boolean,
    val links: Int,
    val fragmentsRelayed: Long,
    val datagramsRelayed: Long,
)

/**
 * Process-wide, memory-only radio stats. The radio writes, the status
 * screen reads. No persistence: a reboot or kill wipes the counters.
 */
object MeshBoard {
    @Volatile var running: Boolean = false
    @Volatile var advertising: Boolean = false
    @Volatile var scanning: Boolean = false
    @Volatile var links: Int = 0
    @Volatile var fragmentsRelayed: Long = 0
    @Volatile var scanningSupported: Boolean = true
    @Volatile var datagramsRelayed: Long = 0

    fun snapshot(): MeshSnapshot = MeshSnapshot(
        running = running,
        advertising = advertising,
        scanning = scanning,
        links = links,
        fragmentsRelayed = fragmentsRelayed,
        datagramsRelayed = datagramsRelayed,
    )

    fun resetCounts() {
        fragmentsRelayed = 0
        datagramsRelayed = 0
    }
}

/**
 * GATT dual role in the bitchat model: the app advertises *and* scans,
 * connects both ways, and gossips fragments it has not seen before with
 * TTL-1, up to 7 hops. Every fragment on the air is blind carriage from
 * [BlindNode]: this class moves bytes and never decrypts or branches on
 * kind, body or sender.
 *
 * Transport shape: one GATT service with one fragment characteristic.
 * Each side writes fragments to the peers it connected to as central and
 * notifies the centrals subscribed to its own server, so a single
 * connection carries both directions. MTU discipline: each wire fragment
 * (at most [MeshCarry.FRAGMENT_MTU] bytes) goes as one write/notify, and
 * a peer is only sent fragments that fit its negotiated MTU
 * (fragment + 3 bytes ATT overhead). A 228-byte datagram always travels
 * as 2 fragments.
 *
 * All carry state is memory-only: queues are dropped on stop, and silence
 * (no peers, MTU too small, radio off) fails safe.
 */
class MeshRadio(
    context: Context,
    private val node: BlindNode = BlindNode("self"),
    private val onDatagram: (ByteArray) -> Unit = {},
) {
    companion object {
        /** Fixed service UUID identifying Kengele mesh carriers (made up). */
        val SERVICE_UUID: UUID = UUID.fromString("4b43434b-0001-4000-8000-000000000001")

        /** Fragment characteristic UUID (made up). */
        val FRAG_CHAR_UUID: UUID = UUID.fromString("4b43434b-0002-4000-8000-000000000002")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ENABLE_NOTIFY = byteArrayOf(0x01, 0x00)

        /** Negotiated MTU wanted: one full fragment plus 3 bytes ATT overhead. */
        const val GATT_MTU_WANT = MeshCarry.FRAGMENT_MTU + 3

        /** Cap on simultaneous central (outgoing) connections. */
        const val MAX_CENTRAL_PEERS = 8

        /** Cap on queued fragments per peer; oldest is dropped past this. */
        const val MAX_QUEUE_PER_PEER = 64
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    private var started = false
    private var wantScanning = true
    private var scanningActive = false
    private var advertisingActive = false

    private var gattServer: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null

    private val centralGatts = mutableMapOf<String, BluetoothGatt>()
    private val connecting = mutableSetOf<String>()
    private val centralChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val centralMtu = mutableMapOf<String, Int>()
    private val centralOut = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val writeBusy = mutableSetOf<String>()

    private val serverDevices = mutableMapOf<String, BluetoothDevice>()
    private val serverSubscribed = mutableSetOf<String>()

    private fun manager(): BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)

    private fun adapter(): BluetoothAdapter? = manager()?.adapter

    /** Whether the radio can run: BLE present, enabled and permitted. */
    fun available(): Boolean {
        val adapter = adapter() ?: return false
        if (!adapter.isEnabled) return false
        if (!MeshPermissions.hasAll(appContext)) return false
        return appContext.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE,
        )
    }

    /** Live link count: distinct peers connected as central or peripheral. */
    fun links(): Int = synchronized(lock) {
        (centralGatts.keys + serverDevices.keys).size
    }

    /**
     * Starts advertising, scanning and the GATT server. Returns false when
     * the radio cannot run (Bluetooth off, missing permission, no LE);
     * the caller shows the status screen either way.
     */
    fun start(): Boolean = synchronized(lock) {
        if (started) return true
        if (!available()) return false
        MeshBoard.resetCounts()
        if (!openServerLocked()) return false
        startAdvertisingLocked()
        wantScanning = true
        applyScanLocked()
        started = true
        updateBoardLocked()
        true
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            stopScanLocked()
            stopAdvertisingLocked()
            for ((_, gatt) in centralGatts) guarded { gatt.disconnect() }
            for ((_, gatt) in centralGatts) guarded { gatt.close() }
            centralGatts.clear()
            connecting.clear()
            centralChars.clear()
            centralMtu.clear()
            centralOut.clear()
            writeBusy.clear()
            serverDevices.clear()
            serverSubscribed.clear()
            guarded { gattServer?.close() }
            gattServer = null
            serverChar = null
            advertisingActive = false
            updateBoardLocked()
        }
    }

    /** Duty-cycle hook: the service toggles scanning while advertising continues. */
    fun setScanning(on: Boolean) = synchronized(lock) {
        wantScanning = on
        if (started) applyScanLocked()
    }

    /**
     * Broadcasts one sealed 228-byte datagram to all live links with the
     * dense-crowd TTL rule. The source records it as seen and forwarded so
     * its neighbours' echoes die on arrival.
     */
    fun broadcast(datagram: ByteArray) = synchronized(lock) {
        val ttl = MeshCarry.broadcastTtl(links())
        val msgId = MeshCarry.freshMsgId()
        val frags = MeshCarry.split(datagram, msgId, ttl)
        node.markSeenDatagram(datagram)
        for (i in frags.indices) node.markRelayedFrag(msgId, i)
        for (f in frags) gossipLocked(f)
    }

    // -- incoming path: every received fragment goes through the blind node. --

    private fun handleIncoming(bytes: ByteArray) {
        synchronized(lock) {
            if (!started) return
            val res = try {
                node.receive(bytes)
            } catch (e: MeshCarryException) {
                // Malformed Kengele fragment with nothing to forward, or
                // other traffic: a carrier drops it silently.
                return
            }
            res.relay?.let {
                gossipLocked(it)
                MeshBoard.fragmentsRelayed++
            }
            res.delivery?.let {
                // A delivery implies every fragment was relayed first (relay
                // is computed before reassembly), so deliveries count
                // datagrams relayed. The bytes stay opaque here; the watcher
                // opens them.
                MeshBoard.datagramsRelayed++
                onDatagram(it.datagram)
            }
        }
    }

    private fun gossipLocked(frag: ByteArray) {
        for (addr in centralGatts.keys) {
            val q = centralOut.getOrPut(addr) { ArrayDeque() }
            if (q.size >= MAX_QUEUE_PER_PEER) q.removeFirst()
            q.addLast(frag)
            pumpLocked(addr)
        }
        val server = gattServer
        val char = serverChar
        if (server != null && char != null) {
            for (addr in serverSubscribed.toList()) {
                val device = serverDevices[addr] ?: continue
                guarded { notifyLocked(server, device, char, frag) }
            }
        }
    }

    /** Sends the head of one peer's queue when it fits the negotiated MTU. */
    private fun pumpLocked(addr: String) {
        if (writeBusy.contains(addr)) return
        val gatt = centralGatts[addr] ?: return
        val char = centralChars[addr] ?: return
        val mtu = centralMtu[addr] ?: 23
        val q = centralOut[addr] ?: return
        while (q.isNotEmpty()) {
            val head = q.first()
            // MTU discipline: a fragment that does not fit this peer is
            // dropped for this peer only; the mesh carries it elsewhere.
            if (head.size + 3 > mtu) {
                q.removeFirst()
                continue
            }
            val ok = guarded {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        char, head, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    char.value = head
                    @Suppress("DEPRECATION")
                    if (gatt.writeCharacteristic(char)) {
                        BluetoothGatt.GATT_SUCCESS
                    } else {
                        BluetoothGatt.GATT_FAILURE
                    }
                }
            } ?: BluetoothGatt.GATT_FAILURE
            if (ok == BluetoothGatt.GATT_SUCCESS) writeBusy.add(addr)
            return
        }
    }

    @SuppressLint("MissingPermission") // callers run under guarded{} with permission checks
    private fun notifyLocked(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        frag: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            server.notifyCharacteristicChanged(device, char, false, frag)
        } else {
            @Suppress("DEPRECATION")
            char.value = frag
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, char, false)
        }
    }

    // -- advertising + scanning. --

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = synchronized(lock) {
            advertisingActive = true
            updateBoardLocked()
        }

        override fun onStartFailure(errorCode: Int) = synchronized(lock) {
            advertisingActive = false
            updateBoardLocked()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = guarded { result.device } ?: return
            maybeConnect(device)
        }
    }

    private fun startAdvertisingLocked() {
        val advertiser = guarded { adapter()?.bluetoothLeAdvertiser } ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        guarded { advertiser.startAdvertising(settings, data, advertiseCallback) }
    }

    private fun stopAdvertisingLocked() {
        val advertiser = try {
            adapter()?.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            null
        } ?: return
        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            // Permission revoked mid-run: the leg just ends.
        }
        advertisingActive = false
    }

    private fun applyScanLocked() {
        val want = started && wantScanning
        if (want == scanningActive) {
            updateBoardLocked()
            return
        }
        if (!want) {
            stopScanLocked()
            return
        }
        val scanner = guarded { adapter()?.bluetoothLeScanner }
        if (scanner == null) {
            MeshBoard.scanningSupported = false
            updateBoardLocked()
            return
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val ok = guarded { scanner.startScan(filters, settings, scanCallback) } != null
        scanningActive = ok
        updateBoardLocked()
    }

    private fun stopScanLocked() {
        if (!scanningActive) return
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Permission revoked mid-run: the leg just ends.
        } catch (e: IllegalStateException) {
            // Adapter turned off mid-run.
        }
        scanningActive = false
        updateBoardLocked()
    }

    private fun maybeConnect(device: BluetoothDevice) = synchronized(lock) {
        if (!started) return
        val addr = guarded { device.address } ?: return
        if (centralGatts.containsKey(addr) || connecting.contains(addr)) return
        if (centralGatts.size >= MAX_CENTRAL_PEERS) return
        connecting.add(addr)
        val gatt = guarded {
            if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
        }
        if (gatt == null) connecting.remove(addr)
    }

    // -- central (GATT client) callbacks. --

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                val addr = guarded { gatt.device.address } ?: return
                connecting.remove(addr)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    centralGatts[addr] = gatt
                    guarded { gatt.requestMtu(GATT_MTU_WANT) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    dropCentralLocked(addr)
                }
                updateBoardLocked()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(lock) {
                val addr = guarded { gatt.device.address } ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) centralMtu[addr] = mtu
                guarded { gatt.discoverServices() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(lock) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val addr = guarded { gatt.device.address } ?: return
                val char = guarded {
                    gatt.getService(SERVICE_UUID)?.getCharacteristic(FRAG_CHAR_UUID)
                } ?: return
                centralChars[addr] = char
                // Subscribe to server-side notifies so one connection
                // carries both directions.
                guarded { gatt.setCharacteristicNotification(char, true) }
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    guarded {
                        if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(cccd, ENABLE_NOTIFY)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = ENABLE_NOTIFY
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }
                pumpLocked(addr)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(lock) {
                val addr = guarded { gatt.device.address } ?: return
                writeBusy.remove(addr)
                if (status == BluetoothGatt.GATT_SUCCESS) centralOut[addr]?.removeFirstOrNull()
                pumpLocked(addr)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(value.copyOf())
        }

        @Deprecated("Legacy callback, still delivered on API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = try {
                characteristic.value
            } catch (e: SecurityException) {
                return
            } ?: return
            handleIncoming(value.copyOf())
        }
    }

    private fun dropCentralLocked(addr: String) {
        guarded { centralGatts[addr]?.close() }
        centralGatts.remove(addr)
        centralChars.remove(addr)
        centralMtu.remove(addr)
        centralOut.remove(addr)
        writeBusy.remove(addr)
        connecting.remove(addr)
    }

    // -- peripheral (GATT server) callbacks. --

    private fun openServerLocked(): Boolean {
        val mgr = manager() ?: return false
        val server = guarded { mgr.openGattServer(appContext, serverCallback) } ?: return false
        val char = BluetoothGattCharacteristic(
            FRAG_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        char.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(char)
        val ok = guarded { server.addService(service) } ?: false
        if (!ok) {
            guarded { server.close() }
            return false
        }
        gattServer = server
        serverChar = char
        return true
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            synchronized(lock) {
                val addr = guarded { device.address } ?: return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverDevices[addr] = device
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverDevices.remove(addr)
                    serverSubscribed.remove(addr)
                }
                updateBoardLocked()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val server = synchronized(lock) { gattServer }
            if (responseNeeded && server != null) {
                guarded {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            if (characteristic.uuid == FRAG_CHAR_UUID && offset == 0) {
                handleIncoming(value.copyOf())
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            synchronized(lock) {
                val addr = guarded { device.address }
                if (addr != null && descriptor.uuid == CCCD_UUID) {
                    if (value.contentEquals(ENABLE_NOTIFY)) {
                        serverSubscribed.add(addr)
                    } else {
                        serverSubscribed.remove(addr)
                    }
                }
            }
            val server = synchronized(lock) { gattServer }
            if (responseNeeded && server != null) {
                guarded {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }
    }

    private fun updateBoardLocked() {
        MeshBoard.running = started
        MeshBoard.advertising = advertisingActive
        MeshBoard.scanning = scanningActive
        MeshBoard.links = (centralGatts.keys + serverDevices.keys).size
    }

    /** Runs one BLE call, returning null when the OS refuses it. */
    private inline fun <T> guarded(op: () -> T): T? = try {
        op()
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalStateException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
