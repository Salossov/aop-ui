package aop.ui.config;

import aop.ui.api.SettingsRegistry;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.neoforged.fml.config.*;
import net.neoforged.neoforge.common.ModConfigSpec;

/** All loader-specific config discovery and live SERVER synchronization live here. */
public final class NeoForgeConfigs {
    public static List<ConfigDocument> documents(String modId) {
        if (SettingsRegistry.provider(modId) == null) return List.of();
        return ModConfigs.getModConfigs(modId).stream().filter(c -> c.getSpec() instanceof ModConfigSpec s && SettingsRegistry.includesSource(modId,s))
                .map(c -> ConfigDocument.of(key(c), modId, c.getType(), c.getFileName(), (ModConfigSpec)c.getSpec(), () -> c.getLoadedConfig().config()))
                .sorted(Comparator.comparing(ConfigDocument::id)).toList();
    }
    public static Optional<ConfigDocument> document(String id) {
        return SettingsRegistry.providers().stream().flatMap(p -> documents(p.modId()).stream()).filter(d -> d.id().equals(id)).findFirst();
    }
    private static String key(ModConfig c) { return c.getModId() + ":" + c.getType().name() + ":" + c.getFileName(); }
    private static ModConfig loaderConfig(ConfigDocument d) {
        return ModConfigs.getModConfigs(d.modId()).stream().filter(c -> key(c).equals(d.id())).findFirst().orElseThrow();
    }
    public static void primeStartup(String modId) { documents(modId).forEach(ConfigDocument::primeStartup); }
    public static String serializeServer(ConfigDocument d) {
        if (d.scope() != ModConfig.Type.SERVER) throw new IllegalArgumentException();
        return new TomlWriter().writeToString(loaderConfig(d).getLoadedConfig().config());
    }
    public static void acceptServer(String id, String toml) {
        var d = document(id).orElseThrow();
        if (d.scope() != ModConfig.Type.SERVER) throw new IllegalArgumentException();
        // FML 4.0 / NeoForge 21.1 compatibility boundary. Same path as initial config synchronization;
        // keeps ModConfig and ModConfigSpec pointing at the same loaded config and posts Reloading.
        ConfigTracker.acceptSyncedConfig(loaderConfig(d), toml.getBytes(StandardCharsets.UTF_8));
    }
    private NeoForgeConfigs() { }
}
