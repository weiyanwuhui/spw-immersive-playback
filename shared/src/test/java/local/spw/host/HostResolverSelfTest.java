package local.spw.host;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.util.*;

/** Rename owners, members and call sites while retaining Kotlin semantic metadata. */
public final class HostResolverSelfTest {
    private static final ClassFile CLASS_FILE = ClassFile.of();
    private static byte[] resource(String name) throws IOException {
        try (var stream = HostResolverSelfTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            return Objects.requireNonNull(stream).readAllBytes();
        }
    }
    private static String member(String owner, String name, String prefix) {
        return owner.startsWith("fixtures/") && !name.startsWith("<") ? prefix + name : name;
    }
    private static ClassDesc type(ClassDesc original, String prefix) {
        return ClassDesc.ofDescriptor(original.descriptorString().replace("Lfixtures/TimedRow;", "Lfixtures/" + prefix + "Row;")
                .replace("Lfixtures/TimedCaller;", "Lfixtures/" + prefix + "Caller;"));
    }
    private static MethodTypeDesc type(MethodTypeDesc original, String prefix) {
        return MethodTypeDesc.ofDescriptor(original.descriptorString().replace("Lfixtures/TimedRow;", "Lfixtures/" + prefix + "Row;"));
    }
    private static byte[] rename(String source, String prefix, boolean missingEnd, boolean duplicateGetter, boolean noMetadata) throws IOException {
        var parsed = CLASS_FILE.parse(resource(source));
        var newOwner = type(parsed.thisClass().asSymbol(), prefix);
        return CLASS_FILE.transformClass(parsed, newOwner, (builder, element) -> {
            if (noMetadata && element instanceof java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute) return;
            if (element instanceof FieldModel field) {
                builder.withField(member(parsed.thisClass().asInternalName(), field.fieldName().stringValue(), prefix),
                        type(field.fieldTypeSymbol(), prefix), f -> field.forEach(f::with));
            } else if (element instanceof MethodModel method) {
                if (missingEnd && method.methodName().equalsString("getEndTime")) return;
                builder.withMethod(member(parsed.thisClass().asInternalName(), method.methodName().stringValue(), prefix),
                        type(method.methodTypeSymbol(), prefix), method.flags().flagsMask(), m -> {
                    for (var e : method) {
                        if (e instanceof CodeModel code) m.transformCode(code, (b, instruction) -> {
                            if (instruction instanceof FieldInstruction f)
                                b.fieldAccess(f.opcode(), type(f.owner().asSymbol(), prefix), member(f.owner().asInternalName(), f.name().stringValue(), prefix), type(f.typeSymbol(), prefix));
                            else if (instruction instanceof InvokeInstruction call)
                                b.invoke(call.opcode(), type(call.owner().asSymbol(), prefix), member(call.owner().asInternalName(), call.name().stringValue(), prefix), type(call.typeSymbol(), prefix), call.isInterface());
                            else b.with(instruction);
                        });
                        else m.with(e);
                    }
                });
            } else builder.with(element);
            if (duplicateGetter && element instanceof MethodModel method && method.methodName().equalsString("getEndTime"))
                builder.withMethodBody("extraEndGetter", MethodTypeDesc.of(ConstantDescs.CD_long), 1,
                        b -> b.aload(0).getfield(newOwner, prefix + "endTime", ConstantDescs.CD_long).lreturn());
        });
    }
    private static final class MemoryLoader extends ClassLoader {
        final Map<String, byte[]> bytes;
        MemoryLoader(Map<String, byte[]> bytes) { super(HostResolverSelfTest.class.getClassLoader()); this.bytes = bytes; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] body = bytes.get(name);
            if (body == null) throw new ClassNotFoundException(name);
            return defineClass(name, body, 0, body.length);
        }
        @Override public InputStream getResourceAsStream(String name) {
            byte[] body = bytes.get(name.replace('/', '.').replaceAll("\\.class$", ""));
            return body == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(body);
        }
    }
    private static HostResolver resolver(String prefix, boolean missingEnd, boolean duplicateGetter, boolean noMetadata) throws Exception {
        var bytes = Map.of("fixtures." + prefix + "Row", rename("fixtures.TimedRow", prefix, missingEnd, duplicateGetter, noMetadata),
                "fixtures." + prefix + "Caller", rename("fixtures.TimedCaller", prefix, false, false, false));
        return new HostResolver(new MemoryLoader(bytes), bytes);
    }
    private static void rejected(Runnable operation) {
        try { operation.run(); throw new AssertionError("Incompatible contract accepted"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("不兼容"); }
    }
    public static void main(String[] args) throws Exception {
        for (String prefix : List.of("alpha", "omega")) {
            var resolver = resolver(prefix, false, false, false);
            var row = resolver.type("fixtures.TimedRow");
            assert row.getName().equals("fixtures." + prefix + "Row");
            Object value = row.getConstructor(long.class, long.class, String.class).newInstance(17L, 91L, "lyrics");
            var start = resolver.getter(row, "startTime");
            assert start.invoke(value).equals(17L);
            assert resolver.getter(row, "endTime").invoke(value).equals(91L);
            assert resolver.getter(row, "text").invoke(value).equals("lyrics");
            assert resolver.getter(row, "startTime") == start : "Uncached member";
            var caller = resolver.type("fixtures.TimedCaller");
            var read = resolver.function(caller, "read");
            assert read.invoke(caller.getConstructor().newInstance(), value).equals(17L);
            assert resolver.caller(start).equals(caller.getName());
            assert resolver.callerIn(start, caller).equals(caller.getName());
            rejected(() -> resolver.callerIn(resolver.getter(row, "endTime"), caller));
        }
        var missing = resolver("missing", true, false, false);
        rejected(() -> missing.getter(missing.type("fixtures.TimedRow"), "endTime"));
        var duplicate = resolver("duplicate", false, true, false);
        rejected(() -> duplicate.getter(duplicate.type("fixtures.TimedRow"), "endTime"));
        var noMetadata = resolver("plain", false, false, true);
        rejected(() -> noMetadata.getter(noMetadata.type("fixtures.plainRow"), "endTime"));
        System.out.println("PASS: renamed classes/methods/fields/callers, semantic start/end identity, cached members; missing, ambiguous and missing-Metadata contracts rejected.");
    }
}
