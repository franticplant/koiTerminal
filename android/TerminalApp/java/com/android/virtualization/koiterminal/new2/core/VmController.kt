/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.koiterminal.new2.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.os.StatFs
import android.os.SystemProperties
import android.system.virtualizationcommon.IGuestAgent
import android.system.virtualizationservice.DisplayConfig
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineException
import android.system.virtualmachine.VirtualMachineManager
import android.util.Log
import com.android.virtualization.debian.aidl.IDebianService
import com.android.virtualization.debian.aidl.IkoiService
import com.android.virtualization.koiterminal.AndroidToVmBridge
import com.android.virtualization.koiterminal.CertificateUtils
import com.android.virtualization.koiterminal.ConfigJson
import com.android.virtualization.koiterminal.GraphicsManager
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.InstalledImage.Companion.roundUp
import com.android.virtualization.koiterminal.NoLogger
import com.android.virtualization.koiterminal.TerminalThreadFactory
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.util.LoggingMutableStateFlow
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.android.virtualization.koiterminal.SerialConsole
import com.android.virtualization.koiterminal.SerialIOManager

object VmController {
    private val TAG = "VmController"

    private lateinit var context: Context
    private var serialIO: SerialIOManager? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    @Volatile private var shuttingDown = false
    private val _vmState = LoggingMutableStateFlow<VmState>(MutableStateFlow(VmState.Ready), TAG)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    private val _sessionDiscarded = MutableSharedFlow<String>()
    val sessionDiscarded: SharedFlow<String> = _sessionDiscarded.asSharedFlow()

    private val _guestAgentController = MutableStateFlow<GuestAgentController?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val ports: StateFlow<List<OpenPort>> =
        _guestAgentController
            .flatMapLatest { controller ->
                // If controller is null, emit a flow containing null
                controller?.ports ?: flowOf(emptyList())
            }
            .stateIn(
                scope = repositoryScope,
                // The dev API reads .value without subscribing; keep the
                // controller's port snapshot current for headless callers too.
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    var virtualMachine: VirtualMachine? = null
        private set

    private var vmConfig: VirtualMachineConfig? = null
        private set

    private var vmConfigJson: ConfigJson? = null
        private set

    /**
     * A one-shot boot recipe from the dev flavour. Production never passes one: the normal path
     * stays `InstalledImage.getDefault()` + its `vm_config.json`.
     */
    internal data class BootOverride(
        val source: String,
        val profile: String?,
        val config: ConfigJson,
        val agentInstalled: Boolean,
        val useInstalledDisks: Boolean,
        val memoryMib: Int?,
    )

    private val _bootReport = MutableStateFlow<JSONObject?>(null)

    /** The last start's boot source and which steps were applied or skipped. */
    val bootReport: JSONObject?
        get() = _bootReport.value

    @Volatile private var runningProfileValue: String? = null

    /** The profile the current VM was started from, or null for the installed image. */
    val runningProfile: String?
        get() = runningProfileValue

    @Volatile private var lastProfileValue: String? = null

    /** The profile the last start used, even after it stopped, or null for the installed image. */
    val lastProfile: String?
        get() = lastProfileValue

    private val _lastExit = MutableStateFlow<JSONObject?>(null)

    /** The last stop or error, with its reason, uptime and console tail. */
    val lastExit: JSONObject?
        get() = _lastExit.value

    private val exits = ArrayDeque<JSONObject>()
    @Volatile private var startedMs = 0L

    /** The last [max] stops/errors, oldest first. */
    fun exitHistory(max: Int): JSONArray = synchronized(exits) {
        JSONArray().apply { exits.takeLast(max.coerceAtLeast(1)).forEach { put(it) } }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        val key = CertificateUtils.createOrGetKey()
        CertificateUtils.writeCertificateToFile(this.context, key.certificate)
    }

    fun reset() {
        if (
            _vmState.value == VmState.Stopped ||
                _vmState.value is VmState.Error ||
                _vmState.value == VmState.Rebooting
        ) {
            _vmState.value = VmState.Ready
        }
    }

    fun enablePortForwarding(port: Int, enable: Boolean): Boolean {
        val controller = _guestAgentController.value ?: return false
        controller.enablePortForwarding(port, enable)
        return true
    }

    /** Whether the guest agent is up and can take shutdown/port calls. */
    val guestAgentReady: Boolean
        get() = _guestAgentController.value?.ready == true

    /** Whether the koi guest service is up for tty resize calls. */
    val koiServiceReady: Boolean
        get() = _guestAgentController.value?.koiReady == true

    val graphicsAccelerationType: GraphicsManager.AccelerationType
        get() = GraphicsManager.getInstance(context).accelerationType

    val isGraphicsAccelerationSupported: Boolean
        get() = GraphicsManager.getInstance(context).isGfxstreamSupported

    fun setGraphicsAccelerationType(type: GraphicsManager.AccelerationType) {
        GraphicsManager.getInstance(context).accelerationType = type
    }

    fun shutdownVm(): Boolean = _guestAgentController.value?.shutdownVm() ?: false

    fun pullClipboardFromGuest() {
        _guestAgentController.value?.pullClipboardFromGuest()
    }

    fun pushClipboardToGuest() {
        _guestAgentController.value?.pushClipboardToGuest()
    }

    fun requestSessionDiscard(sessionId: String) {
        repositoryScope.launch { _sessionDiscarded.emit(sessionId) }
    }

    fun resizeTtyWindow(columns: Int, rows: Int): Boolean {
        val controller = _guestAgentController.value ?: return false
        val target = vmConfigJson?.getConsoleInputDevice() ?: ConfigJson.DEFAULT_CONSOLE_INPUT_DEVICE
        val targetEnum = when (target) {
            "hvc0" -> IkoiService.TTY_HVC0
            "ttyS0" -> IkoiService.TTY_TTYS0
            else -> IkoiService.TTY_HVC0
        }
        return controller.resizeTtyWindow(targetEnum, columns, rows)
    }

    // It should add and then remove a display to reflect the change.
    fun resizeDisplay(width: Int, height: Int, dpi: Int, refreshRate: Int) {
        val vm = virtualMachine ?: return
        try {
            val displays = vm.getDisplays()
            if (displays.isNotEmpty()) {
                val oldDisplay = displays[0]
                if (
                    oldDisplay.config.width == width &&
                        oldDisplay.config.height == height &&
                        oldDisplay.config.refreshRate == refreshRate
                ) {
                    return
                }
            }
            val config = DisplayConfig()
            config.width = width
            config.height = height
            config.horizontalDpi = dpi
            config.verticalDpi = dpi
            config.refreshRate = refreshRate
            vm.addDisplay(config)
            if (displays.isNotEmpty()) {
                val oldDisplay = displays[0]
                vm.removeDisplay(oldDisplay.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize display", e)
        }
    }

    @Synchronized
    internal fun start(boot: BootOverride? = null) {
        // Stopping and Rebooting are alive too: a start during shutdown would
        // race the stop that is cancelling/joining the previous startup. The
        // separate flag covers the window before the state becomes Stopping.
        if (shuttingDown || _vmState.value.isAlive) return

        val steps =
            BootPlan.steps(
                fromProfile = boot != null,
                agentInstalled = boot?.agentInstalled ?: true,
                useInstalledDisks = boot?.useInstalledDisks ?: true,
                profileMemory = boot?.memoryMib != null,
            )
        _bootReport.value =
            JSONObject()
                .put("source", boot?.source ?: "installed")
                .put("applied", JSONArray(steps.first))
                .put("skipped", JSONArray(steps.second))
        runningProfileValue = boot?.profile
        lastProfileValue = boot?.profile
        startedMs = System.currentTimeMillis()

        // Publish the transition before any asynchronous work, so a second
        // caller cannot pass the state check while this one is starting.
        _vmState.value = VmState.Starting
        try {
            context.startForegroundService(Intent(context, VmService::class.java))
        } catch (e: RuntimeException) {
            _vmState.value = VmState.Error(e)
            return
        }
        startJob = repositoryScope.launch {
            try {
                val image = InstalledImage.getDefault(context)
                val json = boot?.config ?: ConfigJson.from(context, image.configPath)
                vmConfigJson = json
                val configBuilder = json.toConfigBuilder(context)
                val useAgent = boot?.agentInstalled ?: true
                Log.d(TAG, "image.isAidlGuestAgent=${image.isAidlGuestAgent()}")
                _guestAgentController.value =
                    if (useAgent) GuestAgentController(context, image.isAidlGuestAgent(), repositoryScope)
                    else null

                val sharedPref =
                    context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                if (boot?.memoryMib == null) {
                    val memoryMib =
                        sharedPref.getInt(
                            SettingsViewModel.KEY_MEMORY_MIB,
                            SettingsViewModel.DEFAULT_MEMORY_MIB,
                        )
                    configBuilder.setMemoryBytes(memoryMib.toLong() * 1024 * 1024)
                }

                val customImageConfigBuilder = json.toCustomImageConfigBuilder(context)

                // Network specified in settings, but if VM config denies it, we treat it as denied
                if (customImageConfigBuilder.build().useNetwork()) {
                    val network: NetworkConnection = SettingsViewModel.networkConnectionPref(sharedPref)
                    if (network != NetworkConnection.FULL) {
                        customImageConfigBuilder.useNetwork(false)
                    }
                }

                // Convert rootfs disk into a sparse file for storage ballooning.
                if (boot == null || boot.useInstalledDisks) {
                    truncateDiskIfNecessary(image)
                    if (image.hasBackup()) {
                        customImageConfigBuilder.addDisk(
                            VirtualMachineCustomImageConfig.Disk.RWDisk(image.backupFile.toString())
                        )
                    }
                }

                if (useAgent) {
                    try {
                        val port = _guestAgentController.value!!.startServer()
                        Log.d(TAG, "gRPC startServer() returns port=$port")
                        customImageConfigBuilder.addParam("debian_server_port=$port")
                    } catch (e: RuntimeException) {
                        // Ignore if failed due to revoked NETWORK permission on GrapheneOS
                        Log.d(TAG, "Guest control server cannot be started: ${e.message}, ${e.cause}")
                        if (e.message?.contains(Regex("""grpc server""")) == false) {
                            throw e
                        }
                    }
                }

                // Override config for Display
                setDisplayConfig(customImageConfigBuilder)
                setGpuConfig(context, customImageConfigBuilder)
                if (boot == null) {
                    customImageConfigBuilder.setAudioConfig(
                        VirtualMachineCustomImageConfig.AudioConfig.Builder()
                            .setUseSpeaker(true)
                            .setUseMicrophone(true)
                            .build()
                    )
                }
                configBuilder.setCustomImageConfig(customImageConfigBuilder.build())

                val config = configBuilder.build()

                val vmm = context.getSystemService(VirtualMachineManager::class.java)!!
                val vmName = config.customImageConfig!!.name!!

                val deduplicate = {
                    try {
                        vmm.get(vmName)?.let { // Clean up existing VM if it's not stopped
                            if (it.status != VirtualMachine.STATUS_STOPPED) {
                                Log.e(TAG, "stopping vm because it is not stopped")
                                it.stop()
                            }
                            // TODO: revisit this to see if we can omit this step.
                            vmm.delete(vmName)
                        }
                    } catch (e: VirtualMachineException) {
                        // Ignore if VM doesn't exist
                    }
                }
                deduplicate()

                val callback =
                    object : VirtualMachineCallback {
                        override fun onPayloadStarted(vm: VirtualMachine) {}

                        override fun onPayloadReady(vm: VirtualMachine) {}

                        override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {}

                        override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                            Log.e(TAG, "VM error: $message ($errorCode)")
                            recordExit("error", errorCode, "ERROR", message)
                            _vmState.value = VmState.Error(RuntimeException("VM error: $message"))
                            runningProfileValue = null
                            serialIO?.flagForShutdown()
                            _guestAgentController.value?.stop()
                        }

                        override fun onStopped(vm: VirtualMachine, reason: Int) {
                            Log.i("VmController", "VM stopped. reason: $reason")
                            val name =
                                when (reason) {
                                    VirtualMachineCallback.STOP_REASON_SHUTDOWN -> "SHUTDOWN"
                                    VirtualMachineCallback.STOP_REASON_KILLED -> "KILLED"
                                    VirtualMachineCallback.STOP_REASON_REBOOT -> "REBOOT"
                                    else -> "UNKNOWN($reason)"
                                }
                            recordExit("stop", reason, name, null)
                            serialIO?.flagForShutdown()
                            if (
                                reason == VirtualMachineCallback.STOP_REASON_SHUTDOWN ||
                                    reason == VirtualMachineCallback.STOP_REASON_KILLED
                            ) {
                                _vmState.value = VmState.Stopped
                                runningProfileValue = null
                            } else if (reason == VirtualMachineCallback.STOP_REASON_REBOOT) {
                                _vmState.value = VmState.Rebooting
                            } else {
                                Log.e("VmController", "VM stopped unexpectedly. reason: $reason")
                                _vmState.value =
                                    VmState.Error(
                                        RuntimeException("VM stopped unexpectedly: $reason")
                                    )
                            }
                            _guestAgentController.value?.stop()
                        }

                        override fun onGuestAgentRegistered(
                            vm: VirtualMachine,
                            guestAgent: IGuestAgent,
                        ) {
                            Log.d(TAG, "Guest agent registered. Imply AIDL connection")

                            var binder: IBinder? = null
                            try {
                                binder = vm.connectToVsockServer(IDebianService.VSOCK_PORT)
                            } catch (e: Exception) {
                                _vmState.value =
                                    VmState.Error(
                                        RuntimeException("Failed to connect to guest agent", e)
                                    )
                                return
                            }
                            val debian_service = IDebianService.Stub.asInterface(binder)

                            var koi_service: IkoiService? = null
                            try {
                                val koi_binder = vm.connectToVsockServer(IkoiService.VSOCK_PORT)
                                koi_service = koi_binder?.let { IkoiService.Stub.asInterface(it) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Guest koiService agent cannot be registered", e)
                            }

                            _guestAgentController.value?.start(guestAgent, debian_service, koi_service)

                            if (SettingsViewModel.shareTimePref(sharedPref)) {
                                _guestAgentController.value?.setTime()
                            }
                            sharedPref.registerOnSharedPreferenceChangeListener(
                                SharedPreferences.OnSharedPreferenceChangeListener { sharedPref, key ->
                                    if (key == SettingsViewModel.KEY_SHARE_TIME && SettingsViewModel.shareTimePref(sharedPref)) {
                                        _guestAgentController.value?.setTime()
                                    }
                                }
                            )

                            Log.d(TAG, "Guest agent ready")
                        }
                    }

                try {
                    val vm = vmm.create(vmName, config)
                    virtualMachine = vm
                    vm.setCallback(Executors.newSingleThreadExecutor(), callback)
                    vm.run()
                    vmConfig = config
                } catch (e: VirtualMachineException) {
                    // Check for denied Network permission (on GrapheneOS)
                    val serviceSpecificException = e.cause
                    if (serviceSpecificException?.message?.contains(Regex("""does not have the android\.permission\.INTERNET permission""")) == true) {
                        // Override network to false
                        customImageConfigBuilder.useNetwork(false)
                        configBuilder.setCustomImageConfig(customImageConfigBuilder.build())
                        val config = configBuilder.build()
                        // try again
                        deduplicate()
                        val vm = vmm.create(vmName, config)
                        virtualMachine = vm
                        vm.setCallback(Executors.newSingleThreadExecutor(), callback)
                        vm.run()
                        vmConfig = config
                    } else {
                        throw e
                    }
                }
                _guestAgentController.value?.setVm(virtualMachine!!)

                // Logger.setup(context, vm, Executors.newSingleThreadExecutor())
                serialIO = NoLogger.setup(context, virtualMachine!!, Executors.newSingleThreadExecutor())
                val outReadingPfd = serialIO?.getSerialOutPfd()
                val inWritingPfd = serialIO?.getSerialInPfd()
                Log.i("VmController", "inWritingPfd=${inWritingPfd?.getFd()} outReadingPfd=${outReadingPfd?.getFd()}")

                if (useAgent && canUseTtydOverVsock()) {
                    Log.i(TAG, "Connect to ttyd using vsock")
                    val bridge = AndroidToVmBridge(virtualMachine!!)
                    val port = bridge.start()
                    if (port == null) {
                        Log.e(TAG, "Failed to start bridge")
                        _vmState.value = VmState.Error(RuntimeException("Failed to start bridge"))
                    } else {
                        Log.d(TAG, "localhost is running with port=" + port)
                        _vmState.value =
                            VmState.Running(outReadingPfd, inWritingPfd, TerminalAddress("localhost", port, bridge.secretKey))
                    }
                } else if (outReadingPfd != null && inWritingPfd != null) {
                    Log.d(TAG, "VM considered running once serial IO is ready: $outReadingPfd, $inWritingPfd")
                    _vmState.value =
                        VmState.Running(outReadingPfd, inWritingPfd, null)
                }

                val timeout = json.getBootTimeoutSecs() ?: 60
                val effectiveTimeout = if (IS_EMULATOR) (timeout * 10) else timeout

                if (useAgent && image.isAidlGuestAgent()) {
                    repositoryScope.launch {
                        delay(TimeUnit.SECONDS.toMillis(effectiveTimeout.toLong()))
                        if (_vmState.value == VmState.Starting) {
                            _vmState.value =
                                VmState.Error(
                                    RuntimeException("Timed out waiting for terminal service")
                                )
                        }
                    }
                } else if (useAgent && !canUseTtydOverVsock()) {
                    startTtydDiscovery(effectiveTimeout.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VM", e)
                recordExit("error", -1, "START_FAILED", e.message)
                _vmState.value = VmState.Error(e)
                runningProfileValue = null
                serialIO?.flagForShutdown()
                _guestAgentController.value?.stop()
            }
        }
    }

    private fun setGpuConfig(context: Context, builder: VirtualMachineCustomImageConfig.Builder) {
        if (GraphicsManager.getInstance(context).isGfxstreamEnabled()) {
            builder.addParam("gfxstream_enabled")
            builder.setGpuConfig(
                VirtualMachineCustomImageConfig.GpuConfig.Builder()
                    .setBackend("gfxstream")
                    .setRendererUseEgl(false)
                    .setRendererUseGles(false)
                    .setRendererUseGlx(false)
                    .setRendererUseSurfaceless(true)
                    .setRendererUseVulkan(true)
                    .setContextTypes(arrayOf<String>("gfxstream-vulkan", "gfxstream-composer"))
                    // TODO(b/492372616): check if we need to set these features for kernel 6.12
                    .setRendererFeatures(
                        "VulkanDisableCoherentMemoryAndEmulate:enabled;VulkanAllocateHostVisibleAsUdmabuf:enabled;ExternalBlob:enabled"
                    )
                    .build()
            )
        }
    }

    private fun setDisplayConfig(builder: VirtualMachineCustomImageConfig.Builder) {
        // Set a placeholder display config to prevent gfxstream from crashing when it's enabled.
        // It will be replaced with the actual resolution once the surface is created.
        builder
            .setDisplayConfig(
                VirtualMachineCustomImageConfig.DisplayConfig.Builder()
                    .setWidth(1280)
                    .setHeight(720)
                    .setHorizontalDpi(160)
                    .setVerticalDpi(160)
                    .setRefreshRate(60)
                    .build()
            )
            .useKeyboard(true)
            .useMouse(true)
            .useTouch(true)
            .useTrackpad(true)
    }

    private fun startTtydDiscovery(timeoutSecs: Long) {
        check(!canUseTtydOverVsock()) {
            "startTtydDiscovery should only be called when ttyd over http is used"
        }
        val executor =
            Executors.newSingleThreadExecutor(TerminalThreadFactory(context.applicationContext))
        val nsdManager = context.getSystemService<NsdManager>(NsdManager::class.java)!!
        val queryInfo = NsdServiceInfo()
        queryInfo.serviceType = "_http._tcp"
        queryInfo.serviceName = "ttyd"

        var isDiscovered = false

        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                override fun onServiceInfoCallbackUnregistered() {
                    executor.shutdown()
                }

                override fun onServiceLost() {}

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val hasUsableAddress = info.hostAddresses.any { !it.isLinkLocalAddress }

                    if (!hasUsableAddress) {
                        return
                    }

                    if (!isDiscovered) {
                        isDiscovered = true
                        try {
                            nsdManager.unregisterServiceInfoCallback(this)
                        } catch (e: IllegalArgumentException) {
                            // Ignore if already unregistered
                        }
                        val ipAddress =
                            info.hostAddresses
                                .firstOrNull { !it.isLinkLocalAddress }!!
                                .hostAddress!!
                        val port = info.port

                        when (val it = _vmState.value) {
                            is VmState.Running -> {
                                _vmState.value = VmState.Running(it.outReadingPfd, it.inWritingPfd, TerminalAddress(ipAddress, port))
                            }
                            else -> {
                                _vmState.value = VmState.Running(null, null, TerminalAddress(ipAddress, port))
                            }
                        }
                    }
                }
            }

        nsdManager.registerServiceInfoCallback(queryInfo, executor, callback)

        repositoryScope.launch {
            delay(TimeUnit.SECONDS.toMillis(timeoutSecs))
            if (!isDiscovered) {
                try {
                    nsdManager.unregisterServiceInfoCallback(callback)
                } catch (e: IllegalArgumentException) {
                    // Ignore if already unregistered
                }
            }
        }
    }

    private fun canUseTtydOverVsock(): Boolean {
        val buildId = InstalledImage.getDefault(context).buildInfo?.buildId ?: 0
        val FIRST_VERSION_SUPPORTS_TTYD_VSOCK = 5106
        return buildId >= FIRST_VERSION_SUPPORTS_TTYD_VSOCK
    }

    @Synchronized
    fun stop() {
        if (shuttingDown || _vmState.value == VmState.Stopped || _vmState.value == VmState.Stopping)
            return

        // Publish the transition synchronously; the stop itself is asynchronous.
        shuttingDown = true
        _vmState.value = VmState.Stopping
        val startup = startJob
        startJob = null
        repositoryScope.launch {
            try {
                // A start that is still booting must finish cancelling before we
                // stop and publish Stopped, or it can resume and replace the state.
                startup?.cancelAndJoin()
                try {
                    virtualMachine?.stop()
                } catch (e: VirtualMachineException) {
                    Log.w("VmController", "Failed to stop VM", e)
                }
                vmConfig = null
                vmConfigJson = null
            } finally {
                // Publish Stopped before reopening admission, as one critical
                // section shared with start/stop.
                synchronized(this@VmController) {
                    _vmState.value = VmState.Stopped
                    runningProfileValue = null
                    shuttingDown = false
                }
            }
        }
    }

    private fun calculateSparseDiskSize(): Long {
        // Create a sparse file with 95% of the total size for storage ballooning.
        val statFs = StatFs(context.filesDir.absolutePath)
        val hostSize = statFs.totalBytes
        return roundUp(hostSize * GUEST_SPARSE_DISK_SIZE_PERCENTAGE / 100)
    }

    private fun truncateDiskIfNecessary(image: InstalledImage) {
        val curSize = image.getApparentSize()
        val physicalSize = image.getPhysicalSize()

        val expectedSize = calculateSparseDiskSize()
        Log.d(
            TAG,
            "rootfs apparent size=$curSize, physical size=$physicalSize, expectedSize=$expectedSize",
        )

        if (curSize != expectedSize) {
            try {
                image.truncate(expectedSize)
            } catch (e: IOException) {
                throw RuntimeException("Failed to truncate a disk", e)
            }
        }
    }

    private fun recordExit(kind: String, reason: Int, name: String, message: String?) {
        val e =
            JSONObject()
                .put("kind", kind)
                .put("reason", reason)
                .put("name", name)
                .put("unixMs", System.currentTimeMillis())
                .put("bootSource", bootReport?.optString("source") ?: "unknown")
                .put("uptimeMs", if (startedMs > 0) System.currentTimeMillis() - startedMs else 0)
                .put("consoleTail", String(SerialConsole.tail(4096), Charsets.UTF_8))
        if (message != null) e.put("message", message)
        _lastExit.value = e
        synchronized(exits) {
            exits.addLast(e)
            while (exits.size > 20) exits.removeFirst()
        }
    }

    private const val GUEST_SPARSE_DISK_SIZE_PERCENTAGE = 95

    private val IS_EMULATOR: Boolean =
        {
            val deviceName = SystemProperties.get("ro.product.vendor.device", "")
            val cuttlefish = deviceName.startsWith("vsoc_")
            val goldfish = deviceName.startsWith("emu64")

            cuttlefish || goldfish
        }()
}
