package local.spw.immersive;

import androidx.compose.runtime.Composer;
import com.xuncorp.spw.workshop.api.hook.UiHookCall;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Uses the host's actual resolver, including the check missed by 0.1.0. */
public final class ImmersiveSelfTest {
    public static void main(String[] args) throws Exception {
        checkLifecycle();
        checkMemoryOnlySetting();
        checkKeyboard();
        checkPageScopes();
        checkCleanupFailures();

        var uiTargets = new java.util.ArrayList<>(ImmersivePlugin.TARGETS);
        uiTargets.add(ImmersivePlugin.MINI_PLAYER_TARGET);
        uiTargets.add(ImmersivePlugin.MINI_SURFACE_TARGET);
        uiTargets.add(ImmersivePlugin.CAPTION_TARGET);
        uiTargets.add(ImmersivePlugin.CAPTION_HIT_TEST);
        for (var target : uiTargets) {
            Method method = target.method();
            assert method.getReturnType() == void.class;
            try {
                Object resolved = local.spw.host.HookInternals.ui(target.owner(), target.name(), target.caller(), target.parameters());
                checkNativeTransform(target, method, resolved);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("Native Compose target validation failed: " + target, failure.getCause());
            }
            try {
                List<String> fullSignature = java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toList();
                local.spw.host.HookInternals.method(target.owner(), target.name(), fullSignature);
                throw new AssertionError("Regression test must reproduce the 0.1.0 ordinary-Hook rejection");
            } catch (InvocationTargetException failure) {
                assert failure.getCause().getMessage().contains("Use a Compose hook") : failure.getCause();
            }
        }
        assert uiTargets.stream().noneMatch(t -> t.owner().equals(ImmersivePlugin.CAPTION_TARGET.caller()))
                : "The native frame must never be replaced";
        for (String owner : ImmersivePlugin.WINDOW_PROCS) {
            local.spw.host.HookInternals.method(owner, "callback", ImmersivePlugin.WINDOW_PROC_PARAMETERS);
        }
        local.spw.host.HookInternals.method(ImmersivePlugin.PLAYBACK_CHROME_OWNER, ImmersivePlugin.CHROME_METHOD.getName(), List.of("boolean", "boolean"));
        for (int message : new int[] {0x200, 0x201, 0x202, 0x20a, 0x20e, 0xa1, 0x246}) {
            assert ImmersivePlugin.nativeMouseResult(message).equals(0L);
        }
        assert ImmersivePlugin.nativeMouseResult(0x84).equals(1L);
        assert ImmersivePlugin.nativeMouseResult(0x20).equals(1L);
        for (int message : new int[] {0x100, 0x101, 0x102, 0x104, 0x105, 0x106, 0x319,
                0x6, 0xf, 0x10, 0x215, 0x2a3}) {
            assert ImmersivePlugin.nativeMouseResult(message) == null : "Swallowed native keyboard/lifecycle message";
        }
        NativeControlHook.class.getDeclaredMethod("Content", UiHookCall.class, Composer.class, int.class);
        NativeInputShield.class.getDeclaredMethod("Content", Composer.class, int.class);
        Composer composer = (Composer) Proxy.newProxyInstance(ImmersiveSelfTest.class.getClassLoader(),
                new Class<?>[] {Composer.class}, (proxy, method, values) -> { throw new AssertionError("No drawing in argument check"); });
        Object marker = new Object();
        Object[] forwarded = NativeControlHook.originalArguments(List.of(true, marker), composer, 4);
        assert forwarded[0].equals(true) && forwarded[1] == marker && forwarded[2] == composer && forwarded[3].equals(0);
        Object[] withDefaults = NativeControlHook.originalArguments(List.of(marker, marker, marker, marker), composer, 7);
        assert withDefaults[4] == composer && withDefaults[5].equals(0) && withDefaults[6].equals(0);
        System.out.println("PASS: only caption/content leaves transformed; native frame excluded; mouse filtering preserves keyboard and lifecycle messages.");
    }

    public static Object chromeAppearance(NativeGlassHook hook, boolean expanded, boolean video) throws Exception {
        Method method = ImmersivePlugin.CHROME_METHOD;
        Object original = method.invoke(null, expanded, video);
        Method background = ImmersivePlugin.HOST.getter(original.getClass(), "backgroundColor");
        Method backdrop = ImmersivePlugin.HOST.getter(original.getClass(), "backdropEffectsEnabled");
        Object originalColor = background.invoke(original);
        Object originalEnabled = backdrop.invoke(original);
        MethodHookParam call = new MethodHookParam() {
            Object result = original;
            public Method getMethod() { return method; }
            public Object getThisObject() { return null; }
            public Object[] getArgs() { return new Object[] {expanded, video}; }
            public Object getResult() { return result; }
            public Throwable getThrowable() { return null; }
            public boolean isOriginalSkipped() { return false; }
            public void setResult(Object value) { result = value; }
            public void setThrowable(Throwable failure) { throw new AssertionError(failure); }
            public Object invokeOriginal() { return original; }
        };
        hook.after(call);
        assert originalColor.equals(background.invoke(original)) : "Glass suppression changed chrome color";
        if (!expanded) assert originalEnabled.equals(backdrop.invoke(original)) : "Other pages lost glass effects";
        // Return the original object: WorkshopMethodAdvice.exit discards replacement returns.
        return original;
    }

    public static com.xuncorp.voxzen.ui.navigation.Navigator nativeNavigator(boolean visible) throws Exception {
        Class<?> valueType = ImmersivePlugin.HOST.type("com.moriafly.salt.ui.BottomSheetValue");
        Class<?> bottomType = ImmersivePlugin.HOST.type("com.moriafly.salt.ui.BottomSheetState");
        Object value = java.util.Arrays.stream(valueType.getEnumConstants())
                .filter(v -> ((Enum<?>) v).name().equals(visible ? "Expanded" : "Collapsed")).findFirst().orElseThrow();
        Object bottom = bottomType.getConstructors()[0].newInstance(value,
                androidx.compose.ui.unit.DensityKt.Density(1f, 1f),
                new androidx.compose.animation.core.TweenSpec<Float>(1, 0, androidx.compose.animation.core.EasingKt.getLinearEasing()),
                (kotlin.jvm.functions.Function1<Object, Boolean>) ignored -> true);
        var sheet = (com.xuncorp.voxzen.ui.component.PlayerSheetState)
                com.xuncorp.voxzen.ui.component.PlayerSheetState.class.getConstructor(bottomType).newInstance(bottom);
        var route = com.xuncorp.voxzen.ui.navigation.ScreenRoute.Track.INSTANCE;
        return new com.xuncorp.voxzen.ui.navigation.Navigator(route, java.util.Set.of(route),
                new androidx.navigation3.runtime.NavBackStack<>(route), sheet);
    }

    private static void checkPageScopes() throws Exception {
        var plugin = new ImmersivePlugin(new com.xuncorp.spw.workshop.api.PluginContext(
                "local.spw.immersive", "test", ".", "1.19.0", com.xuncorp.spw.workshop.api.Channel.Steam));
        var modeField = ImmersivePlugin.class.getDeclaredField("immersive"); modeField.setAccessible(true);
        var navField = ImmersivePlugin.class.getDeclaredField("navigator"); navField.setAccessible(true);
        var activeField = ImmersivePlugin.class.getDeclaredField("activeImmersive"); activeField.setAccessible(true);
        var playbackField = ImmersivePlugin.class.getDeclaredField("playbackImmersive"); playbackField.setAccessible(true);
        var playbackOption = ImmersivePlugin.class.getDeclaredField("playbackEnabled"); playbackOption.setAccessible(true);
        var otherOption = ImmersivePlugin.class.getDeclaredField("otherPagesEnabled"); otherOption.setAccessible(true);
        @SuppressWarnings("unchecked") var mode = (androidx.compose.runtime.MutableState<Boolean>) modeField.get(plugin);
        @SuppressWarnings("unchecked") var navigation = (androidx.compose.runtime.MutableState<com.xuncorp.voxzen.ui.navigation.Navigator>) navField.get(plugin);
        @SuppressWarnings("unchecked") var active = (androidx.compose.runtime.State<Boolean>) activeField.get(plugin);
        @SuppressWarnings("unchecked") var playback = (androidx.compose.runtime.State<Boolean>) playbackField.get(plugin);
        @SuppressWarnings("unchecked") var onPlayer = (androidx.compose.runtime.MutableState<Boolean>) playbackOption.get(plugin);
        @SuppressWarnings("unchecked") var onOther = (androidx.compose.runtime.MutableState<Boolean>) otherOption.get(plugin);
        assert onPlayer.getValue() && onOther.getValue() : "Both page scopes must default to immersive";
        var expanded = nativeNavigator(true);
        var collapsed = nativeNavigator(false);
        for (boolean enabled : new boolean[] {false, true}) {
            mode.setValue(enabled);
            for (boolean player : new boolean[] {false, true}) {
                onPlayer.setValue(player);
                for (boolean other : new boolean[] {false, true}) {
                    onOther.setValue(other);
                    navigation.setValue(expanded);
                    assert active.getValue() == (enabled && player);
                    assert playback.getValue() == (enabled && player);
                    navigation.setValue(collapsed);
                    assert active.getValue() == (enabled && other);
                    assert !playback.getValue() : "Other pages must retain an operable mini player";
                    assert mode.getValue() == enabled : "Navigation must retain the immersion toggle";
                }
            }
        }
        System.out.println("PASS: real host page state and independent playback/other-page options; navigation preserves immersion.");
    }

    private static void checkLifecycle() throws Exception {
        var lifecycle = new java.util.concurrent.atomic.AtomicReference<Object>(new Object());
        int[] calls = {0};
        Runnable first = ImmersivePlugin.shortcutHandler(lifecycle, () -> calls[0]++);
        java.awt.EventQueue.invokeAndWait(() -> {
            first.run();
            lifecycle.set(null);
        });
        java.awt.EventQueue.invokeAndWait(() -> { });
        assert calls[0] == 0 : "Queued action survived stop";
        lifecycle.set(new Object());
        first.run();
        Runnable second = ImmersivePlugin.shortcutHandler(lifecycle, () -> calls[0]++);
        second.run();
        java.awt.EventQueue.invokeAndWait(() -> { });
        assert calls[0] == 1 : "Old registration survived restart or current registration failed";
        lifecycle.set(null);
        second.run();
        java.awt.EventQueue.invokeAndWait(() -> { });
        assert calls[0] == 1 : "Late callback survived stop";
        System.out.println("PASS: queued and late shortcuts ignored after stop and restart.");
    }

    @SuppressWarnings("unchecked")
    private static void checkCleanupFailures() throws Exception {
        var plugin = new ImmersivePlugin(new com.xuncorp.spw.workshop.api.PluginContext(
                "local.spw.immersive", "0.1.30", ".", "1.19.2", com.xuncorp.spw.workshop.api.Channel.Steam));
        var hooksField = ImmersivePlugin.class.getDeclaredField("hooks"); hooksField.setAccessible(true);
        var hooks = (List<com.xuncorp.spw.workshop.api.hook.HookHandle>) hooksField.get(plugin);
        var immersiveField = ImmersivePlugin.class.getDeclaredField("immersive"); immersiveField.setAccessible(true);
        var immersive = (androidx.compose.runtime.MutableState<Boolean>) immersiveField.get(plugin);
        immersive.setValue(true);
        var inputsField = ImmersivePlugin.class.getDeclaredField("inputWindows"); inputsField.setAccessible(true);
        inputsField.set(plugin, java.util.Set.of(123L));
        var shortcutField = ImmersivePlugin.class.getDeclaredField("shortcut"); shortcutField.setAccessible(true);
        int[] shortcutCloses = {0};
        shortcutField.set(plugin, (AutoCloseable) () -> shortcutCloses[0]++);
        int[] attempts = {0, 0, 0, 0};
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        hooks.add(() -> { attempts[0]++; if (fail.get()) throw new IllegalStateException("injected first unhook failure"); });
        hooks.add(() -> attempts[1]++);
        hooks.add(() -> { attempts[2]++; if (fail.get()) throw new LinkageError("injected late unhook failure"); });
        hooks.add(() -> attempts[3]++);
        var errors = new java.io.ByteArrayOutputStream();
        var stderr = System.err;
        try (var capture = new java.io.PrintStream(errors)) {
            System.setErr(capture);
            try { plugin.stop(); throw new AssertionError("Partial cleanup reported success"); }
            catch (IllegalStateException failure) { assert failure.getSuppressed().length == 2; }
        } finally { System.setErr(stderr); }
        assert java.util.Arrays.equals(attempts, new int[] {1, 1, 1, 1}) : "A failed hook skipped later cleanup";
        assert !immersive.getValue() && ((java.util.Set<?>) inputsField.get(plugin)).isEmpty();
        assert shortcutCloses[0] == 1 && shortcutField.get(plugin) == null;
        assert hooks.size() == 2 : "Failed hooks were forgotten or successful hooks retained";
        assert errors.toString().contains("injected first unhook failure") && errors.toString().contains("injected late unhook failure");
        fail.set(false);
        plugin.stop();
        plugin.stop();
        assert hooks.isEmpty() && java.util.Arrays.equals(attempts, new int[] {2, 1, 2, 1});
        assert shortcutCloses[0] == 1;
        System.out.println("PASS: all hooks attempted after multiple failures; failures logged/reported, failed handles retry, successful cleanup stays idempotent.");
    }

    private static void checkMemoryOnlySetting() throws Exception {
        int[] writes = {0};
        for (boolean saved : new boolean[] {true, false}) {
            var property = new com.xuncorp.voxzen.util.spkv.SpkvStateProperty<Boolean>(
                    "test-only", saved, (key, fallback) -> fallback,
                    (key, value) -> { writes[0]++; return kotlin.Unit.INSTANCE; });
            var state = ImmersivePlugin.memoryState(property);
            boolean previous = state.getValue();
            state.setValue(false);
            assert !state.getValue();
            // Simulate a fresh process reading the unchanged saved value before cleanup.
            var reopened = new com.xuncorp.voxzen.util.spkv.SpkvStateProperty<Boolean>(
                    "test-only", saved, (key, fallback) -> fallback,
                    (key, value) -> { writes[0]++; return kotlin.Unit.INSTANCE; });
            assert ImmersivePlugin.memoryState(reopened).getValue() == saved;
            state.setValue(previous);
            assert state.getValue() == saved;
        }
        assert writes[0] == 0 : "Temporary setting triggered persistence";
        var field = com.xuncorp.voxzen.util.AppConfig.class.getDeclaredField("windowFullScreenButton$delegate");
        assert field.getType() == kotlin.properties.ReadWriteProperty.class;
        System.out.println("PASS: real host state delegate changes/restores without persistent writes.");
    }

    private static void checkKeyboard() {
        var f10 = ImmersivePlugin.parseShortcut(ImmersivePlugin.DEFAULT_SHORTCUT);
        assert f10.getKeyCode() == java.awt.event.KeyEvent.VK_F10 && f10.getModifiers() == 0;
        var custom = ImmersivePlugin.parseShortcut(" ctrl + ALT + i ");
        assert custom.getKeyCode() == java.awt.event.KeyEvent.VK_I && custom.getModifiers() == 3;
        assert ImmersivePlugin.parseShortcut("Ctrl+Shift+F9").getModifiers() == 5;
        assert ImmersivePlugin.parseShortcut("Space").getKeyCode() == java.awt.event.KeyEvent.VK_SPACE;
        for (String invalid : new String[] {"", "Esc", "Escape", "Ctrl+Escape", "Ctrl", "Ctrl+", "Ctrl++I",
                "Control+Ctrl+I", "NoSuchKey", "Ctrl+NoSuchKey", "Meta+F10"}) {
            try {
                ImmersivePlugin.parseShortcut(invalid);
                throw new AssertionError("Accepted invalid shortcut: " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
        var component = new javax.swing.JPanel();
        int[] exits = {0};
        Runnable exit = () -> exits[0]++;
        var esc = new java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
                java.awt.event.KeyEvent.VK_ESCAPE, (char) 27);
        assert !ImmersivePlugin.handleEscape(esc, false, exit) && !esc.isConsumed();
        assert ImmersivePlugin.handleEscape(esc, true, exit) && esc.isConsumed() && exits[0] == 1;
        var release = new java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_RELEASED, 0, 0,
                java.awt.event.KeyEvent.VK_ESCAPE, (char) 27);
        assert ImmersivePlugin.handleEscape(release, true, exit) && release.isConsumed() && exits[0] == 1;
        for (int code : new int[] {java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_RIGHT,
                java.awt.event.KeyEvent.VK_LEFT, 0xB0,
                java.awt.event.KeyEvent.VK_F10, java.awt.event.KeyEvent.VK_I}) {
            var event = new java.awt.event.KeyEvent(component, java.awt.event.KeyEvent.KEY_PRESSED, 0,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK, code, java.awt.event.KeyEvent.CHAR_UNDEFINED);
            assert !ImmersivePlugin.handleEscape(event, true, exit) && !event.isConsumed()
                    : "Playback/custom shortcut was swallowed: " + code;
        }
        System.out.println("PASS: configurable shortcuts parse; invalid keys rejected; Esc invokes playback collapse; other keys pass through.");
    }

    private static void checkNativeTransform(ImmersivePlugin.ControlTarget target, Method method, Object resolved) throws Exception {
        byte[] before = resourceBytes(target.caller());
        byte[] after = local.spw.host.HookInternals.transform(method, target.caller(), resolved);
        assert !java.util.Arrays.equals(before, after) : "Native UI transformer must actually change the call site";
        java.lang.classfile.ClassFile.of().parse(after);
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (var stream = ImmersiveSelfTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Missing class bytes: " + name);
            return stream.readAllBytes();
        }
    }

}

