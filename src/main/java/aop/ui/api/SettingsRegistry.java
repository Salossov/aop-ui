package aop.ui.api;

import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import aop.ui.config.NeoForgeConfigs;

/** Opt-in registration. AOP: UI never exposes unregistered mods' configuration. */
public final class SettingsRegistry {
    public record Provider(String modId, String name, UiTheme theme) { }
    private static final ConcurrentSkipListMap<String, Provider> PROVIDERS = new ConcurrentSkipListMap<>();
    private static final java.util.Map<String,java.util.Set<net.neoforged.neoforge.common.ModConfigSpec>> SOURCES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,String> CATEGORIES = new java.util.concurrent.ConcurrentHashMap<>();
    private record Source(String modId, net.neoforged.neoforge.common.ModConfigSpec spec) { }
    private static final java.util.Map<Source,java.util.Map<String,net.neoforged.neoforge.common.ModConfigSpec.BooleanValue>> DEPENDENCIES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Call after registering the mod's configs, before reading startup content switches. */
    public static void register(String modId, String displayName, UiTheme theme) {
        if (!modId.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid mod ID");
        var provider = new Provider(modId, displayName, java.util.Objects.requireNonNull(theme));
        if (PROVIDERS.putIfAbsent(modId, provider) != null) throw new IllegalStateException("Settings already registered: " + modId);
        NeoForgeConfigs.primeStartup(modId);
    }

    public static List<Provider> providers() { return List.copyOf(PROVIDERS.values()); }
    public static Provider provider(String modId) { return PROVIDERS.get(modId); }
    /** Explicit source allowlist. Omitting sources retains all of this provider's NeoForge configs. */
    public static void register(String modId,String displayName,UiTheme theme,net.neoforged.neoforge.common.ModConfigSpec... sources) {
        if(sources.length==0){register(modId,displayName,theme);return;}
        if(PROVIDERS.containsKey(modId))throw new IllegalStateException("Settings already registered: "+modId);
        SOURCES.put(modId,java.util.Set.of(sources));register(modId,displayName,theme);
    }
    public static boolean includesSource(String modId,net.neoforged.neoforge.common.ModConfigSpec source) {
        var sources=SOURCES.get(modId);return sources==null||sources.contains(source);
    }
    /** Optional category label; category paths still come from the existing config. */
    public static void registerCategory(String modId,String category,String translationKey) {
        if(provider(modId)==null)throw new IllegalArgumentException("Register the provider first");
        CATEGORIES.put(modId+"/"+category,java.util.Objects.requireNonNull(translationKey));
    }
    public static String categoryTranslation(String modId,String category) {return CATEGORIES.getOrDefault(modId+"/"+category,"aopui.category."+category);}

    /**
     * Declare an enabled-if relationship within one config. Stored child values are never changed.
     * Use enabled() when consuming a dependent boolean to apply the same rule outside the screen.
     */
    public static synchronized void registerDependency(String modId,net.neoforged.neoforge.common.ModConfigSpec spec,
            net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> child,
            net.neoforged.neoforge.common.ModConfigSpec.BooleanValue parent) {
        if(provider(modId)==null || !includesSource(modId,spec))throw new IllegalArgumentException("Register the config source first");
        if(spec.getValues().get(child.getPath())!=child || spec.getValues().get(parent.getPath())!=parent)
            throw new IllegalArgumentException("Dependencies must use values from the same config");
        String path=String.join(".",child.getPath());
        var rules=DEPENDENCIES.computeIfAbsent(new Source(modId,spec),k->new java.util.concurrent.ConcurrentHashMap<>());
        if(rules.containsKey(path))throw new IllegalArgumentException("Dependency already registered: "+path);
        var cursor=parent;
        while(cursor!=null) {
            String key=String.join(".",cursor.getPath());
            if(key.equals(path))throw new IllegalArgumentException("Cyclic settings dependency");
            cursor=rules.get(key);
        }
        rules.put(path,parent);
    }
    public static net.neoforged.neoforge.common.ModConfigSpec.BooleanValue dependency(String modId,
            net.neoforged.neoforge.common.ModConfigSpec spec,String childPath) {
        var rules=DEPENDENCIES.get(new Source(modId,spec));return rules==null?null:rules.get(childPath);
    }
    public static boolean enabled(String modId,net.neoforged.neoforge.common.ModConfigSpec spec,
            net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        if(!value.get())return false;
        var parent=dependency(modId,spec,String.join(".",value.getPath()));
        return parent==null||enabled(modId,spec,parent);
    }
    private SettingsRegistry() { }
}
