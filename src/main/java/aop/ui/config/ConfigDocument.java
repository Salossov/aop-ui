package aop.ui.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Transactions are limited to one actual NeoForge config file. Unknown keys are untouched. */
public record ConfigDocument(String id, String modId, ModConfig.Type scope, String fileName, ModConfigSpec spec, List<ConfigOption> options, java.util.function.Supplier<? extends UnmodifiableConfig> storage) {
    public static ConfigDocument of(String id, String modId, ModConfig.Type scope, String fileName, ModConfigSpec spec, java.util.function.Supplier<? extends UnmodifiableConfig> storage) {
        List<ConfigOption> options = new ArrayList<>();
        walk(spec.getValues(), scope, options);
        options.sort(Comparator.comparing(ConfigOption::path));
        return new ConfigDocument(id, modId, scope, fileName, spec, List.copyOf(options), storage);
    }
    private static void walk(UnmodifiableConfig node, ModConfig.Type scope, List<ConfigOption> result) {
        for (var e : node.valueMap().values()) {
            if (e instanceof ModConfigSpec.ConfigValue<?> v) result.add(new ConfigOption(v, scope));
            else if (e instanceof UnmodifiableConfig nested) walk(nested, scope, result);
        }
    }
    public void primeStartup() {
        if (scope == ModConfig.Type.STARTUP && spec.isLoaded()) for (var o : options) {
            if (o.value().getSpec().restartType() != ModConfigSpec.RestartType.GAME)
                throw new IllegalStateException("STARTUP settings must declare gameRestart(): " + modId + "/" + o.path());
            o.value().get();
        }
    }
    public JsonObject snapshot() {
        synchronized (spec) {
            if (!spec.isLoaded()) throw new IllegalStateException("aopui.error.unavailable");
            var saved = new JsonObject(); var effective = new JsonObject();
            for (var o : options) {
                // The stored representation may be an enum name, while get() is the effective cached value.
                saved.add(o.path(), o.encode(storage.get().getOrElse(o.value().getPath(), o.value()::getDefault)));
                effective.add(o.path(), o.encode(o.value().get()));
            }
            var result = new JsonObject();
            result.add("values", saved); result.add("effective", effective);
            result.addProperty("revision", revision(saved));
            return result;
        }
    }
    public void apply(String expectedRevision, JsonObject changes) {
        synchronized (spec) {
            var before = snapshot();
            if (!before.get("revision").getAsString().equals(expectedRevision)) throw new IllegalStateException("aopui.error.stale");
            if (changes.size() > 512) throw new IllegalArgumentException("aopui.error.invalid");
            Map<ConfigOption, Object> decoded = new LinkedHashMap<>();
            Map<ConfigOption, Object> old = new LinkedHashMap<>();
            for (var e : changes.entrySet()) {
                var option = options.stream().filter(o -> o.path().equals(e.getKey())).findFirst().orElseThrow(() -> new IllegalArgumentException("aopui.error.invalid"));
                decoded.put(option, option.decode(e.getValue()));
                old.put(option, option.decode(before.getAsJsonObject("values").get(option.path())));
            }
            if (decoded.isEmpty()) return;
            try {
                decoded.forEach(ConfigOption::set);
                spec.save();
            } catch (RuntimeException error) {
                old.forEach(ConfigOption::set);
                // A reload listener can fail after FML's atomic write has completed.
                // Persist the rollback as well; retain both errors if storage itself is unavailable.
                try { spec.save(); } catch(RuntimeException rollbackError) { error.addSuppressed(rollbackError); }
                throw new IllegalStateException("aopui.error.save", error);
            }
        }
    }
    static String revision(JsonObject values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
