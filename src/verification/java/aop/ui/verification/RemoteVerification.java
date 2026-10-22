package aop.ui.verification;

import aop.ui.config.*;
import aop.ui.client.SettingsClient;
import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Uses real client/server payload transport and actual loaded SERVER specs. */
@EventBusSubscriber(modid="aop_ui_verification",value=Dist.CLIENT)
public final class RemoteVerification {
    private static int ticks,stage,checks;private static boolean done;private static ConfigDocument document,startup;
    private static JsonObject initial,current;
    private static Path file(String role){return Path.of(System.getProperty("aopui.remoteReport")).resolve(role+".json");}
    private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    private static String role(){return System.getProperty("aopui.verify.remote");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        if(done||role()==null)return;ticks++;
        var mc=Minecraft.getInstance();
        try {
            if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen){mc.screen.onClose();return;}
            if(ticks>3600)throw new AssertionError("Remote client timed out at stage "+stage);
            if(stage==4&&!SettingsClient.connected()) {
                var parent=new net.minecraft.client.gui.screens.TitleScreen();
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent,mc,net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("127.0.0.1:25676"),new net.minecraft.client.multiplayer.ServerData("AOP UI QA","127.0.0.1:25676",net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
                stage=5;return;
            }
            if(!SettingsClient.connected())return;
            if(document==null) {
                document=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(d->d.scope()==ModConfig.Type.SERVER).findFirst().orElseThrow();
                startup=NeoForgeConfigs.documents("aop_ui_verification").stream().filter(d->d.scope()==ModConfig.Type.STARTUP).findFirst().orElseThrow();
            }
            if(role().equals("viewer")) {
                if(stage==0) {
                    openServerScreen(mc);stage=1;read(document,reply->{
                        check(!reply.get("editable").getAsBoolean(),"Viewer read-only");
                        var toggle=toggle(mc);check(toggle!=null&&!toggle.active,"Read-only GUI control disabled");mc.screen.onClose();
                        try{Files.writeString(file("viewer-ready"),"ready");}catch(Exception ex){throw new RuntimeException(ex);}stage=2;
                    });
                } else if(stage==2&&document.snapshot().getAsJsonObject("values").get("world.count").getAsInt()==17) {
                    check(true,"Live SERVER config synchronized to second client");stage=3;
                    read(document,reply->{var snapshot=reply.getAsJsonObject("snapshot");save(document,snapshot,"world.count",new JsonPrimitive(19),r->{check(!r.get("ok").getAsBoolean()&&r.get("error").getAsString().equals("aopui.error.denied"),"Unauthorized write rejected");check(document.snapshot().getAsJsonObject("values").get("world.count").getAsInt()==17,"Denied write did not change client/server value");stage=4;mc.level.disconnect();mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen());});});
                } else if(stage==5) {
                    check(document.snapshot().getAsJsonObject("values").get("world.count").getAsInt()==17,"Saved SERVER value survives reconnect");finish(true,null);
                }
                return;
            }
            if(stage==0) {
                openServerScreen(mc);stage=20;
            } else if(stage==20) {
                var toggle=toggle(mc);if(toggle==null||!toggle.active)return;
                toggle.onPress();check(document.snapshot().getAsJsonObject("values").get("world.enabled").getAsBoolean(),"GUI server edit is staged");
                mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);stage=21;
            } else if(stage==21) {
                if(mc.screen instanceof aop.ui.client.SettingsScreen)return;
                check(!document.snapshot().getAsJsonObject("values").get("world.enabled").getAsBoolean(),"Esc saved and synchronized server edit");
                stage=22;read(document,r->save(document,r.getAsJsonObject("snapshot"),"world.enabled",new JsonPrimitive(true),saved->{check(saved.get("ok").getAsBoolean(),"GUI fixture restored");stage=30;}));
            } else if(stage==30) {
                stage=1;read(document,reply->{
                    check(reply.get("editable").getAsBoolean(),"OP4 can edit");initial=reply.getAsJsonObject("snapshot");stage=10;
                });
            } else if(stage==10&&Files.exists(file("viewer-ready"))) {
                stage=1;save(document,initial,"world.count",new JsonPrimitive(17),saved->{check(saved.get("ok").getAsBoolean(),"Save accepted");current=saved.getAsJsonObject("snapshot");check(current.getAsJsonObject("values").get("world.count").getAsInt()==17,"Authoritative server snapshot updated");stage=2;});
            } else if(stage==2&&Files.exists(file("viewer"))) {
                var viewer=JsonParser.parseString(Files.readString(file("viewer"))).getAsJsonObject();check(viewer.get("passed").getAsBoolean(),"Second client passed");stage=3;
                save(document,initial,"world.count",new JsonPrimitive(18),stale->{
                    check(!stale.get("ok").getAsBoolean()&&stale.get("error").getAsString().equals("aopui.error.stale"),"Concurrent stale write rejected");
                    save(document,current,"world.count",new JsonPrimitive(999),invalid->{
                        check(!invalid.get("ok").getAsBoolean()&&invalid.get("error").getAsString().equals("aopui.error.invalid"),"Server validates original range");
                        request("read","../../private.toml",null,null,unknown->{
                            check(!unknown.get("ok").getAsBoolean(),"Unregistered paths denied");
                            save(document,current,"world.count",new JsonPrimitive(4),restored->{
                                check(restored.get("ok").getAsBoolean(),"Server value restored");
                                read(startup,loaded->{save(startup,loaded.getAsJsonObject("snapshot"),"content",new JsonPrimitive(false),next->{
                                    check(next.get("ok").getAsBoolean(),"Remote startup saved");var s=next.getAsJsonObject("snapshot");
                                    check(!s.getAsJsonObject("values").get("content").getAsBoolean()&&s.getAsJsonObject("effective").get("content").getAsBoolean(),"Startup effective value retained until restart");
                                    finish(true,null);
                                });});
                            });
                        });
                    });
                });
            }
        }catch(Throwable error){finish(false,error.toString());}
    }
    private static void openServerScreen(Minecraft mc) {
        SettingsClient.open(new net.minecraft.client.gui.screens.PauseScreen(true),"aop_ui_verification");
        mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button).map(c->(net.minecraft.client.gui.components.Button)c)
            .filter(b->b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("aopui.server").getString())).findFirst().orElseThrow().onPress();
    }
    private static net.minecraft.client.gui.components.Button toggle(Minecraft mc) {
        if(!(mc.screen instanceof aop.ui.client.SettingsScreen))return null;
        return mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button).map(c->(net.minecraft.client.gui.components.Button)c)
            .filter(b->b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("options.on").getString())).findFirst().orElse(null);
    }
    private static void read(ConfigDocument d,java.util.function.Consumer<JsonObject> callback){request("read",d.id(),null,null,r->{try{check(r.get("ok").getAsBoolean(),"Read accepted");callback.accept(r);}catch(Throwable error){finish(false,error.toString());}});}
    private static void save(ConfigDocument d,JsonObject snapshot,String key,JsonElement value,java.util.function.Consumer<JsonObject> callback){var changes=new JsonObject();changes.add(key,value);request("save",d.id(),snapshot.get("revision").getAsString(),changes,r->{try{callback.accept(r);}catch(Throwable error){finish(false,error.toString());}});}
    private static void finish(boolean passed,String error){
        if(done)return;done=true;
        try{var report=new JsonObject();report.addProperty("passed",passed);report.addProperty("checks",checks);if(error!=null)report.addProperty("error",error);Files.createDirectories(file(role()).getParent());Files.writeString(file(role()),report.toString());}catch(Exception e){throw new RuntimeException(e);}
        Minecraft.getInstance().stop();
    }
    static void request(String operation,String config,String revision,JsonObject changes,java.util.function.Consumer<JsonObject> callback) {
        try {
            // Test-only access to the real client transport, without adding testing hooks to the API.
            var method=SettingsClient.class.getDeclaredMethod("request",String.class,String.class,String.class,JsonObject.class,java.util.function.Consumer.class);
            method.setAccessible(true);method.invoke(null,operation,config,revision,changes,callback);
        }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
}
