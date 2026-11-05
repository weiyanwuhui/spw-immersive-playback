package local.spw.immersive;

import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.runtime.State;
import com.xuncorp.spw.workshop.api.ActionShortcut;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import com.xuncorp.voxzen.util.AppConfig;
import com.xuncorp.voxzen.ui.navigation.AppNavigationKt;
import com.xuncorp.voxzen.ui.navigation.Navigator;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import local.spw.host.HostResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Hide native UI surfaces without changing window size, placement or fullscreen. */
public final class ImmersivePlugin extends SpwPlugin {
    private final MutableState<Boolean> immersive = flag(false);
    private final MutableState<Boolean> playbackEnabled = flag(true);
    private final MutableState<Boolean> otherPagesEnabled = flag(true);
    private final MutableState<Boolean> lockMouse = flag(false);
    private final MutableState<Navigator> navigator = SnapshotStateKt.mutableStateOf(
            null, SnapshotStateKt.structuralEqualityPolicy());
    private final State<Boolean> activeImmersive = () -> immersive.getValue()
            && (playerVisible(navigator.getValue()) ? playbackEnabled.getValue() : otherPagesEnabled.getValue());
    private final State<Boolean> playbackImmersive = () -> activeImmersive.getValue() && playerVisible(navigator.getValue());
    private final List<HookHandle> hooks = new ArrayList<>();
    private final KeyEventDispatcher keyboard = this::dispatchKey;
    private final WindowAdapter escapeFocus = new WindowAdapter() {
        @Override public void windowLostFocus(WindowEvent event) {
            if (event.getWindow() == window) escapeWindow = null;
        }
    };
    private final AtomicReference<Object> lifecycle = new AtomicReference<>();
    private MutableState<Boolean> fullscreenButton;
    private AutoCloseable shortcut;
    private ConfigHelper config;
    private JFrame window;
    private boolean wasFullscreenButtonVisible;
    private Window escapeWindow;
    private volatile Set<Long> inputWindows = Set.of();
    static final String DEFAULT_SHORTCUT = "F10";

    static final HostResolver HOST = new HostResolver(WorkshopApi.class);
    private static final Method TITLE = HOST.composable("MainTitleBar", 7, null);
    static final List<ControlTarget> TARGETS = List.of(target(TITLE, HOST.caller(TITLE)));
    static final ControlTarget CAPTION_HIT_TEST = target(HOST.composable("PlatformCaptionBarHitTest", 4, androidx.compose.ui.Modifier.class), TITLE.getDeclaringClass().getName());
    private static final Method THEME = HOST.composable("ChangeSaltThemeIsDark", 4, boolean.class);
    static final ControlTarget MINI_PLAYER_TARGET = target(THEME, HOST.facade("MiniPlayer", "miniPlayerCapsuleSurface").getName());
    static final ControlTarget MINI_SURFACE_TARGET = target(boxMethod(), HOST.facade("MainSurface", "PlayerSurfaceGlass").getName());
    static final Method CHROME_METHOD = HOST.function(HOST.facade("playbackChromeAppearance"), "playbackChromeAppearance");
    static final String PLAYBACK_CHROME_OWNER = CHROME_METHOD.getDeclaringClass().getName();
    static final Field BACKDROP_FIELD = HOST.field(CHROME_METHOD.getReturnType(), "backdropEffectsEnabled");
    static final ControlTarget CAPTION_TARGET = target(THEME, HOST.facade("WindowsSaltWindowFrame").getName());
    private static final Method SHEET_VISIBLE = sheetVisibility();

    private static Method sheetVisibility() {
        Class<?> sheet = HOST.type("com.xuncorp.voxzen.ui.component.PlayerSheetState");
        Class<?> bottom = HOST.type("com.moriafly.salt.ui.BottomSheetState");
        Class<?> value = HOST.type("com.moriafly.salt.ui.BottomSheetValue");
        Method found = HostResolver.unique(java.util.Arrays.stream(sheet.getDeclaredMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == boolean.class)
                .filter(m -> HOST.calls(m, call -> call.getDeclaringClass() == bottom && call.getReturnType() == value).size() == 1)
                .toList(), "player sheet visibility");
        found.setAccessible(true);
        return found;
    }
    static final List<String> WINDOW_PROCS = List.of(
            "com.moriafly.salt.ui.platform.windows.ComposeWindowProc",
            "com.moriafly.salt.ui.platform.windows.SkiaLayerWindowProc");
    static final List<String> WINDOW_PROC_PARAMETERS = List.of(
            "com.sun.jna.platform.win32.WinDef$HWND", "int",
            "com.sun.jna.platform.win32.WinDef$WPARAM", "com.sun.jna.platform.win32.WinDef$LPARAM");

    record ControlTarget(String owner, String name, String caller, List<String> parameters, int masks, Method method) { }

    private static ControlTarget target(Method method, String caller) {
        HOST.callerIn(method, HOST.type(caller));
        var signature = List.of(method.getParameterTypes());
        int composer = signature.indexOf(androidx.compose.runtime.Composer.class);
        if (composer < 0 || signature.subList(composer + 1, signature.size()).stream().anyMatch(t -> t != int.class))
            throw new IllegalStateException("沉浸播放 Compose 参数不兼容");
        return new ControlTarget(method.getDeclaringClass().getName(), method.getName(), caller,
                signature.subList(0, composer).stream().map(Class::getName).toList(), signature.size() - composer - 1, method);
    }

    private static Method boxMethod() {
        try { return Class.forName("androidx.compose.foundation.layout.BoxKt").getDeclaredMethod("Box", androidx.compose.ui.Modifier.class, androidx.compose.runtime.Composer.class, int.class); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("SPW Box 接口不兼容", failure); }
    }

    public ImmersivePlugin(PluginContext context) {
        super(context);
    }

    private static MutableState<Boolean> flag(boolean initial) {
        return SnapshotStateKt.mutableStateOf(initial, SnapshotStateKt.structuralEqualityPolicy());
    }

    @Override public void start() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new IllegalStateException("沉浸播放的原生窗口控制仅支持 Windows。");
        }
        for (PluginPermission permission : List.of(PluginPermission.KEY_BINDINGS, PluginPermission.CLASS_TRANSFORM)) {
            if (!WorkshopApi.manager().isPermissionGranted(permission)) {
                throw new IllegalStateException("沉浸播放需要授权：" + permission);
            }
        }
        try {
            var delegate = AppConfig.class.getDeclaredField("windowFullScreenButton$delegate");
            delegate.setAccessible(true);
            fullscreenButton = memoryState(delegate.get(null));
            lifecycle.set(new Object());
            for (ControlTarget target : TARGETS) {
                hooks.add(WorkshopApi.hookRegistrar().replaceComposable(
                        target.owner(), target.name(), target.caller(), target.parameters(), 50,
                        new NativeCaptionHook(activeImmersive, target.method(), CAPTION_HIT_TEST.method())));
            }
            hooks.add(WorkshopApi.hookRegistrar().replaceComposable(
                    MINI_PLAYER_TARGET.owner(), MINI_PLAYER_TARGET.name(), MINI_PLAYER_TARGET.caller(), MINI_PLAYER_TARGET.parameters(), 50,
                    new NativeControlHook(playbackImmersive, MINI_PLAYER_TARGET.method())));
            hooks.add(WorkshopApi.hookRegistrar().afterComposable(
                    MINI_PLAYER_TARGET.owner(), MINI_PLAYER_TARGET.name(), MINI_PLAYER_TARGET.caller(), MINI_PLAYER_TARGET.parameters(), 50,
                    new NativeInputHook(activeImmersive, lockMouse, AppNavigationKt.getLocalNavigator(),
                            navigator::setValue, playbackImmersive, this::updateFullscreenButton)));
            hooks.add(WorkshopApi.hookRegistrar().replaceComposable(
                    MINI_SURFACE_TARGET.owner(), MINI_SURFACE_TARGET.name(), MINI_SURFACE_TARGET.caller(),
                    MINI_SURFACE_TARGET.parameters(), 50,
                    new NativeControlHook(playbackImmersive, MINI_SURFACE_TARGET.method())));
            hooks.add(WorkshopApi.hookRegistrar().hookMethod(
                    PLAYBACK_CHROME_OWNER, CHROME_METHOD.getName(), List.of("boolean", "boolean"),
                    new NativeGlassHook(playbackImmersive, BACKDROP_FIELD)));
            hooks.add(WorkshopApi.hookRegistrar().replaceComposable(
                    CAPTION_TARGET.owner(), CAPTION_TARGET.name(), CAPTION_TARGET.caller(), CAPTION_TARGET.parameters(), 50,
                    new NativeControlHook(activeImmersive, CAPTION_TARGET.method())));
            for (String owner : WINDOW_PROCS) {
                hooks.add(WorkshopApi.hookRegistrar().hookMethod(owner, "callback", WINDOW_PROC_PARAMETERS,
                        new MethodHook() {
                            @Override public void before(MethodHookParam call) {
                                if (!playbackImmersive.getValue() || !lockMouse.getValue()) return;
                                var hwnd = (WinDef.HWND) call.getArgs()[0];
                                if (!inputWindows.contains(Pointer.nativeValue(hwnd.getPointer()))) return;
                                int message = (Integer) call.getArgs()[1];
                                Long result = nativeMouseResult(message);
                                if (result == null) return;
                                if (message == 0x20) NativeLibrary.getInstance("user32")
                                        .getFunction("SetCursor").invokePointer(new Object[] {null});
                                call.setResult(new WinDef.LRESULT(result));
                            }
                        }));
            }
            config = WorkshopApi.manager().createConfigManager().getConfig();
            ActionShortcut configured;
            try { configured = parseShortcut(config.get("toggle_shortcut", DEFAULT_SHORTCUT)); }
            catch (IllegalArgumentException invalid) {
                configured = parseShortcut(DEFAULT_SHORTCUT);
                WorkshopApi.ui().toast("旧快捷键配置无效，暂用 F10。请在播放器的快捷键设置中修改。",
                        WorkshopApi.Ui.ToastType.Warning);
            }
            shortcut = WorkshopApi.manager().getKeyBindingManager().register(
                    "toggle-immersive", "切换沉浸播放", configured, false,
                    shortcutHandler(lifecycle, this::toggle));
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboard);
        } catch (Exception failure) {
            try { stop(); }
            catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("沉浸播放注册失败", failure);
        }
    }

    static Runnable shortcutHandler(AtomicReference<Object> lifecycle, Runnable action) {
        Object registration = lifecycle.get();
        return () -> EventQueue.invokeLater(() -> {
            if (registration != null && lifecycle.get() == registration) action.run();
        });
    }

    // shortcut: supported hosts store Compose state here; recheck this field on host updates.
    @SuppressWarnings("unchecked")
    static MutableState<Boolean> memoryState(Object delegate) throws ReflectiveOperationException {
        var field = delegate.getClass().getDeclaredField("state");
        field.setAccessible(true);
        Object state = field.get(delegate);
        if (!(state instanceof MutableState<?> mutable) || !(mutable.getValue() instanceof Boolean)) {
            throw new IllegalStateException("Unsupported fullscreen button state");
        }
        return (MutableState<Boolean>) state;
    }

    private void toggle() {
        if (window != null || immersive.getValue()) {
            leave();
            return;
        }
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (!(focused instanceof JFrame frame)
                || !focused.getClass().getName().equals("androidx.compose.ui.awt.ComposeWindow")) {
            WorkshopApi.ui().toast("请在播放器主窗口按快捷键进入沉浸播放。", WorkshopApi.Ui.ToastType.Warning);
            return;
        }
        if (frame.getGlassPane().isVisible()) {
            WorkshopApi.ui().toast("请先关闭当前弹窗。", WorkshopApi.Ui.ToastType.Warning);
            return;
        }
        try {
            config.reload();
            playbackEnabled.setValue(config.get("immersive_playback", true));
            otherPagesEnabled.setValue(config.get("immersive_other_pages", true));
            lockMouse.setValue(config.get("lock_mouse", false));
            if (navigator.getValue() == null) throw new IllegalStateException("Player navigation is not ready");
            wasFullscreenButtonVisible = fullscreenButton.getValue();
            window = frame;
            window.addWindowFocusListener(escapeFocus);
            var handles = new HashSet<Long>();
            handles.add(Pointer.nativeValue(Native.getWindowPointer(frame)));
            collectCanvasHandles(frame, handles);
            inputWindows = Set.copyOf(handles);
            immersive.setValue(true);
            updateFullscreenButton(activeImmersive.getValue());
        } catch (Exception failure) {
            leave();
            failure.printStackTrace();
            WorkshopApi.ui().toast("无法进入沉浸播放，已恢复界面。", WorkshopApi.Ui.ToastType.Warning);
        }
    }

    private static void collectCanvasHandles(Component component, Set<Long> handles) {
        if (component instanceof Canvas && component.isDisplayable()) {
            handles.add(Pointer.nativeValue(Native.getComponentPointer(component)));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectCanvasHandles(child, handles);
        }
    }

    static Long nativeMouseResult(int message) {
        if (message == 0x84 || message == 0x20) return 1L;
        if ((message >= 0x200 && message <= 0x20e) || (message >= 0xa0 && message <= 0xad)
                || (message >= 0x240 && message <= 0x24f)) return 0L;
        return null;
    }

    static boolean playerVisible(Navigator host) {
        if (host == null) return false;
        try { return (Boolean) SHEET_VISIBLE.invoke(host.getPlayerSheetState()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("无法读取 SPW 播放页状态", failure); }
    }

    static ActionShortcut parseShortcut(String value) {
        if (value == null || value.length() > 80) throw new IllegalArgumentException("Invalid shortcut");
        String[] parts = value.trim().split("\\+", -1);
        StringBuilder normalized = new StringBuilder();
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            int modifier = switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "ctrl", "control" -> ActionShortcut.CONTROL;
                case "alt" -> ActionShortcut.ALT;
                case "shift" -> ActionShortcut.SHIFT;
                default -> throw new IllegalArgumentException("Use Ctrl, Alt or Shift");
            };
            if ((modifiers & modifier) != 0) throw new IllegalArgumentException("Duplicate modifier");
            modifiers |= modifier;
        }
        if ((modifiers & ActionShortcut.CONTROL) != 0) normalized.append("ctrl ");
        if ((modifiers & ActionShortcut.ALT) != 0) normalized.append("alt ");
        if ((modifiers & ActionShortcut.SHIFT) != 0) normalized.append("shift ");
        normalized.append(parts[parts.length - 1].trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        KeyStroke key = KeyStroke.getKeyStroke(normalized.toString());
        if (key == null || key.getKeyCode() <= 0 || key.getKeyCode() == KeyEvent.VK_ESCAPE
                || key.getKeyCode() == KeyEvent.VK_CONTROL || key.getKeyCode() == KeyEvent.VK_ALT
                || key.getKeyCode() == KeyEvent.VK_SHIFT || key.getKeyCode() == KeyEvent.VK_META
                || key.getKeyCode() == KeyEvent.VK_ALT_GRAPH) {
            throw new IllegalArgumentException("Choose a non-modifier key; Esc is reserved for exit");
        }
        return new ActionShortcut(key.getKeyCode(), modifiers);
    }

    private boolean dispatchKey(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
        Window source = event.getComponent() instanceof Window owner ? owner
                : SwingUtilities.getWindowAncestor(event.getComponent());
        Window pending = escapeWindow;
        if (event.getID() == KeyEvent.KEY_RELEASED) escapeWindow = null;
        boolean activeHere = window != null && source == window && playbackImmersive.getValue();
        boolean finishingEscape = pending != null && source == pending;
        if (!activeHere && !finishingEscape) return false;
        boolean firstPress = event.getID() == KeyEvent.KEY_PRESSED && activeHere && pending == null;
        if (event.getID() == KeyEvent.KEY_PRESSED && activeHere) escapeWindow = window;
        return handleEscape(event, activeHere || finishingEscape, () -> {
            if (firstPress) navigator.getValue().collapsePlayer();
        });
    }

    static boolean handleEscape(KeyEvent event, boolean activeHere, Runnable collapse) {
        if (!activeHere || event.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
        if (event.getID() == KeyEvent.KEY_PRESSED) collapse.run();
        event.consume();
        return true;
    }

    private void leave() {
        immersive.setValue(false);
        inputWindows = Set.of();
        escapeWindow = null;
        if (window != null) {
            window.removeWindowFocusListener(escapeFocus);
            fullscreenButton.setValue(wasFullscreenButtonVisible);
        }
        window = null;
    }

    private void updateFullscreenButton(boolean active) {
        if (window != null) fullscreenButton.setValue(!active && wasFullscreenButtonVisible);
    }

    @Override public void stop() {
        lifecycle.set(null);
        var failures = new ArrayList<Throwable>();
        try {
            if (EventQueue.isDispatchThread()) leave();
            else EventQueue.invokeAndWait(this::leave);
        } catch (Exception failure) {
            failures.add(failure);
        } finally {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyboard);
            if (shortcut != null) {
                try { shortcut.close(); } catch (Exception failure) { failure.printStackTrace(); }
                shortcut = null;
            }
            for (var iterator = hooks.iterator(); iterator.hasNext();) {
                HookHandle hook = iterator.next();
                try {
                    hook.close();
                    iterator.remove();
                } catch (RuntimeException | LinkageError failure) {
                    failure.printStackTrace();
                    failures.add(failure);
                }
            }
        }
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("沉浸播放清理未全部完成，请查看日志或重试停用。");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }
}



