// shortcut: the current-scene local is internal to bundled Compose; recheck on host updates.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

@file:OptIn(
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class
)

package local.spw.immersive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.State
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.SideEffect
import com.xuncorp.voxzen.ui.navigation.Navigator
import java.util.function.Consumer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.scene.LocalComposeScene
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

class NativeControlHook(
    private val immersive: State<Boolean>,
    private val original: Method
) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        if (!immersive.value) {
            renderOriginal(call, currentComposer)
        }
    }

    private fun renderOriginal(call: UiHookCall, composer: Composer) {
        val arguments = originalArguments(call.arguments, composer, original.parameterCount)
        try {
            // UI Hooks replace call sites, so invoking the native body does not re-enter this Hook.
            original.invoke(call.thisObject, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    companion object {
        @JvmStatic
        fun originalArguments(business: List<Any?>, composer: Composer, count: Int): Array<Any?> {
            require(count > business.size + 1) { "Missing Compose changed masks" }
            return Array(count) { index ->
                when {
                    index < business.size -> business[index]
                    index == business.size -> composer
                    else -> 0 // Recompute changed flags; both selected call sites pass all parameters explicitly.
                }
            }
        }
    }
}

/** Keep the host's transparent caption hit test when hiding its visible title bar. */
class NativeCaptionHook(private val immersive: State<Boolean>, original: Method, private val hitTest: Method) : ComposableHook() {
    private val ordinary = NativeControlHook(immersive, original)

    @Composable
    override fun Content(call: UiHookCall) {
        if (!immersive.value) {
            ordinary.Content(call)
            return
        }
        val composer = currentComposer
        try {
            hitTest.invoke(null, Modifier, composer, 0, 0)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}

/** Disable the outer lens as well as the separately hidden playback-bar backing. */
class NativeGlassHook(private val immersive: State<Boolean>, private val backdrop: java.lang.reflect.Field) : MethodHook() {
    // shortcut: this host ignores replacement returns; mutate only its newly created appearance.
    init { check(backdrop.type == Boolean::class.javaPrimitiveType); backdrop.isAccessible = true }

    override fun after(call: MethodHookParam) {
        if (!immersive.value || call.args[0] != true || call.throwable != null) return
        val appearance = call.result ?: return
        backdrop.setBoolean(appearance, false)
    }
}

class NativeInputShield(immersive: State<Boolean>, lockMouse: State<Boolean>) {
    private val blocked = derivedStateOf { immersive.value && lockMouse.value }
    @Composable
    fun Content() {
        val active = blocked.value
        if (!active) return
        val scene = LocalComposeScene.current
        LaunchedEffect(active) {
            if (active) scene?.cancelPointerInput()
        }
        val hiddenPointer = remember {
            PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(), "immersive"))
        }
        Box(Modifier.fillMaxSize().zIndex(Float.MAX_VALUE)
            .then(if (active) Modifier.pointerHoverIcon(hiddenPointer, overrideDescendants = true) else Modifier)
            .then(InputGateElement(blocked)))
    }
}

private data class InputGateElement(val immersive: State<Boolean>) : ModifierNodeElement<InputGateNode>() {
    override fun create() = InputGateNode(immersive)
    override fun update(node: InputGateNode) { node.immersive = immersive }
}

private class InputGateNode(var immersive: State<Boolean>) : Modifier.Node(), PointerInputModifierNode {
    // Live hit-test sharing unlocks ordinary content before the exit frame is composed.
    override fun sharePointerInputWithSiblings() = !immersive.value
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (immersive.value) pointerEvent.changes.forEach { it.consume() }
    }
    override fun onCancelPointerInput() {}
}

/** Captures navigation inside main-window content; never replaces the native frame. */
class NativeInputHook(
    private val immersive: State<Boolean>, lockMouse: State<Boolean>,
    private val navigatorLocal: CompositionLocal<Navigator>? = null,
    private val captureNavigator: Consumer<Navigator>? = null,
    playbackImmersive: State<Boolean> = immersive,
    private val playbackChanged: Consumer<Boolean>? = null
) : ComposableHook() {
    private val shield = NativeInputShield(playbackImmersive, lockMouse)

    @Composable
    override fun Content(call: UiHookCall) {
        val active = immersive.value
        SideEffect { playbackChanged?.accept(active) }
        navigatorLocal?.let { local ->
            val host = local.current
            SideEffect { captureNavigator?.accept(host) }
        }
        shield.Content()
    }
}

