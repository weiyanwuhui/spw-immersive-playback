@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)

package local.spw.immersive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.HoverInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Bitmap
import javax.swing.SwingUtilities
import javax.swing.JFrame
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/** Native Compose hit testing and direct key dispatch; no displayed windows or desktop input. */
object PointerShieldSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {

        SwingUtilities.invokeAndWait { checkGlass(); checkInput(false); checkInput(true); checkEscape() }
        println("PASS: page scopes, content visibility, pointer recovery, 20 cycles and Esc collapse.")
    }

    @JvmStatic
    @Composable
    fun controlFixture(content: @Composable () -> Unit) { content() }

    private fun field(plugin: ImmersivePlugin, name: String): Any {
        return ImmersivePlugin::class.java.getDeclaredField(name).let {
            it.isAccessible = true; checkNotNull(it.get(plugin))
        }
    }

    private fun checkGlass() {
        val immersive = mutableStateOf(false)

        val configured = mutableStateOf(true)
        fun packed(x: Float, y: Float) = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)
        val geometry = ImmersivePlugin.HOST.type("com.xuncorp.voxzen.ui.main.surface.MiniPlayerGlassGeometry").constructors.single().apply { isAccessible = true }
            .newInstance(0f, 0f, 46f, 0f, packed(120f, 80f), packed(180f, 46f), 23f, 0.toByte())
        val animation = ImmersivePlugin.HOST.type("com.xuncorp.voxzen.ui.main.surface.PlayerSurfaceAnimationState").constructors.single().apply { isAccessible = true }
            .newInstance({ 1f }, mutableStateOf(geometry), mutableStateOf(RectangleShape))
        val visibility = ImmersivePlugin.HOST.type("com.xuncorp.voxzen.ui.main.surface.MiniPlayerChromePresentation").constructors.single().apply { isAccessible = true }
            .newInstance(1f, 0f)
        val glassMethod = ImmersivePlugin.HOST.composable("PlayerSurfaceGlass", 6, animation.javaClass)
        val glass = NativeControlHook(mutableStateOf(false), glassMethod)
        val filter = NativeGlassHook(immersive, ImmersivePlugin.BACKDROP_FIELD)
        var contentPresent = false
        val content: @Composable () -> Unit = {
            DisposableEffect(Unit) { contentPresent = true; onDispose { contentPresent = false } }
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Color(0xff204060))
                drawLine(Color.White, Offset(0f, 30f), Offset(240f, 100f), strokeWidth = 12f)
            })
        }
        val call = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> get() {
                val appearance = ImmersiveSelfTest.chromeAppearance(filter, true, false)
                val enabled = ImmersivePlugin.HOST.getter(appearance.javaClass, "backdropEffectsEnabled").invoke(appearance) == true
                return listOf(animation, configured.value && enabled, visibility, content)
            }
            override fun getArgument(name: String): Any? = null
        }
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(240, 120))
        val surface = checkNotNull(Surface.makeRasterN32Premul(240, 120))
        val pixels = Bitmap().apply { check(allocN32Pixels(240, 120)) }
        var time = 0L
        fun render(): IntArray {
            Snapshot.sendApplyNotifications()
            repeat(3) {
                time += 16_000_000
                frame.performFrame(time)
                scene.measureAndLayout()
                surface.canvas.clear(0)
                scene.draw(surface.canvas.asComposeCanvas())
            }
            check(contentPresent && surface.readPixels(pixels, 0, 0))
            return IntArray(240 * 120) { pixels.getColor(it % 240, it / 240) }
        }
        val previews = java.nio.file.Path.of("build", "glass-regression-1.19.2")
        java.nio.file.Files.createDirectories(previews)
        fun save(name: String) {
            surface.makeImageSnapshot().use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    java.nio.file.Files.write(previews.resolve(name), data.bytes)
                }
            }
        }
        try {
            scene.setContent { glass.Content(call) }
            // No controls or backing nodes exist here; the host's outer lens still leaves a capsule.
            val residual = render()
            save("residual-lens.png")
            configured.value = false
            val reference = render()
            save("reference.png")
            val changedPixels = residual.indices.count { residual[it] != reference[it] }
            check(changedPixels > 0) { "Regression did not reproduce the real host's residual lens" }
            check(reference[0] == 0xff204060.toInt() && reference.any { it == 0xffffffff.toInt() })
            configured.value = true
            repeat(4) {
                immersive.value = true
                check(render().contentEquals(reference)) { "Immersive playback left glass/outline pixels" }
                save("immersive.png")
                immersive.value = false
                check(render().contentEquals(residual)) { "Leaving immersion did not restore the native glass effect" }
            }
            configured.value = false
            immersive.value = true
            check(render().contentEquals(reference))
            immersive.value = false
            check(render().contentEquals(reference)) { "An originally disabled glass effect was enabled" }
            immersive.value = true
            ImmersiveSelfTest.chromeAppearance(filter, false, false)
            ImmersiveSelfTest.chromeAppearance(filter, false, true)
            val video = ImmersiveSelfTest.chromeAppearance(filter, true, true)
            check(ImmersivePlugin.HOST.getter(video.javaClass, "backdropEffectsEnabled").invoke(video) == false)
            println("PASS: actual SPW 1.19.2 PlayerSurfaceGlass reproduced $changedPixels residual pixels; immersive output exactly matches the no-glass reference and restores on exit.")
        } finally {
            scene.close()
            frame.close()
            pixels.close()
            surface.close()
        }
    }

    private fun checkEscape() {
        val plugin = ImmersivePlugin(com.xuncorp.spw.workshop.api.PluginContext(
            "local.spw.immersive", "test", ".", "1.19.0", com.xuncorp.spw.workshop.api.Channel.Steam))
        @Suppress("UNCHECKED_CAST") val requested = field(plugin, "immersive") as MutableState<Boolean>
        @Suppress("UNCHECKED_CAST") val navigation = field(plugin, "navigator") as MutableState<com.xuncorp.voxzen.ui.navigation.Navigator>
        val expanded = ImmersiveSelfTest.nativeNavigator(true)
        navigation.value = expanded
        requested.value = true
        @Suppress("UNCHECKED_CAST") val events = expanded.javaClass
            .getMethod("getPlayerSheetEvents\$Voxzen_composeApp").invoke(expanded) as Flow<Any>
        var collapses = 0
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { collapses++ }
        }
        val source = JFrame() // Event source only; never made displayable or visible.
        val other = JFrame()
        val focus = field(plugin, "escapeFocus") as WindowFocusListener
        source.addWindowFocusListener(focus)
        val fullscreen = mutableStateOf(false)
        ImmersivePlugin::class.java.getDeclaredField("fullscreenButton").apply { isAccessible = true }.set(plugin, fullscreen)
        ImmersivePlugin::class.java.getDeclaredField("window").apply { isAccessible = true }.set(plugin, source)
        val dispatch = ImmersivePlugin::class.java.getDeclaredMethod("dispatchKey", KeyEvent::class.java)
            .apply { isAccessible = true }
        fun escape(type: Int): KeyEvent = KeyEvent(source, type, 0, 0, KeyEvent.VK_ESCAPE, 27.toChar())
        try {
            repeat(4) {
                val press = escape(KeyEvent.KEY_PRESSED)
                check(dispatch.invoke(plugin, press) == true && press.isConsumed)
            }
            check(collapses == 1 && requested.value) { "Esc must collapse once per key hold and retain immersion" }
            focus.windowLostFocus(WindowEvent(other, WindowEvent.WINDOW_LOST_FOCUS))
            dispatch.invoke(plugin, escape(KeyEvent.KEY_PRESSED))
            check(collapses == 1) { "Unrelated windows reset the Esc hold" }
            // No key-up reaches SPW when focus leaves before Esc is released.
            focus.windowLostFocus(WindowEvent(source, WindowEvent.WINDOW_LOST_FOCUS))
            focus.windowGainedFocus(WindowEvent(source, WindowEvent.WINDOW_GAINED_FOCUS))
            navigation.value = ImmersiveSelfTest.nativeNavigator(false)
            navigation.value = expanded
            check(dispatch.invoke(plugin, escape(KeyEvent.KEY_PRESSED)) == true)
            check(collapses == 2 && requested.value) { "Esc stayed latched after focus loss and a missed release" }
            repeat(3) { dispatch.invoke(plugin, escape(KeyEvent.KEY_PRESSED)) }
            check(collapses == 2) { "Focus recovery broke long-press suppression" }
            check(dispatch.invoke(plugin, escape(KeyEvent.KEY_RELEASED)) == true)
            navigation.value = ImmersiveSelfTest.nativeNavigator(false)
            val ordinary = escape(KeyEvent.KEY_PRESSED)
            check(dispatch.invoke(plugin, ordinary) == false && !ordinary.isConsumed && requested.value) {
                "Esc outside playback must keep the host's normal keyboard behavior"
            }
            plugin.stop()
            check(source.windowFocusListeners.none { it === focus }) { "Stopping retained the window focus listener" }
            check(!requested.value)
            plugin.stop()
            println("PASS: Esc recovers after focus loss without a key release; long holds stay single-shot and focus listener is removed on stop.")
        } finally {
            job.cancel()
            source.dispose()
            other.dispose()
        }
    }

    private fun checkInput(nested: Boolean) {
        val plugin = ImmersivePlugin(com.xuncorp.spw.workshop.api.PluginContext(
            "local.spw.immersive", "test", ".", "1.19.0", com.xuncorp.spw.workshop.api.Channel.Steam))
        @Suppress("UNCHECKED_CAST") val requested = field(plugin, "immersive") as MutableState<Boolean>
        @Suppress("UNCHECKED_CAST") val navigation = field(plugin, "navigator") as MutableState<com.xuncorp.voxzen.ui.navigation.Navigator>
        @Suppress("UNCHECKED_CAST") val mode = field(plugin, "playbackImmersive") as State<Boolean>
        @Suppress("UNCHECKED_CAST") val presentation = field(plugin, "activeImmersive") as State<Boolean>
        @Suppress("UNCHECKED_CAST") val playbackOption = field(plugin, "playbackEnabled") as MutableState<Boolean>
        @Suppress("UNCHECKED_CAST") val otherOption = field(plugin, "otherPagesEnabled") as MutableState<Boolean>
        val expanded = ImmersiveSelfTest.nativeNavigator(true)
        val collapsed = ImmersiveSelfTest.nativeNavigator(false)
        navigation.value = expanded
        var controlsPresent = false
        var titlePresent = false
        val lockMouse = mutableStateOf(true)
        var clicks = 0
        var drags = 0
        var scrolls = 0
        var hovered = false
        val interactions = Class.forName("androidx.compose.foundation.interaction.InteractionSourceKt")
            .getMethod("MutableInteractionSource").invoke(null) as MutableInteractionSource
        val hoverJob = CoroutineScope(Dispatchers.Unconfined).launch {
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovered = true
                    is HoverInteraction.Exit -> hovered = false
                }
            }
        }
        val platform = object : PlatformContext {
            override val windowInfo = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(400, 300)
            }
            override val inputModeManager = object : InputModeManager {
                override val inputMode = InputMode.Keyboard
                override fun requestInputMode(inputMode: InputMode) = true
            }
        }
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f),
            size = IntSize(400, 300), platformContext = platform)
        val surface = checkNotNull(Surface.makeRasterN32Premul(400, 300))
        val pixels = Bitmap().apply { check(allocN32Pixels(400, 300)) }
        // The host retains ClickableKt bytecode but strips its Kotlin package metadata.
        val onClick: () -> Unit = { clicks++ }
        val nativeClickable = Class.forName("androidx.compose.foundation.ClickableKt").methods.single {
            it.name == "clickable-O2vRcR0"
        }.invoke(null, Modifier, interactions, null, true, null, null, onClick) as Modifier
        val caption: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    var down = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val available = event.changes.none { it.isConsumed }
                        when (event.type) {
                            PointerEventType.Press -> down = available
                            PointerEventType.Release -> down = false
                            PointerEventType.Move -> if (down && available) drags++
                            PointerEventType.Scroll -> if (available) scrolls++
                        }
                    }
                }
            }.then(nativeClickable))
        }
        val call = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = emptyList()
            override fun getArgument(name: String): Any? = null
        }
        @Suppress("UNCHECKED_CAST")
        val navigatorLocal = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt")
            .getMethod("getLocalNavigator").invoke(null) as ProvidableCompositionLocal<com.xuncorp.voxzen.ui.navigation.Navigator>
        var capturedNavigator: com.xuncorp.voxzen.ui.navigation.Navigator? = null
        val shield = NativeInputHook(presentation, lockMouse, navigatorLocal,
            { capturedNavigator = it; navigation.value = it }, mode)
        val probe: @Composable () -> Unit = {
            DisposableEffect(Unit) {
                controlsPresent = true
                onDispose { controlsPresent = false }
            }
        }
        val controlCall = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = listOf(probe)
            override fun getArgument(name: String): Any? = null
        }
        val controls = NativeControlHook(mode, PointerShieldSelfTest::class.java.getDeclaredMethod(
            "controlFixture", kotlin.jvm.functions.Function2::class.java, Composer::class.java, Int::class.javaPrimitiveType))
        val backing = NativeControlHook(mode, ImmersivePlugin.MINI_SURFACE_TARGET.method())
        val backingCall = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = listOf(Modifier.fillMaxSize().drawBehind {
                drawRoundRect(Color.Magenta, cornerRadius = CornerRadius(28f, 28f))
            })
            override fun getArgument(name: String): Any? = null
        }
        val titleProbe: @Composable () -> Unit = {
            DisposableEffect(Unit) { titlePresent = true; onDispose { titlePresent = false } }
        }
        val titleCall = object : UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = listOf(titleProbe)
            override fun getArgument(name: String): Any? = null
        }
        val title = NativeControlHook(presentation, PointerShieldSelfTest::class.java.getDeclaredMethod(
            "controlFixture", kotlin.jvm.functions.Function2::class.java, Composer::class.java, Int::class.javaPrimitiveType))
        var time = 0L
        fun flush() {
            Snapshot.sendApplyNotifications()
            repeat(3) {
                time += 16_000_000
                frame.performFrame(time)
                scene.measureAndLayout()
                surface.canvas.clear(0)
                scene.draw(surface.canvas.asComposeCanvas())
            }
            check(surface.readPixels(pixels, 0, 0))
            check(pixels.getColor(50, 50) == if (mode.value) 0 else 0xffff00ff.toInt()) {
                "Playback-bar backing must leave zero pixels in immersive playback and restore on other pages"
            }
        }
        fun pointer(type: PointerEventType, position: Offset, pressed: Boolean = false, wheel: Offset = Offset.Zero) {
            scene.sendPointerEvent(type, position, scrollDelta = wheel,
                buttons = PointerButtons(isPrimaryPressed = pressed), timeMillis = time / 1_000_000)
            flush()
        }
        fun clickAfterChange() {
            // Entry inserts an overlay; nested exit must remove it before testing Compose routing.
            // Native message filtering covers the interval before the entry frame in the real host.
            if (nested || mode.value) flush()
            scene.sendPointerEvent(PointerEventType.Move, Offset(60f, 60f), timeMillis = time / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 60f),
                buttons = PointerButtons(isPrimaryPressed = true), timeMillis = time / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 60f),
                buttons = PointerButtons(isPrimaryPressed = false), timeMillis = time / 1_000_000)
        }
        fun gestures() {
            pointer(PointerEventType.Move, Offset(40f, 40f))
            pointer(PointerEventType.Press, Offset(40f, 40f), true)
            pointer(PointerEventType.Release, Offset(40f, 40f))
            pointer(PointerEventType.Press, Offset(40f, 40f), true)
            pointer(PointerEventType.Move, Offset(100f, 100f), true)
            pointer(PointerEventType.Move, Offset(160f, 160f), true)
            pointer(PointerEventType.Release, Offset(160f, 160f))
            pointer(PointerEventType.Scroll, Offset(100f, 100f), wheel = Offset(0f, 1f))
        }
        try {
            scene.setContent {
                CompositionLocalProvider(navigatorLocal provides navigation.value) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {} // The native frame remains outside the plugin hook.
                    caption() // Keep the target present to prove real event isolation.
                    controls.Content(controlCall)
                    backing.Content(backingCall)
                    title.Content(titleCall)
                    // Caption hooks live in a different branch from the main playback controls.
                    if (nested) Box(Modifier.zIndex(100f)) { shield.Content(call) }
                    else shield.Content(call)
                }
                }
            }
            flush()
            check(capturedNavigator === expanded) {
                "Host navigation local was not captured from the content branch"
            }
            gestures()
            check(clicks > 0 && drags > 0 && scrolls > 0) { "Baseline input did not reach content: $clicks/$drags/$scrolls" }
            check(hovered) { "Native hover baseline did not activate" }
            val before = listOf(clicks, drags, scrolls)
            requested.value = true
            flush()
            check(!hovered) { "Existing lyric hover was not cleared when entering immersive mode" }
            gestures()
            check(!hovered) { "Immersive mode still lets the lyric target receive hover" }
            check(listOf(clicks, drags, scrolls) == before) { "Input leaked through immersive overlay" }
            requested.value = false
            flush()
            gestures()
            check(hovered) { "Native hover did not recover after exit" }
            check(clicks > before[0] && drags > before[1] && scrolls > before[2]) { "Exit did not restore input" }

            // Exit can happen between frames: native input must unlock before Compose redraws.
            requested.value = true
            flush()
            val beforeExit = clicks
            requested.value = false
            clickAfterChange()
            check(clicks > beforeExit) { "Exit left an input shield until the next composition frame" }
            flush()

            repeat(20) {
                val beforeToggle = clicks
                requested.value = true
                clickAfterChange()
                check(clicks == beforeToggle) { "Entry leaked a click before recomposition" }
                flush()
                requested.value = false
                clickAfterChange()
                check(clicks > beforeToggle) { "Repeated exit left mouse locked at cycle $it" }
                flush()
            }

            requested.value = true
            flush()
            val beforeHeldExit = clicks
            pointer(PointerEventType.Press, Offset(60f, 60f), true)
            requested.value = false
            pointer(PointerEventType.Release, Offset(60f, 60f))
            check(clicks == beforeHeldExit) { "An immersive mouse-down became an unintended click after exit" }
            clickAfterChange()
            check(clicks > beforeHeldExit) { "Exit during a mouse press left input locked" }

            // With the default option, immersive presentation must preserve ordinary input.
            lockMouse.value = false
            requested.value = true
            flush()
            pointer(PointerEventType.Move, Offset(20f, 12f))
            pointer(PointerEventType.Move, Offset(20f, 80f))
            val beforeAllowed = listOf(clicks, drags, scrolls)
            gestures()
            check(hovered && clicks > beforeAllowed[0] && drags > beforeAllowed[1] && scrolls > beforeAllowed[2]) {
                "Unlocked immersive mode must preserve hover, clicks, drags and scrolling"
            }
            lockMouse.value = true
            flush()
            val beforeLocked = listOf(clicks, drags, scrolls)
            gestures()
            check(!hovered && listOf(clicks, drags, scrolls) == beforeLocked) { "Mouse lock did not activate" }
            lockMouse.value = false
            clickAfterChange()
            check(clicks > beforeLocked[0]) { "Disabling lock left a stale input barrier" }

            lockMouse.value = true
            requested.value = true
            flush()
            check(!controlsPresent) { "Expanded immersive controls must be hidden" }
            val beforeCollapse = clicks
            navigation.value = collapsed
            clickAfterChange()
            check(clicks > beforeCollapse) { "Returning to main page left mouse input locked" }
            flush()
            check(controlsPresent && requested.value) { "Collapsed player must restore original controls without disabling immersive mode" }
            check(!titlePresent) { "Other pages must default to immersive chrome while preserving playback controls" }
            pointer(PointerEventType.Move, Offset(20f, 12f))
            pointer(PointerEventType.Move, Offset(20f, 80f))
            otherOption.value = false
            flush()
            check(titlePresent && controlsPresent && requested.value) { "Other-page option did not restore chrome independently" }
            otherOption.value = true
            navigation.value = expanded
            flush()
            check(!controlsPresent && !titlePresent && requested.value) { "Reopening playback must retain the active immersive mode" }
            playbackOption.value = false
            flush()
            val beforeDisabled = clicks
            gestures()
            check(controlsPresent && titlePresent && clicks > beforeDisabled && requested.value) {
                "Playback-page option did not restore its controls and input independently"
            }
        } finally {
            hoverJob.cancel()
            scene.close()
            frame.close()
            pixels.close()
            surface.close()
        }
    }
}










