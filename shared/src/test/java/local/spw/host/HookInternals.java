package local.spw.host;

import java.lang.reflect.Method;
import java.util.*;
import com.xuncorp.spw.workshop.api.WorkshopApi;

/** Exercise the host's actual resolver/transformer without embedding their obfuscated names. */
public final class HookInternals {
    public static final HostResolver HOST = new HostResolver(WorkshopApi.class);
    private static final Class<?> TARGETS = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopHookTargets");
    private static final Object INSTANCE = HOST.singleton(TARGETS);
    public static Object ui(String owner, String name, String caller, List<String> parameters) throws Exception {
        return HOST.function(TARGETS, "ui").invoke(INSTANCE, owner, name, caller, parameters);
    }
    public static Object method(String owner, String name, List<String> parameters) throws Exception {
        return HOST.function(TARGETS, "method").invoke(INSTANCE, owner, name, parameters);
    }
    public static byte[] bytes(String name) throws Exception {
        try (var stream = WorkshopApi.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return Objects.requireNonNull(stream, name).readAllBytes();
        }
    }
    public static byte[] transform(Method method, String caller, Object resolved) throws Exception {
        Class<?> signatureType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopComposableCallSignature");
        Class<?> companionType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopComposableCallSignature$Companion");
        Object companion = HOST.singleton(companionType);
        Method factory = HostResolver.unique(Arrays.stream(companionType.getDeclaredMethods()).filter(m ->
                m.getParameterCount() == 6 && m.getParameterTypes()[0] == int.class && m.getReturnType() == signatureType).toList(), "hook signature factory");
        factory.setAccessible(true);
        Class<?> readerType = factory.getParameterTypes()[5];
        String owner = method.getDeclaringClass().getName();
        String descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
        Object signature = factory.invoke(companion, 184, owner.replace('.', '/'), method.getName(), descriptor, false,
                readerType.getConstructor(byte[].class).newInstance((Object)bytes(owner)));
        Object masks = HOST.function(signatureType, "resolveDefaultMaskIndices").invoke(signature);
        Class<?> siteType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopHookRegistrar$UiSite");
        Object site = siteType.getConstructor(int.class, resolved.getClass()).newInstance(1, resolved);
        HOST.field(siteType, "defaultMaskIndices").set(site, masks);
        Class<?> transformType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopUiHookTransformer");
        Object transformer = transformType.getConstructor(siteType).newInstance(site);
        return (byte[]) HOST.function(transformType, "transform").invoke(transformer, caller, bytes(caller));
    }
}
