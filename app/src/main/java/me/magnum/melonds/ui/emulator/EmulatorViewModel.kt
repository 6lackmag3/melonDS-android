package me.magnum.melonds.ui.emulator

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.Maybe
import io.reactivex.Observable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.awaitSingleOrNull
import kotlinx.coroutines.rx2.rxMaybe
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.common.Schedulers
import me.magnum.melonds.common.romprocessors.RomFileProcessorFactory
import me.magnum.melonds.common.runtime.FrameBufferProvider
import me.magnum.melonds.domain.model.BackgroundMode
import me.magnum.melonds.domain.model.Cheat
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.FpsCounterPosition
import me.magnum.melonds.domain.model.LayoutConfiguration
import me.magnum.melonds.domain.model.Orientation
import me.magnum.melonds.domain.model.Rom
import me.magnum.melonds.domain.model.RomInfo
import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.emulator.FirmwareLaunchResult
import me.magnum.melonds.domain.model.emulator.RomLaunchResult
import me.magnum.melonds.domain.model.retroachievements.GameAchievementData
import me.magnum.melonds.domain.model.retroachievements.RAEvent
import me.magnum.melonds.domain.model.retroachievements.RASimpleAchievement
import me.magnum.melonds.domain.repositories.BackgroundRepository
import me.magnum.melonds.domain.repositories.CheatsRepository
import me.magnum.melonds.domain.repositories.LayoutsRepository
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SaveStatesRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.domain.services.EmulatorManager
import me.magnum.melonds.impl.emulator.EmulatorSession
import me.magnum.melonds.ui.emulator.firmware.FirmwarePauseMenuOption
import me.magnum.melonds.ui.emulator.model.EmulatorState
import me.magnum.melonds.ui.emulator.model.EmulatorUiEvent
import me.magnum.melonds.ui.emulator.model.PauseMenu
import me.magnum.melonds.ui.emulator.model.RAIntegrationEvent
import me.magnum.melonds.ui.emulator.model.RuntimeInputLayoutConfiguration
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import me.magnum.melonds.ui.emulator.model.ToastEvent
import me.magnum.melonds.ui.emulator.rewind.model.RewindSaveState
import me.magnum.melonds.ui.emulator.rom.RomPauseMenuOption
import me.magnum.melonds.utils.EventSharedFlow
import me.magnum.rcheevosapi.model.RAAchievement
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class EmulatorViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val romsRepository: RomsRepository,
    private val cheatsRepository: CheatsRepository,
    private val retroAchievementsRepository: RetroAchievementsRepository,
    private val romFileProcessorFactory: RomFileProcessorFactory,
    private val layoutsRepository: LayoutsRepository,
    private val backgroundsRepository: BackgroundRepository,
    private val saveStatesRepository: SaveStatesRepository,
    private val frameBufferProvider: FrameBufferProvider,
    private val emulatorManager: EmulatorManager,
    private val schedulers: Schedulers
) : ViewModel() {

    private val sessionCoroutineScope = EmulatorSessionCoroutineScope()
    private lateinit var session: EmulatorSession

    private val _currentSystemOrientation = MutableStateFlow<Orientation?>(null)

    private val _emulatorState = MutableStateFlow<EmulatorState>(EmulatorState.Uninitialized)
    val emulatorState = _emulatorState.asStateFlow()

    private val _layout = MutableStateFlow<LayoutConfiguration?>(null)

    private val _runtimeLayout = MutableStateFlow<RuntimeInputLayoutConfiguration?>(null)
    val runtimeLayout = _runtimeLayout.asStateFlow()

    private val _runtimeRendererConfiguration = MutableStateFlow<RuntimeRendererConfiguration?>(null)
    val runtimeRendererConfiguration = _runtimeRendererConfiguration.asStateFlow()

    private val _background = MutableStateFlow(RuntimeBackground.None)
    val background = _background.asStateFlow()

    private val _achievementTriggeredEvent = MutableSharedFlow<RAAchievement>(extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.SUSPEND)
    val achievementTriggeredEvent = _achievementTriggeredEvent.asSharedFlow()

    private val _currentFps = MutableStateFlow<Int?>(null)
    val currentFps = _currentFps.asStateFlow()

    private val _toastEvent = EventSharedFlow<ToastEvent>()
    val toastEvent = _toastEvent.asSharedFlow()

    private val _raIntegrationEvent = EventSharedFlow<RAIntegrationEvent>()
    val integrationEvent = _raIntegrationEvent.asSharedFlow()

    private val _uiEvent = EventSharedFlow<EmulatorUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    fun loadRom(rom: Rom) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                launchRom(rom)
            }
        }
    }

    fun loadRom(romUri: Uri) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                val rom = getRomAtUri(romUri).awaitSingleOrNull()
                if (rom != null) {
                    launchRom(rom)
                } else {
                    _emulatorState.value = EmulatorState.RomNotFoundError
                }
            }
        }
    }

    fun loadRom(romPath: String) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                val rom = getRomAtPath(romPath).awaitSingleOrNull()
                if (rom != null) {
                    launchRom(rom)
                } else {
                    _emulatorState.value = EmulatorState.RomNotFoundError
                }
            }
        }
    }

    private suspend fun launchRom(rom: Rom) = coroutineScope {
        startObservingBackground()
        startObservingRuntimeInputLayoutConfiguration()
        startObservingRendererConfiguration()
        startObservingAchievementEvents()
        startObservingLayoutForRom(rom)
        startRetroAchievementsSession(rom)

        val cheats = getRomInfo(rom)?.let { getRomEnabledCheats(it) } ?: emptyList()
        val result = emulatorManager.loadRom(rom, cheats)
        when (result) {
            is RomLaunchResult.LaunchFailedSramProblem,
            is RomLaunchResult.LaunchFailed -> {
                _emulatorState.value = EmulatorState.RomLoadError
            }
            is RomLaunchResult.LaunchSuccessful -> {
                if (!result.isGbaLoadSuccessful) {
                    _toastEvent.tryEmit(ToastEvent.GbaLoadFailed)
                }
                _emulatorState.value = EmulatorState.RunningRom(rom)
                startTrackingFps()
            }
        }
    }

    fun loadFirmware(consoleType: ConsoleType) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingFirmware)
            sessionCoroutineScope.launch {
                startObservingBackground()
                startObservingRuntimeInputLayoutConfiguration()
                startObservingRendererConfiguration()
                startObservingLayoutForFirmware()

                val result = emulatorManager.loadFirmware(consoleType)
                when (result) {
                    is FirmwareLaunchResult.LaunchFailed -> {
                        _emulatorState.value = EmulatorState.FirmwareLoadError(result.reason)
                    }
                    FirmwareLaunchResult.LaunchSuccessful -> {
                        _emulatorState.value = EmulatorState.RunningFirmware(consoleType)
                        startTrackingFps()
                    }
                }
            }
        }
    }

    fun setSystemOrientation(orientation: Orientation) {
        _currentSystemOrientation.value = orientation
    }

    fun onSettingsChanged() {
        val currentState = _emulatorState.value
        sessionCoroutineScope.launch {
            session.updateRetroAchievementsSettings(
                retroAchievementsRepository.isUserAuthenticated(),
                settingsRepository.isRetroAchievementsHardcoreEnabled(),
            )

            when (currentState) {
                is EmulatorState.RunningRom -> emulatorManager.updateRomEmulatorConfiguration(currentState.rom)
                is EmulatorState.RunningFirmware -> emulatorManager.updateFirmwareEmulatorConfiguration(currentState.console)
                else -> {
                    // Do nothing
                }
            }
        }
    }

    fun onCheatsChanged() {
        val rom = (_emulatorState.value as? EmulatorState.RunningRom)?.rom ?: return

        getRomInfo(rom)?.let {
            sessionCoroutineScope.launch {
                val cheats = getRomEnabledCheats(it)
                emulatorManager.updateCheats(cheats)
            }
        }
    }

    fun pauseEmulator(showPauseMenu: Boolean) {
        sessionCoroutineScope.launch {
            emulatorManager.pauseEmulator()
            if (showPauseMenu) {
                val pauseOptions = when (_emulatorState.value) {
                    is EmulatorState.RunningRom -> {
                        RomPauseMenuOption.values().filter {
                            filterRomPauseMenuOption(it)
                        }
                    }
                    is EmulatorState.RunningFirmware -> {
                        FirmwarePauseMenuOption.values().toList()
                    }
                    else -> null
                }

                if (pauseOptions != null) {
                    _uiEvent.emit(EmulatorUiEvent.ShowPauseMenu(PauseMenu(pauseOptions)))
                }
            }
        }
    }

    fun resumeEmulator() {
        sessionCoroutineScope.launch {
            emulatorManager.resumeEmulator()
        }
    }

    fun resetEmulator() {
        if (_emulatorState.value.isRunning()) {
            sessionCoroutineScope.launch {
                emulatorManager.resetEmulator()
                _toastEvent.emit(ToastEvent.ResetFailed)
            }
        }
    }

    fun stopEmulator() {
        emulatorManager.stopEmulator()
        frameBufferProvider.clearFrameBuffer()
    }

    fun onPauseMenuOptionSelected(option: PauseMenuOption) {
        when (option) {
            is RomPauseMenuOption -> {
                when (option) {
                    RomPauseMenuOption.SETTINGS -> _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.SettingsScreen)
                    RomPauseMenuOption.SAVE_STATE -> {
                        (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                            val saveStateSlots = getRomSaveStateSlots(it.rom)
                            _uiEvent.tryEmit(EmulatorUiEvent.ShowRomSaveStates(saveStateSlots, EmulatorUiEvent.ShowRomSaveStates.Reason.SAVING))
                        }
                    }
                    RomPauseMenuOption.LOAD_STATE -> {
                        (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                            val saveStateSlots = getRomSaveStateSlots(it.rom)
                            _uiEvent.tryEmit(EmulatorUiEvent.ShowRomSaveStates(saveStateSlots, EmulatorUiEvent.ShowRomSaveStates.Reason.LOADING))
                        }
                    }
                    RomPauseMenuOption.REWIND -> {
                        sessionCoroutineScope.launch {
                            val rewindWindow = emulatorManager.getRewindWindow()
                            _uiEvent.emit(EmulatorUiEvent.ShowRewindWindow(rewindWindow))
                        }
                    }
                    RomPauseMenuOption.CHEATS -> {
                        (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                            getRomInfo(it.rom)?.let { romInfo ->
                                _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.CheatsScreen(romInfo))
                            }
                        }
                    }
                    RomPauseMenuOption.RESET -> resetEmulator()
                    RomPauseMenuOption.EXIT -> _uiEvent.tryEmit(EmulatorUiEvent.CloseEmulator)
                }
            }
            is FirmwarePauseMenuOption -> {
                when (option) {
                    FirmwarePauseMenuOption.SETTINGS -> _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.SettingsScreen)
                    FirmwarePauseMenuOption.RESET -> resetEmulator()
                    FirmwarePauseMenuOption.EXIT -> _uiEvent.tryEmit(EmulatorUiEvent.CloseEmulator)
                }
            }
        }
    }

    fun onOpenRewind() {
        if (!settingsRepository.isRewindEnabled()) {
            _toastEvent.tryEmit(ToastEvent.RewindNotEnabled)
            return
        }

        if (!session.areSaveStatesAllowed()) {
            _toastEvent.tryEmit(ToastEvent.RewindNotAvailableWhileRAHardcoreModeEnabled)
            return
        }

        sessionCoroutineScope.launch {
            emulatorManager.pauseEmulator()
            val rewindWindow = emulatorManager.getRewindWindow()
            _uiEvent.emit(EmulatorUiEvent.ShowRewindWindow(rewindWindow))
        }
    }

    fun rewindToState(rewindSaveState: RewindSaveState) {
        sessionCoroutineScope.launch {
            emulatorManager.loadRewindState(rewindSaveState)
        }
    }

    fun saveStateToSlot(slot: SaveStateSlot) {
        sessionCoroutineScope.launch(Dispatchers.IO) {
            (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                if (!saveRomState(it.rom, slot)) {
                    _toastEvent.emit(ToastEvent.StateSaveFailed)
                }
                emulatorManager.resumeEmulator()
            }
        }
    }

    fun loadStateFromSlot(slot: SaveStateSlot) {
        if (!slot.exists) {
            _toastEvent.tryEmit(ToastEvent.StateStateDoesNotExist)
        } else {
            sessionCoroutineScope.launch {
                (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                    if (!loadRomState(it.rom, slot)) {
                        _toastEvent.emit(ToastEvent.StateLoadFailed)
                    }
                    emulatorManager.resumeEmulator()
                }
            }
        }
    }

    fun doQuickSave() {
        val currentState = _emulatorState.value
        when (currentState) {
            is EmulatorState.RunningRom -> {
                if (session.areSaveStatesAllowed()) {
                    sessionCoroutineScope.launch {
                        emulatorManager.pauseEmulator()
                        val quickSlot = saveStatesRepository.getRomQuickSaveStateSlot(currentState.rom)
                        if (saveRomState(currentState.rom, quickSlot)) {
                            _toastEvent.emit(ToastEvent.QuickSaveSuccessful)
                        }
                        emulatorManager.resumeEmulator()
                    }
                } else {
                    _toastEvent.tryEmit(ToastEvent.CannotUseSaveStatesWhenRAHardcoreIsEnabled)
                }
            }
            is EmulatorState.RunningFirmware -> {
                _toastEvent.tryEmit(ToastEvent.CannotSaveStateWhenRunningFirmware)
            }
            else -> {
                // Do nothing
            }
        }
    }

    fun doQuickLoad() {
        val currentState = _emulatorState.value
        when (currentState) {
            is EmulatorState.RunningRom -> {
                if (session.areSaveStatesAllowed()) {
                    sessionCoroutineScope.launch {
                        emulatorManager.pauseEmulator()
                        val quickSlot = saveStatesRepository.getRomQuickSaveStateSlot(currentState.rom)
                        if (loadRomState(currentState.rom, quickSlot)) {
                            _toastEvent.emit(ToastEvent.QuickLoadSuccessful)
                        }
                        emulatorManager.resumeEmulator()
                    }
                } else {
                    _toastEvent.tryEmit(ToastEvent.CannotUseSaveStatesWhenRAHardcoreIsEnabled)
                }
            }
            is EmulatorState.RunningFirmware -> {
                _toastEvent.tryEmit(ToastEvent.CannotLoadStateWhenRunningFirmware)
            }
            else -> {
                // Do nothing
            }
        }
    }

    fun deleteSaveStateSlot(slot: SaveStateSlot): List<SaveStateSlot>? {
        return (_emulatorState.value as? EmulatorState.RunningRom)?.let {
            saveStatesRepository.deleteRomSaveState(it.rom, slot)
            getRomSaveStateSlots(it.rom)
        }
    }

    private suspend fun saveRomState(rom: Rom, slot: SaveStateSlot): Boolean {
        val slotUri = saveStatesRepository.getRomSaveStateUri(rom, slot)
        return if (emulatorManager.saveState(slotUri)) {
            val screenshot = frameBufferProvider.getScreenshot()
            saveStatesRepository.setRomSaveStateScreenshot(rom, slot, screenshot)
            true
        } else {
            false
        }
    }

    private suspend fun loadRomState(rom: Rom, slot: SaveStateSlot): Boolean {
        if (!slot.exists) {
            return false
        }

        val slotUri = saveStatesRepository.getRomSaveStateUri(rom, slot)
        return emulatorManager.loadState(slotUri)
    }

    private fun startObservingRuntimeInputLayoutConfiguration() {
        sessionCoroutineScope.launch {
            combine(
                _layout,
                _currentSystemOrientation,
                settingsRepository.showSoftInput(),
                settingsRepository.isTouchHapticFeedbackEnabled(),
                settingsRepository.getSoftInputOpacity(),
            ) { layout, orientation, showSoftInput, isHapticFeedbackEnabled, inputOpacity ->
                if (layout == null || orientation == null) {
                    null
                } else {
                    val layoutToUse = when (orientation) {
                        Orientation.PORTRAIT -> layout.portraitLayout
                        Orientation.LANDSCAPE -> layout.landscapeLayout
                    }

                    val opacity = if (layout.useCustomOpacity) {
                        layout.opacity
                    } else {
                        inputOpacity
                    }

                    RuntimeInputLayoutConfiguration(
                        showSoftInput = showSoftInput,
                        softInputOpacity = opacity,
                        isHapticFeedbackEnabled = isHapticFeedbackEnabled,
                        layoutOrientation = layout.orientation,
                        layout = layoutToUse,
                    )
                }
            }.collect(_runtimeLayout)
        }
    }

    private suspend fun resetEmulatorState(newState: EmulatorState) {
        sessionCoroutineScope.notifyNewSessionStarted()
        startEmulatorSession()
        _currentFps.value = null
        _emulatorState.value = newState
        _background.value = RuntimeBackground.None
        _layout.value = null
    }

    private fun startObservingAchievementEvents() {
        sessionCoroutineScope.launch {
            emulatorManager.observeRetroAchievementEvents().collect {
                when (it) {
                    is RAEvent.OnAchievementPrimed -> { /* TODO: Show primed achievement */ }
                    is RAEvent.OnAchievementUnPrimed -> { /* TODO: Remove primed achievement */ }
                    is RAEvent.OnAchievementTriggered -> onAchievementTriggered(it.achievementId)
                }
            }
        }
    }

    private fun startObservingBackground() {
        sessionCoroutineScope.launch {
            combine(_layout, _currentSystemOrientation, ensureEmulatorIsRunning()) { layout, orientation, _ ->
                if (layout == null || orientation == null) {
                    RuntimeBackground.None
                } else {
                    if (orientation == Orientation.PORTRAIT) {
                        loadBackground(layout.portraitLayout.backgroundId, layout.portraitLayout.backgroundMode)
                    } else {
                        loadBackground(layout.landscapeLayout.backgroundId, layout.landscapeLayout.backgroundMode)
                    }
                }
            }.collect(_background)
        }
    }

    private suspend fun startObservingLayoutForRom(rom: Rom) {
        val romLayoutId = rom.config.layoutId
        val layoutObservable = if (romLayoutId == null) {
            getGlobalLayoutObservable()
        } else {
            // Load and observe ROM layout but switch to global layout if not found
            layoutsRepository.getLayout(romLayoutId)
                    .flatMapObservable {
                        // Continue observing the ROM layout but if the observable completes, this means that it is no
                        // longer available. From that point on, start observing the global layout
                        layoutsRepository.observeLayout(romLayoutId).concatWith(getGlobalLayoutObservable())
                    }
                    .switchIfEmpty(getGlobalLayoutObservable())
        }

        sessionCoroutineScope.launch {
            combine(layoutObservable.subscribeOn(schedulers.backgroundThreadScheduler).asFlow(), ensureEmulatorIsRunning()) { layout, _ ->
                layout
            }.collect(_layout)
        }
    }

    private fun startObservingRendererConfiguration() {
        sessionCoroutineScope.launch {
            settingsRepository.getVideoFiltering().collectLatest {
                _runtimeRendererConfiguration.value = RuntimeRendererConfiguration(it)
            }
        }
    }

    private fun startObservingLayoutForFirmware() {
        _layout.value = null

        sessionCoroutineScope.launch {
            combine(getGlobalLayoutObservable().subscribeOn(schedulers.backgroundThreadScheduler).asFlow(), ensureEmulatorIsRunning()) { layout, _ ->
                layout
            }.collect(_layout)
        }
    }

    private suspend fun loadBackground(backgroundId: UUID?, mode: BackgroundMode): RuntimeBackground {
        return if (backgroundId == null) {
            RuntimeBackground(null, mode)
        } else {
            val message = backgroundsRepository.getBackground(backgroundId)
                    .subscribeOn(schedulers.backgroundThreadScheduler)
                    .materialize()
                    .await()

            RuntimeBackground(message.value, mode)
        }
    }

    private fun getGlobalLayoutObservable(): Observable<LayoutConfiguration> {
        return settingsRepository.observeSelectedLayoutId()
                .startWith(settingsRepository.getSelectedLayoutId())
                .switchMap { layoutId ->
                    layoutsRepository.getLayout(layoutId)
                            .flatMapObservable { layoutsRepository.observeLayout(layoutId).startWith(it) }
                            .switchIfEmpty(layoutsRepository.observeLayout(LayoutConfiguration.DEFAULT_ID))
                }
    }

    private fun getRomInfo(rom: Rom): RomInfo? {
        val fileRomProcessor = romFileProcessorFactory.getFileRomProcessorForDocument(rom.uri)
        return fileRomProcessor?.getRomInfo(rom)
    }

    private fun getRomSaveStateSlots(rom: Rom): List<SaveStateSlot> {
        return saveStatesRepository.getRomSaveStates(rom)
    }

    private fun getRomAtPath(path: String): Maybe<Rom> {
        return rxMaybe {
            romsRepository.getRomAtPath(path)
        }
    }

    private fun getRomAtUri(uri: Uri): Maybe<Rom> {
        return rxMaybe {
            romsRepository.getRomAtUri(uri)
        }
    }

    fun isSustainedPerformanceModeEnabled(): Boolean {
        return settingsRepository.isSustainedPerformanceModeEnabled()
    }

    fun getFpsCounterPosition(): FpsCounterPosition {
        return settingsRepository.getFpsCounterPosition()
    }

    private suspend fun getRomEnabledCheats(romInfo: RomInfo): List<Cheat> {
        if (!settingsRepository.areCheatsEnabled() || !session.areCheatsEnabled()) {
            return emptyList()
        }

        return cheatsRepository.getRomEnabledCheats(romInfo).await()
    }

    private suspend fun getRomAchievementData(rom: Rom): GameAchievementData {
        if (!retroAchievementsRepository.isUserAuthenticated()) {
            return GameAchievementData.withDisabledRetroAchievementsIntegration()
        }

        return retroAchievementsRepository.getGameUserAchievements(rom.retroAchievementsHash, session.isRetroAchievementsHardcoreModeEnabled).fold(
            onSuccess = { achievements ->
                if (achievements.isEmpty()) {
                    GameAchievementData.withDisabledRetroAchievementsIntegration()
                } else {
                    val lockedAchievements = achievements.filter { !it.isUnlocked }.map { RASimpleAchievement(it.achievement.id, it.achievement.memoryAddress) }
                    val gameSummary = retroAchievementsRepository.getGameSummary(rom.retroAchievementsHash)
                    GameAchievementData(
                        isRetroAchievementsIntegrationEnabled = true,
                        lockedAchievements = lockedAchievements,
                        totalAchievementCount = achievements.size,
                        richPresencePatch = gameSummary?.richPresencePatch,
                        icon = gameSummary?.icon,
                    )
                }
            },
            onFailure = { GameAchievementData.withDisabledRetroAchievementsIntegration() }
        )
    }

    private fun onAchievementTriggered(achievementId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            retroAchievementsRepository.getAchievement(achievementId)
                .onSuccess {
                    if (it != null) {
                        _achievementTriggeredEvent.emit(it)
                        retroAchievementsRepository.awardAchievement(it, session.isRetroAchievementsHardcoreModeEnabled)
                    }
                }
        }
    }

    private fun startRetroAchievementsSession(rom: Rom) {
        sessionCoroutineScope.launch {
            val achievementData = getRomAchievementData(rom)
            if (!achievementData.isRetroAchievementsIntegrationEnabled) {
                return@launch
            }

            // Wait until the emulator has actually started
            ensureEmulatorIsRunning().firstOrNull()

            val startResult = retroAchievementsRepository.startSession(rom.retroAchievementsHash)
            if (!startResult.isSuccess) {
                _raIntegrationEvent.tryEmit(RAIntegrationEvent.Failed(achievementData.icon))
                return@launch
            }

            emulatorManager.setupAchievements(achievementData)
            _raIntegrationEvent.tryEmit(
                RAIntegrationEvent.Loaded(
                    icon = achievementData.icon,
                    unlockedAchievements = achievementData.unlockedAchievementCount,
                    totalAchievements = achievementData.totalAchievementCount,
                )
            )
            while (isActive) {
                // TODO: Should we pause the session if the app goes to background? If so, how?
                delay(2.minutes)
                val richPresenceDescription = MelonEmulator.getRichPresenceStatus()
                retroAchievementsRepository.sendSessionHeartbeat(rom.retroAchievementsHash, richPresenceDescription)
            }
        }
    }

    private fun startTrackingFps() {
        sessionCoroutineScope.launch {
            while (isActive) {
                delay(1.seconds)
                _currentFps.value = emulatorManager.getFps()
            }
        }
    }

    private fun filterRomPauseMenuOption(option: RomPauseMenuOption): Boolean {
        return when (option) {
            RomPauseMenuOption.REWIND -> settingsRepository.isRewindEnabled() && session.areSaveStatesAllowed()
            RomPauseMenuOption.SAVE_STATE -> session.areSaveStatesAllowed()
            RomPauseMenuOption.LOAD_STATE -> session.areSaveStatesAllowed()
            RomPauseMenuOption.CHEATS -> session.areCheatsEnabled()
            else -> true
        }
    }

    private fun ensureEmulatorIsRunning(): Flow<Unit> {
        return _emulatorState.filter { it.isRunning() }.take(1).map { }
    }

    private suspend fun startEmulatorSession() {
        val isUserAuthenticatedInRetroAchievements = retroAchievementsRepository.isUserAuthenticated()
        val isRetroAchievementsHardcoreModeEnabled = settingsRepository.isRetroAchievementsHardcoreEnabled()
        session = EmulatorSession(
            isUserAuthenticatedInRetroAchievements,
            isRetroAchievementsHardcoreModeEnabled,
        )
    }

    override fun onCleared() {
        super.onCleared()
        sessionCoroutineScope.cancel()
        if (_emulatorState.value.isRunning()) {
            emulatorManager.stopEmulator()
        }
    }

    private class EmulatorSessionCoroutineScope : CoroutineScope {
        private var currentCoroutineContext: CoroutineContext = EmptyCoroutineContext

        override val coroutineContext: CoroutineContext get() = currentCoroutineContext

        fun notifyNewSessionStarted() {
            cancel()
            currentCoroutineContext = SupervisorJob() + Dispatchers.Main.immediate
        }

        fun cancel() {
            currentCoroutineContext.cancel()
        }
    }
}