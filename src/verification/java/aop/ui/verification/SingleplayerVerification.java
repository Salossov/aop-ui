package aop.ui.verification;

import aop.ui.client.*;
import aop.ui.config.*;
import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Real integrated-server scenario, on an isolated copy of the generated QA world. */
@EventBusSubscriber(modid="aop_ui_verification",value=Dist.CLIENT)
public final class SingleplayerVerification {
    private static int stage,ticks,checks;private static boolean done;private static ConfigDocument document;
    private static volatile Boolean noCheats;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        if(done||!Boolean.getBoolean("aopui.verify.singleplayer"))return;ticks++;
        var mc=Minecraft.getInstance();
        try {
            if(mc.screen instanceof AccessibilityOnboardingScreen){mc.screen.onClose();return;}
            if(ticks>3600)throw new AssertionError("Singleplayer verification timed out at stage "+stage);
            if(!SettingsClient.connected())return;
            if(stage==0) {
                check(mc.isLocalServer(),"Integrated server active");stage=1;
                mc.getSingleplayerServer().execute(()->{var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());noCheats=player!=null&&!player.hasPermissions(4);});
            } else if(stage==1&&noCheats!=null) {
                check(noCheats,"Host has no OP4/cheats");
                mc.setScreen(new PauseScreen(true));
                var entry=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals("AOP: UI")).findFirst().orElseThrow();
                check(entry.active,"Pause-menu entry available");entry.onPress();
                mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(Component.translatable("aopui.server").getString())).findFirst().orElseThrow().onPress();
                document=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(d->d.scope()==ModConfig.Type.SERVER).findFirst().orElseThrow();stage=2;
            } else if(stage==2) {
                var toggle=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).filter(b->b.getMessage().getString().equals(Component.translatable("options.on").getString())).findFirst();
                if(toggle.isEmpty()||!toggle.get().active)return;
                check(true,"Host can edit without cheats");toggle.get().onPress();
                check(document.snapshot().getAsJsonObject("values").get("world.enabled").getAsBoolean(),"Integrated server change remains staged");
                mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);stage=3;
            } else if(stage==3) {
                if(mc.screen instanceof SettingsScreen)return;
                check(!document.snapshot().getAsJsonObject("values").get("world.enabled").getAsBoolean(),"Integrated server save applied");
                var config=net.neoforged.fml.config.ModConfigs.getModConfigs("aop_ui_verification").stream().filter(c->c.getType()==ModConfig.Type.SERVER).findFirst().orElseThrow();
                check(Files.isRegularFile(config.getFullPath()),"Synchronized spec retained its disk-backed config");
                check(Files.readString(config.getFullPath()).contains("enabled = false"),"World TOML contains saved value");
                stage=4;var change=new JsonObject();change.addProperty("world.enabled",true);
                RemoteVerification.request("save",document.id(),document.snapshot().get("revision").getAsString(),change,r->{
                    try{check(r.get("ok").getAsBoolean(),"Singleplayer fixture restored");finish(true,null);}catch(Throwable error){finish(false,error.toString());}
                });
            }
        } catch(Throwable error){finish(false,error.toString());}
    }
    private static void finish(boolean passed,String error) {
        if(done)return;done=true;
        try{var report=new JsonObject();report.addProperty("passed",passed);report.addProperty("checks",checks);if(error!=null)report.addProperty("error",error);var path=Path.of(System.getProperty("aopui.singleplayerReport"));Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(Exception e){throw new RuntimeException(e);}
        var mc=Minecraft.getInstance();if(mc.level!=null)mc.level.disconnect();mc.disconnect(new TitleScreen());mc.stop();
    }
}
