package aop.ui.client;

import aop.ui.api.SettingsRegistry;
import aop.ui.config.NeoForgeConfigs;
import aop.ui.network.SettingsNetwork;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;

/** Public client entry point. The registry and server handlers never load this class on a server. */
public final class SettingsClient {
    private record Pending(long deadline, Consumer<JsonObject> receiver) { }
    private static final Map<Long,Pending> PENDING = new HashMap<>();
    private static long sequence;
    public static Screen screen(Screen parent, String modId) { return new SettingsScreen(parent, modId); }
    public static void open(Screen parent, String modId) { Minecraft.getInstance().setScreen(screen(parent,modId)); }
    public static boolean connected() { return Minecraft.getInstance().getConnection() != null && Minecraft.getInstance().level != null; }
    static long request(String operation,String config,String revision,JsonObject changes,Consumer<JsonObject> receiver) {
        long id=++sequence;
        var p=new JsonObject(); p.addProperty("request",id); p.addProperty("operation",operation); p.addProperty("config",config);
        if(revision!=null) p.addProperty("revision",revision);
        if(changes!=null) p.add("changes",changes);
        String json=p.toString();
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>28000) throw new IllegalArgumentException("aopui.error.too_large");
        PENDING.put(id,new Pending(System.currentTimeMillis()+12000,receiver));
        try { PacketDistributor.sendToServer(new SettingsNetwork.Request(json)); }
        catch(RuntimeException error) { PENDING.remove(id);throw error; }
        return id;
    }
    public static void install() { SettingsNetwork.installClient(SettingsClient::receive); }
    private static void receive(JsonObject reply) {
        if(reply.has("sync")) {
            // Integrated servers share the actual spec with their client; replacing it would replace
            // the authoritative server file with an in-memory synchronized client config.
            if(!Minecraft.getInstance().isLocalServer()) NeoForgeConfigs.acceptServer(reply.get("sync").getAsString(),reply.get("toml").getAsString());
            return;
        }
        var pending=PENDING.remove(reply.get("request").getAsLong());
        if(pending!=null) pending.receiver.accept(reply);
    }
    static void tickPending() {
        if(PENDING.isEmpty())return;
        var expired = PENDING.entrySet().stream().filter(e -> e.getValue().deadline < System.currentTimeMillis() || !connected()).toList();
        for(var e:expired) {
            PENDING.remove(e.getKey());
            var error=new JsonObject();error.addProperty("ok",false);error.addProperty("error","aopui.error.connection");
            e.getValue().receiver.accept(error);
        }
    }
    private SettingsClient() { }
}
