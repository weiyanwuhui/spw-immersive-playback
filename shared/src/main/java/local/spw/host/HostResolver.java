package local.spw.host;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import kotlin.Metadata;
import kotlin.metadata.*;
import kotlin.metadata.jvm.*;

/** Metadata supplies semantic identities; bytecode supplies the actual obfuscated JVM members. */
public final class HostResolver {
    private final ClassLoader loader;
    private final Map<String, Entry> entries;
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, Class<?>> types = new ConcurrentHashMap<>();
    private final Map<String, Method> methods = new ConcurrentHashMap<>();
    private final Map<String, Field> fields = new ConcurrentHashMap<>();
    private final Map<String, String> callers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, Field>> propertyFields = new ConcurrentHashMap<>();

    public HostResolver(Class<?> anchor) {
        this(anchor.getClassLoader(), readArchive(anchor), false);
    }

    public HostResolver(ClassLoader loader, Map<String, byte[]> bytecode) {
        this(loader, bytecode, true);
    }

    private HostResolver(ClassLoader loader, Map<String, byte[]> bytecode, boolean fixtures) {
        this.loader = loader;
        var index = new LinkedHashMap<String, Entry>();
        for (var item : bytecode.entrySet()) {
            var model = ClassFile.of().parse(item.getValue());
            var annotation = metadata(model);
            Entry entry = new Entry(model.thisClass().asInternalName(), annotation, loader, fixtures ? item.getValue() : null);
            index.put(model.thisClass().asInternalName(), entry);
            if (annotation != null && annotation.k() == 1 && annotation.d2().length > 0) {
                String identity = annotation.d2()[0];
                if (identity.startsWith("L") && identity.endsWith(";")) {
                    String semantic = identity.substring(1, identity.length() - 1);
                    String previous = aliases.putIfAbsent(semantic, model.thisClass().asInternalName());
                    if (previous != null && !previous.equals(model.thisClass().asInternalName()))
                        throw unsupported("duplicate Metadata identity " + semantic);
                }
            }
        }
        entries = Collections.unmodifiableMap(index);
    }

    private static Map<String, byte[]> readArchive(Class<?> anchor) {
        try {
            Path path = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            try (var jar = new JarFile(path.toFile())) {
                for (var entry : Collections.list(jar.entries())) {
                    String n = entry.getName();
                    if (n.endsWith(".class") && (n.startsWith("com/xuncorp/") || n.startsWith("com/moriafly/")
                            || n.startsWith("androidx/compose/ui/"))) {
                        try (var stream = jar.getInputStream(entry)) { bytes.put(n, stream.readAllBytes()); }
                    }
                }
            }
            return bytes;
        } catch (Exception failure) { throw new IllegalStateException("无法读取 SPW 宿主元数据", failure); }
    }

    private static Metadata metadata(ClassModel model) {
        var attr = model.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations());
        if (attr.isEmpty()) return null;
        for (var a : attr.get().annotations()) if (a.className().equalsString("Lkotlin/Metadata;")) {
            Map<String, Object> values = new HashMap<>();
            for (var e : a.elements()) {
                Object value = switch (e.value()) {
                    case AnnotationValue.OfInt i -> i.intValue();
                    case AnnotationValue.OfString s -> s.stringValue();
                    case AnnotationValue.OfArray array -> {
                        if (e.name().equalsString("mv") || e.name().equalsString("bv"))
                            yield array.values().stream().mapToInt(v -> ((AnnotationValue.OfInt)v).intValue()).toArray();
                        yield array.values().stream().map(v -> ((AnnotationValue.OfString)v).stringValue()).toArray(String[]::new);
                    }
                    default -> throw unsupported("unsupported Metadata annotation value");
                };
                values.put(e.name().stringValue(), value);
            }
            return (Metadata) Proxy.newProxyInstance(Metadata.class.getClassLoader(), new Class<?>[]{Metadata.class},
                    (proxy, method, args) -> method.getName().equals("annotationType") ? Metadata.class
                            : values.getOrDefault(method.getName(), method.getDefaultValue()));
        }
        return null;
    }

    private static final class Entry {
        final String name;
        final ClassLoader loader;
        final byte[] fixture;
        final Metadata annotation;
        final Set<String> tokens;
        private KotlinClassMetadata metadata;
        Entry(String name, Metadata annotation, ClassLoader loader, byte[] fixture) {
            this.name = name; this.annotation = annotation; this.loader = loader; this.fixture = fixture;
            tokens = annotation == null || (annotation.k() != 2 && annotation.k() != 5) ? Set.of() : new HashSet<>(Arrays.asList(annotation.d2()));
        }
        ClassModel model() {
            if (fixture != null) return ClassFile.of().parse(fixture);
            try (var stream = (loader == null ? ClassLoader.getSystemResourceAsStream(name + ".class") : loader.getResourceAsStream(name + ".class"))) {
                if (stream == null) throw unsupported("missing class resource " + name);
                return ClassFile.of().parse(stream.readAllBytes());
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        synchronized KotlinClassMetadata metadata() {
            if (metadata == null && annotation != null) metadata = KotlinClassMetadata.readLenient(annotation);
            return metadata;
        }
        KmDeclarationContainer declarations() {
            return switch (metadata()) {
                case KotlinClassMetadata.Class c -> c.getKmClass();
                case KotlinClassMetadata.FileFacade f -> f.getKmPackage();
                case KotlinClassMetadata.MultiFileClassPart p -> p.getKmPackage();
                case null, default -> null;
            };
        }
    }

    public Class<?> type(String semantic) {
        return types.computeIfAbsent(semantic, name -> load(aliases.getOrDefault(name.replace('.', '/'), name.replace('.', '/'))));
    }

    private Class<?> load(String internal) {
        try { return Class.forName(internal.replace('/', '.'), false, loader); }
        catch (ClassNotFoundException failure) { throw unsupported("missing type " + internal); }
    }

    public Class<?> facade(String... tokens) {
        var candidates = entries.values().stream().filter(e -> e.annotation != null
                && (e.annotation.k() == 2 || e.annotation.k() == 5) && e.tokens.containsAll(Arrays.asList(tokens))).toList();
        return load(unique(candidates, "facade " + Arrays.toString(tokens)).name);
    }

    private Entry entry(Class<?> type) {
        var found = entries.get(type.getName().replace('.', '/'));
        if (found != null) return found;
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw unsupported("missing bytecode " + type.getName());
            var model = ClassFile.of().parse(stream.readAllBytes());
            return new Entry(type.getName().replace('.', '/'), metadata(model), type.getClassLoader(), null);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private String mapped(String descriptor) {
        var result = new StringBuilder();
        for (int i = 0; i < descriptor.length(); i++) {
            char c = descriptor.charAt(i);
            if (c != 'L') { result.append(c); continue; }
            int end = descriptor.indexOf(';', i);
            String name = descriptor.substring(i + 1, end);
            result.append('L').append(aliases.getOrDefault(name, name)).append(';');
            i = end;
        }
        return result.toString();
    }

    private static String descriptor(Method method) {
        return java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
    }

    private boolean compatible(String expected, Method actual) {
        String signature = mapped(expected), found = descriptor(actual);
        if (signature.equals(found)) return true;
        if (Modifier.isAbstract(actual.getModifiers())) return false;
        // The host optimizer removes unused trailing Compose change flags from internal accessors.
        while (signature.contains("Composer;I)")) {
            signature = signature.replace("Composer;I)", "Composer;)");
            if (signature.equals(found)) return true;
        }
        if (subsequence(signature, found)) return true;
        // R8 can retain only the synthetic default bridge, with its final argument mask.
        if (actual.isSynthetic() && found.contains("I)") && code(actual).code().stream().flatMap(c -> c.elementStream())
                .anyMatch(i -> i instanceof Instruction instruction && instruction.opcode() == Opcode.IAND))
            return subsequence(signature, found.replace("I)", ")"));
        return false;
    }

    private static boolean subsequence(String expected, String actual) {
        var a = MethodTypeDesc.ofDescriptor(expected);
        var b = MethodTypeDesc.ofDescriptor(actual);
        if (!a.returnType().equals(b.returnType())) return false;
        int j = 0;
        for (int i = 0; i < a.parameterCount(); i++) {
            if (j < b.parameterCount() && a.parameterType(i).equals(b.parameterType(j))) { j++; continue; }
        }
        return j == b.parameterCount();
    }

    public Method function(Class<?> owner, String semantic) { return function(owner, semantic, m -> true); }

    public Method composable(String semantic, int count, Class<?> first) {
        String key = "composable:" + semantic + ":" + count + ":" + first;
        return methods.computeIfAbsent(key, unused -> {
            List<Method> found = new ArrayList<>();
            for (var e : entries.values()) {
                String leaf = semantic.substring(semantic.lastIndexOf('.') + 1);
                if (e.annotation == null || (e.annotation.k() != 2 && e.annotation.k() != 5) || !e.tokens.contains(leaf)) continue;
                var signatures = e.declarations().getFunctions().stream().filter(f -> f.getName().equals(leaf))
                        .map(JvmExtensionsKt::getSignature).filter(Objects::nonNull).toList();
                for (var m : load(e.name).getDeclaredMethods())
                    if (m.getReturnType() == void.class && m.getParameterCount() == count && (first == null || m.getParameterTypes()[0] == first)
                            && signatures.stream().anyMatch(s -> compatible(s.getDescriptor(), m))) found.add(m);
            }
            var traced = found.stream().filter(m -> traceMatches(m, semantic)).toList();
            return accessible(unique(traced.isEmpty() ? found : traced, key));
        });
    }

    private boolean traceMatches(Method method, String semantic) {
        return code(method).code().stream().flatMap(c -> c.elementStream())
                .anyMatch(e -> e instanceof ConstantInstruction c && c.constantValue() instanceof String text
                        && (text.startsWith(semantic + " (") || text.contains("." + semantic + " (")));
    }

    public Method function(Class<?> owner, String semantic, Predicate<Method> shape) {
        var declarations = entry(owner).declarations();
        if (declarations == null) throw unsupported("no declarations for " + owner.getName());
        var signatures = declarations.getFunctions().stream().filter(f -> f.getName().equals(semantic))
                .map(JvmExtensionsKt::getSignature).filter(Objects::nonNull).toList();
        var candidates = Arrays.stream(owner.getDeclaredMethods()).filter(shape)
                .filter(m -> signatures.stream().anyMatch(s -> compatible(s.getDescriptor(), m))).toList();
        var named = candidates.stream().filter(m -> signatures.stream().anyMatch(s -> s.getName().equals(m.getName()))).toList();
        var selected = unique(named.isEmpty() ? candidates : named, owner.getName() + "." + semantic);
        selected.setAccessible(true);
        return methods.computeIfAbsent(owner.getName() + ":" + semantic + ":" + descriptor(selected), key -> selected);
    }

    public Method getter(Class<?> owner, String semantic) {
        String key = owner.getName() + ":get:" + semantic;
        return methods.computeIfAbsent(key, unused -> {
            var p = property(owner, semantic);
            var signature = JvmExtensionsKt.getGetterSignature(p);
            if (signature == null) throw unsupported("no getter " + key);
            var candidates = Arrays.stream(owner.getDeclaredMethods()).filter(m -> compatible(signature.getDescriptor(), m)).toList();
            var named = candidates.stream().filter(m -> m.getName().equals(signature.getName())).toList();
            if (!named.isEmpty()) return accessible(unique(named, key));
            if (JvmExtensionsKt.getFieldSignature(p) == null && candidates.size() == 1) return accessible(candidates.getFirst());
            var field = field(owner, semantic);
            return accessible(unique(candidates.stream().filter(m -> referencedFields(m).equals(List.of(field))).toList(), key));
        });
    }

    public Object singleton(Class<?> owner) {
        Field member = fields.computeIfAbsent(owner.getName() + ":singleton", unused -> {
            var candidates = Arrays.stream(owner.getDeclaredFields()).filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == owner).toList();
            if (candidates.isEmpty() && owner.getDeclaringClass() != null)
                candidates = Arrays.stream(owner.getDeclaringClass().getDeclaredFields()).filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == owner).toList();
            return accessible(unique(candidates, "singleton " + owner.getName()));
        });
        try { return member.get(null); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    public Object receiver(Method method) { return Modifier.isStatic(method.getModifiers()) ? null : singleton(method.getDeclaringClass()); }

    private KmProperty property(Class<?> owner, String semantic) {
        var declarations = entry(owner).declarations();
        if (declarations == null) throw unsupported("no properties for " + owner.getName());
        return unique(declarations.getProperties().stream().filter(p -> p.getName().equals(semantic)).toList(), owner.getName() + "." + semantic);
    }

    public Field field(Class<?> owner, String semantic) {
        String key = owner.getName() + ":field:" + semantic;
        Field known = fields.get(key);
        if (known != null) return known;
        var found = propertyFields.computeIfAbsent(owner, this::mapFields).get(semantic);
        if (found == null) throw unsupported("ambiguous/missing property field " + key);
        fields.putIfAbsent(key, found);
        return found;
    }

    public Field initializedField(Class<?> owner, String parameter) {
        Field field = propertyFields.computeIfAbsent(owner, this::mapFields).get("@" + parameter);
        if (field == null) throw unsupported("missing constructor-state association " + owner.getName() + ":" + parameter);
        return field;
    }

    private Map<String, Field> mapFields(Class<?> owner) {
        Map<String, Field> result = new LinkedHashMap<>();
        var e = entry(owner);
        var declarations = e.declarations();
        if (declarations == null) throw unsupported("no field metadata " + owner.getName());
        for (var p : declarations.getProperties()) {
            var signature = JvmExtensionsKt.getFieldSignature(p);
            if (signature == null) continue;
            try {
                Field field = owner.getDeclaredField(signature.getName());
                if (field.getType().descriptorString().equals(mapped(signature.getDescriptor()))) result.put(p.getName(), accessible(field));
            } catch (NoSuchFieldException ignored) { }
        }
        if (e.metadata() instanceof KotlinClassMetadata.Class metadata) for (var ctor : metadata.getKmClass().getConstructors()) {
            var signature = JvmExtensionsKt.getSignature(ctor);
            if (signature == null) continue;
            String expected = mapped(signature.getDescriptor());
            String withoutMarker = expected.replace("Lkotlin/jvm/internal/DefaultConstructorMarker;)V", ")V");
            var real = e.model().methods().stream().filter(m -> m.methodName().equalsString("<init>")
                    && (m.methodType().equalsString(expected) || m.methodType().equalsString(withoutMarker))
                    && m.code().stream().flatMap(c -> c.elementStream()).anyMatch(i -> i instanceof FieldInstruction f
                            && f.opcode() == Opcode.PUTFIELD && f.owner().asInternalName().equals(e.name))).toList();
            if (real.size() != 1 || real.getFirst().code().isEmpty()) continue;
            Map<Integer, String> parameters = new HashMap<>();
            Map<Integer, String> parameterTypes = new HashMap<>();
            var type = MethodTypeDesc.ofDescriptor(real.getFirst().methodType().stringValue());
            int slot = 1;
            for (int i = 0; i < ctor.getValueParameters().size(); i++) {
                parameters.put(slot, ctor.getValueParameters().get(i).getName());
                String desc = type.parameterType(i).descriptorString(); parameterTypes.put(slot, desc); slot += desc.equals("J") || desc.equals("D") ? 2 : 1;
            }
            CodeElement previous = null;
            String loadedParameter = null;
            for (var instruction : real.getFirst().code().get()) {
                if (instruction instanceof LoadInstruction load && parameters.containsKey(load.slot())) loadedParameter = parameters.get(load.slot());
                if (instruction instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD
                        && f.owner().asInternalName().equals(e.name) && loadedParameter != null) {
                    String parameter = loadedParameter;
                    if (previous instanceof InvokeInstruction factory && factory.owner().asInternalName().equals("androidx/compose/runtime/SnapshotIntStateKt")
                            && factory.name().equalsString("mutableIntStateOf")) {
                        try { result.put("@" + parameter, accessible(owner.getDeclaredField(f.name().stringValue()))); }
                        catch (NoSuchFieldException failure) { throw unsupported("state field missing " + parameter); }
                    }
                    var delegated = declarations.getProperties().stream().filter(p -> p.getName().equals(parameter))
                            .map(JvmExtensionsKt::getFieldSignature).filter(Objects::nonNull)
                            .anyMatch(s -> s.getName().endsWith("$delegate") && mapped(s.getDescriptor()).equals(f.type().stringValue()));
                    if (delegated) try { result.putIfAbsent(parameter, accessible(owner.getDeclaredField(f.name().stringValue()))); }
                    catch (NoSuchFieldException failure) { throw unsupported("delegate field missing " + parameter); }
                }
                if (instruction instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD
                        && f.owner().asInternalName().equals(e.name) && previous instanceof LoadInstruction load) {
                    String semantic = parameters.get(load.slot());
                    if (semantic != null && f.type().equalsString(parameterTypes.get(load.slot()))) {
                        try { result.putIfAbsent(semantic, accessible(owner.getDeclaredField(f.name().stringValue()))); }
                        catch (NoSuchFieldException failure) { throw unsupported("constructor field missing " + semantic); }
                    }
                }
                if (instruction instanceof Instruction) previous = instruction;
                if (instruction instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD) loadedParameter = null;
            }
        }
        boolean changed;
        do {
            changed = false;
            for (var p : declarations.getProperties()) {
                if (result.containsKey(p.getName())) continue;
                var signature = JvmExtensionsKt.getFieldSignature(p);
                if (signature == null) continue;
                var candidates = Arrays.stream(owner.getDeclaredFields()).filter(f -> !result.containsValue(f)
                        && f.getType().descriptorString().equals(mapped(signature.getDescriptor()))).toList();
                if (candidates.size() == 1) { result.put(p.getName(), accessible(candidates.getFirst())); changed = true; }
            }
        } while (changed);
        return Collections.unmodifiableMap(result);
    }

    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private static Field accessible(Field field) { field.setAccessible(true); return field; }

    public MethodModel code(Method method) {
        return unique(entry(method.getDeclaringClass()).model().methods().stream().filter(m -> m.methodName().equalsString(method.getName())
                && m.methodType().equalsString(descriptor(method))).toList(), "bytecode " + method);
    }

    public List<Method> calls(Method method, Predicate<Method> predicate) {
        List<Method> result = new ArrayList<>();
        code(method).code().ifPresent(body -> {
            for (var e : body) if (e instanceof InvokeInstruction i && !i.name().equalsString("<init>")) {
                Class<?> owner = load(i.owner().asInternalName());
                var called = Arrays.stream(owner.getDeclaredMethods()).filter(m -> m.getName().equals(i.name().stringValue())
                        && descriptor(m).equals(i.type().stringValue())).toList();
                if (called.size() == 1 && predicate.test(called.getFirst())) result.add(accessible(called.getFirst()));
            }
        });
        return List.copyOf(result);
    }

    public List<Field> referencedFields(Method method) {
        List<Field> result = new ArrayList<>();
        code(method).code().ifPresent(body -> {
            for (var e : body) if (e instanceof FieldInstruction f && (f.opcode() == Opcode.GETFIELD || f.opcode() == Opcode.GETSTATIC)) {
                try {
                    Field field = load(f.owner().asInternalName()).getDeclaredField(f.name().stringValue());
                    if (!result.contains(field)) result.add(accessible(field));
                } catch (NoSuchFieldException failure) { throw unsupported("missing referenced field " + f.name()); }
            }
        });
        return List.copyOf(result);
    }

    public List<String> sqlQueries(Method method) {
        var attribute = code(method).findAttribute(java.lang.classfile.Attributes.runtimeInvisibleAnnotations());
        if (attribute.isEmpty()) return List.of();
        return attribute.get().annotations().stream().filter(a -> a.className().equalsString("Landroidx/room/Query;"))
                .flatMap(a -> a.elements().stream()).filter(e -> e.name().equalsString("value"))
                .map(e -> ((AnnotationValue.OfString)e.value()).stringValue()).toList();
    }

    public List<String> strings(Method method) {
        return code(method).code().stream().flatMap(c -> c.elementStream())
                .filter(e -> e instanceof ConstantInstruction c && c.constantValue() instanceof String)
                .map(e -> (String)((ConstantInstruction)e).constantValue()).toList();
    }

    public List<Integer> integers(Method method) {
        return code(method).code().stream().flatMap(c -> c.elementStream())
                .filter(e -> e instanceof ConstantInstruction c && c.constantValue() instanceof Integer)
                .map(e -> (Integer)((ConstantInstruction)e).constantValue()).toList();
    }

    public String caller(Method target) {
        return callers.computeIfAbsent(target.toString(), unused -> {
            String owner = target.getDeclaringClass().getName().replace('.', '/');
            var candidates = entries.values().stream().filter(e -> !e.name.equals(owner)
                    && !e.name.startsWith(owner + "$") && references(e, target) > 0).toList();
            return unique(candidates, "caller of " + target).name.replace('/', '.');
        });
    }

    public String callerIn(Method target, Class<?> caller) {
        if (references(entry(caller), target) < 1) throw unsupported("missing call from " + caller.getName() + " to " + target);
        return caller.getName();
    }

    private static long references(Entry e, Method target) {
        String owner = target.getDeclaringClass().getName().replace('.', '/');
        return e.model().methods().stream().flatMap(m -> m.code().stream()).flatMap(c -> c.elementStream())
                .filter(i -> i instanceof InvokeInstruction call && call.owner().asInternalName().equals(owner)
                        && call.name().equalsString(target.getName()) && call.type().equalsString(descriptor(target))).count();
    }

    public static <T> T unique(List<T> candidates, String purpose) {
        if (candidates.size() != 1) throw unsupported(purpose + ": expected 1, found " + candidates.size() + " " + candidates);
        return candidates.getFirst();
    }

    private static IllegalStateException unsupported(String detail) { return new IllegalStateException("SPW 接口不兼容：" + detail); }
}
