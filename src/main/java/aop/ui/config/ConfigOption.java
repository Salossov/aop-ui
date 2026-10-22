package aop.ui.config;

import com.google.gson.*;
import java.util.*;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** A typed view of an existing value, including its original validator. */
public record ConfigOption(ModConfigSpec.ConfigValue<?> value, ModConfig.Type scope) {
    public String path() { return String.join(".", value.getPath()); }
    public String category() { return value.getPath().size() > 1 ? value.getPath().getFirst() : "general"; }
    public String translationKey(String modId) {
        var key = value.getSpec().getTranslationKey();
        return key != null ? key : "aopui.option." + modId + "." + path();
    }
    public String description() { return Objects.toString(value.getSpec().getComment(), ""); }
    public boolean restart() { return scope == ModConfig.Type.STARTUP || value.getSpec().restartType() != ModConfigSpec.RestartType.NONE; }
    public String kind() {
        Object v = value.getDefault();
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Enum<?>) return "enum";
        if (v instanceof Integer || v instanceof Long) return "integer";
        if (v instanceof Number) return "decimal";
        if (v instanceof List<?>) return "list";
        return "string";
    }
    public List<String> choices() {
        if (!(value.getDefault() instanceof Enum<?> e)) return List.of();
        return Arrays.stream(e.getDeclaringClass().getEnumConstants()).filter(value.getSpec()::test).map(Enum::name).toList();
    }
    public double minimum() { var r = value.getSpec().getRange(); return r != null && r.getMin() instanceof Number n ? n.doubleValue() : -Double.MAX_VALUE; }
    public double maximum() { var r = value.getSpec().getRange(); return r != null && r.getMax() instanceof Number n ? n.doubleValue() : Double.MAX_VALUE; }
    public boolean slider() { return (kind().equals("decimal") || kind().equals("integer")) && maximum() > minimum() && maximum() - minimum() <= 256; }
    public JsonElement encode(Object v) { return new Gson().toJsonTree(v instanceof Enum<?> e ? e.name() : v); }

    public Object decode(JsonElement input) {
        if (input == null || input.isJsonNull()) throw invalid();
        Object def = value.getDefault();
        Object decoded;
        try {
            if (def instanceof List<?>) {
                if (!input.isJsonArray() || input.getAsJsonArray().size() > 256) throw invalid();
                List<String> result = new ArrayList<>();
                for (var e : input.getAsJsonArray()) {
                    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > 1024) throw invalid();
                    result.add(e.getAsString());
                }
                decoded = result;
            } else {
                if (!input.isJsonPrimitive()) throw invalid();
                var p = input.getAsJsonPrimitive();
                if (def instanceof Boolean) { if (!p.isBoolean()) throw invalid(); decoded = p.getAsBoolean(); }
                else if (def instanceof Number) {
                    if (!p.isNumber()) throw invalid();
                    var n = p.getAsBigDecimal();
                    if (def instanceof Integer) decoded = n.intValueExact();
                    else if (def instanceof Long) decoded = n.longValueExact();
                    else { double d = n.doubleValue(); if (!Double.isFinite(d)) throw invalid(); decoded = d; }
                } else {
                    if (!p.isString() || p.getAsString().length() > 4096) throw invalid();
                    if (def instanceof Enum<?> e) {
                        decoded = Arrays.stream(e.getDeclaringClass().getEnumConstants()).filter(v -> v.name().equals(p.getAsString())).findFirst().orElseThrow(ConfigOption::invalid);
                    } else decoded = p.getAsString();
                }
            }
            if (!value.getSpec().test(decoded)) throw invalid();
            return decoded;
        } catch (ArithmeticException | UnsupportedOperationException | ClassCastException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("aopui.error.invalid"); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void set(Object newValue) { ((ModConfigSpec.ConfigValue)value).set(newValue); }
}
