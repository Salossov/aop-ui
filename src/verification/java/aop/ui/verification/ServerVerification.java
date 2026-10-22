package aop.ui.verification;

import aop.ui.config.*;
import com.google.gson.*;
import java.nio.file.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid="aop_ui_verification")
public final class ServerVerification {
    private static int ticks;
    private static Path folder(){return Path.of(System.getProperty("aopui.remoteReport"));}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if(System.getProperty("aopui.verify.server")==null)return;
        if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p && p.getGameProfile().getName().equals("AopUiAdmin"))p.getServer().getPlayerList().op(p.getGameProfile());
    }
    @SubscribeEvent public static void started(ServerStartedEvent e) {
        if("network".equals(System.getProperty("aopui.verify.server"))) {
            try {
                Files.createDirectories(folder());
                for(String name:new String[]{"admin","viewer","viewer-ready","server","restart"})Files.deleteIfExists(folder().resolve(name+".json"));
                var d=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(c->c.scope()==ModConfig.Type.SERVER).findFirst().orElseThrow();
                var change=new JsonObject();change.addProperty("world.count",4);d.apply(d.snapshot().get("revision").getAsString(),change);
            }catch(Exception error){writeFailure("server",error);e.getServer().halt(false);}
            return;
        }
        if(!"restart".equals(System.getProperty("aopui.verify.server")))return;
        try {
            var d=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(c->c.scope()==ModConfig.Type.STARTUP).findFirst().orElseThrow();
            boolean effective=d.snapshot().getAsJsonObject("effective").get("content").getAsBoolean();
            if(effective)throw new AssertionError("Saved startup change did not take effect after process restart");
            var change=new JsonObject();change.addProperty("content",true);d.apply(d.snapshot().get("revision").getAsString(),change);
            Files.createDirectories(folder());Files.writeString(folder().resolve("restart.json"),"{\"passed\":true,\"startup_after_process_restart\":false}");
        } catch(Throwable error){writeFailure("restart",error);}
        e.getServer().halt(false);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        if(!"network".equals(System.getProperty("aopui.verify.server")))return;
        ticks++;
        try {
            if(Files.exists(folder().resolve("admin.json"))&&Files.exists(folder().resolve("viewer.json"))) {
                var admin=JsonParser.parseString(Files.readString(folder().resolve("admin.json"))).getAsJsonObject();
                var viewer=JsonParser.parseString(Files.readString(folder().resolve("viewer.json"))).getAsJsonObject();
                if(!admin.get("passed").getAsBoolean()||!viewer.get("passed").getAsBoolean())throw new AssertionError("Client network scenario failed");
                var d=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(c->c.scope()==ModConfig.Type.SERVER).findFirst().orElseThrow();
                if(d.snapshot().getAsJsonObject("values").get("world.count").getAsInt()!=4)throw new AssertionError("Server value was not restored");
                Files.writeString(folder().resolve("server.json"),"{\"passed\":true,\"server_count\":4}");e.getServer().halt(false);
            }
            if(ticks>12000)throw new AssertionError("Network verification timed out");
        } catch(Throwable error){writeFailure("server",error);e.getServer().halt(false);}
    }
    private static void writeFailure(String name,Throwable error){
        com.mojang.logging.LogUtils.getLogger().error("AOP UI dedicated server verification failed",error);
        try{Files.createDirectories(folder());var j=new JsonObject();j.addProperty("passed",false);j.addProperty("error",error.toString());Files.writeString(folder().resolve(name+".json"),j.toString());}catch(Exception ex){throw new RuntimeException(ex);}
    }
}
