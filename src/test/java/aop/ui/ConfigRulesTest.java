package aop.ui;

import aop.ui.config.*;
import aop.ui.network.SettingsNetwork;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.*;
import com.google.gson.*;
import java.util.List;
import net.neoforged.fml.config.*;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Exercises actual NeoForge values, validators and save callbacks; no Minecraft world needed. */
public final class ConfigRulesTest {
    enum Mode { FIRST, SECOND }
    static int checks;
    static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    static void rejects(Runnable r) {try{r.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    static final class Loaded {
        final CommentedConfig config=TomlFormat.newConfig();int saves;final ModConfigSpec spec;
        final java.nio.file.Path path;final ModConfig modConfig;
        Loaded(ModConfigSpec spec) {
            this.spec=spec;
            try {
                path=java.nio.file.Files.createTempFile("aopui-config-test-", ".toml");path.toFile().deleteOnExit();
                var info=(net.neoforged.neoforgespi.language.IModInfo)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{net.neoforged.neoforgespi.language.IModInfo.class},(proxy,method,args)->method.getName().equals("getModId")?"aop_ui_fixture":null);
                var bus=net.neoforged.bus.api.BusBuilder.builder().build();
                bus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading e)->{saves++;spec.afterReload();});
                var container=new net.neoforged.fml.ModContainer(info) {public net.neoforged.bus.api.IEventBus getEventBus(){return bus;}};
                var constructor=ModConfig.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
                modConfig=(ModConfig)constructor.newInstance(ModConfig.Type.SERVER,spec,container,"fixture.toml",new java.util.concurrent.locks.ReentrantLock());
                spec.correct(config);load(path);
            } catch(ReflectiveOperationException|java.io.IOException e){throw new AssertionError(e);}
        }
        // Fixture only: use FML's real sealed LoadedConfig instead of a mock implementation.
        void load(java.nio.file.Path file) {
            try {
                var constructor=Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];constructor.setAccessible(true);
                spec.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(config,file,modConfig));
            } catch(ReflectiveOperationException e){throw new AssertionError(e);}
        }
        void fail(){
            try {
                var constructor=Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];constructor.setAccessible(true);
                var field=ModConfigSpec.class.getDeclaredField("loadedConfig");field.setAccessible(true);
                field.set(spec,constructor.newInstance(config,path.resolve("cannot-write-below-a-file.toml"),modConfig));
            } catch(ReflectiveOperationException e){throw new AssertionError(e);}
        }
    }
    public static void main(String[] args) throws java.io.IOException {
        var game=java.nio.file.Path.of("build/test-fixtures").toAbsolutePath();java.nio.file.Files.createDirectories(game);
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
        var b=new ModConfigSpec.Builder();
        var flag=b.define("flag",true);var number=b.defineInRange("count",3,-5,20);var decimal=b.defineInRange("zoom",1d,.5,2);
        b.defineEnum("mode",Mode.FIRST);b.define("dimension","minecraft:overworld",v->v instanceof String s&&s.contains(":"));
        b.defineListAllowEmpty("list",List.of("one"),()->"",v->v instanceof String s&&!s.contains("!"));
        var spec=b.build();var loaded=new Loaded(spec);
        loaded.config.set("foreign", "keep");loaded.config.setComment("foreign","User comment");
        var d=ConfigDocument.of("test","test",ModConfig.Type.SERVER,"test.toml",spec,()->loaded.config);
        var old=d.snapshot();
        var changes=new JsonObject();changes.addProperty("flag",false);changes.addProperty("count",7);d.apply(old.get("revision").getAsString(),changes);
        check(!flag.get()&&number.get()==7,"Apply all valid values");check(loaded.saves==1,"Save once per config");
        check("keep".equals(loaded.config.get("foreign")),"Unknown keys retained");
        check(new TomlWriter().writeToString(loaded.config).contains("User comment"),"Unknown comments retained");
        rejects(()->d.apply(old.get("revision").getAsString(),changes));
        var invalid=new JsonObject();invalid.addProperty("flag",true);invalid.addProperty("count",999);
        rejects(()->d.apply(d.snapshot().get("revision").getAsString(),invalid));check(!flag.get()&&number.get()==7,"Validation before any mutation");
        for(var o:d.options()) {
            if(o.path().equals("count")){rejects(()->o.decode(new JsonPrimitive(1.25)));rejects(()->o.decode(new JsonPrimitive("7")));rejects(()->o.decode(new JsonPrimitive(2147483648L)));}
            if(o.path().equals("mode")){rejects(()->o.decode(new JsonPrimitive("OTHER")));check(o.choices().size()==2,"Enum choices");}
            if(o.path().equals("dimension"))rejects(()->o.decode(new JsonPrimitive("invalid")));
            if(o.path().equals("list")) {rejects(()->o.decode(JsonParser.parseString("[\"!\"]")));rejects(()->o.decode(JsonParser.parseString("[4]")));check(o.decode(new JsonArray()).equals(List.of()),"Empty lists retain original validator behavior");}
        }
        loaded.fail();var failure=new JsonObject();failure.addProperty("flag",true);
        rejects(()->d.apply(d.snapshot().get("revision").getAsString(),failure));check(!flag.get(),"Rollback in-memory value on save failure");
        var startupBuilder=new ModConfigSpec.Builder();var startup=startupBuilder.gameRestart().define("content",true);var startupSpec=startupBuilder.build();var startupLoaded=new Loaded(startupSpec);
        var sd=ConfigDocument.of("startup","test",ModConfig.Type.STARTUP,"startup.toml",startupSpec,()->startupLoaded.config);sd.primeStartup();
        var next=new JsonObject();next.addProperty("content",false);sd.apply(sd.snapshot().get("revision").getAsString(),next);
        check(startup.get(),"STARTUP effective value frozen");check(!sd.snapshot().getAsJsonObject("values").get("content").getAsBoolean(),"Next-launch value saved");
        check(sd.snapshot().getAsJsonObject("effective").get("content").getAsBoolean(),"Effective value reported separately");
        startup.clearCache();check(!startup.get(),"Restart cache reads saved value");
        check(SettingsNetwork.canEdit(true,false,false),"OP4 permitted");check(!SettingsNetwork.canEdit(false,false,true),"Multiplayer owner without OP4 denied");
        check(SettingsNetwork.canEdit(false,true,true),"Singleplayer host without cheats permitted");check(!SettingsNetwork.canEdit(false,true,false),"LAN guest denied");
        aop.ui.api.SettingsRegistry.register("fixture_sources","Fixture",aop.ui.api.UiTheme.COMPACT,spec);
        check(aop.ui.api.SettingsRegistry.includesSource("fixture_sources",spec),"Explicit source included");
        check(!aop.ui.api.SettingsRegistry.includesSource("fixture_sources",startupSpec),"Other config source excluded");
        aop.ui.api.SettingsRegistry.registerCategory("fixture_sources","world","fixture.world");
        check(aop.ui.api.SettingsRegistry.categoryTranslation("fixture_sources","world").equals("fixture.world"),"Public category registration");
        var dependencies=new ModConfigSpec.Builder();
        var master=dependencies.define("tooltips",true);var portal=dependencies.define("portal",true);var table=dependencies.define("table",false);
        var details=dependencies.define("details",true);var ds=dependencies.build();new Loaded(ds);
        aop.ui.api.SettingsRegistry.register("fixture_dependencies","Dependencies",aop.ui.api.UiTheme.COMPACT,ds);
        aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,portal,master);
        aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,table,master);
        aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,details,portal);
        check(aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,portal),"Enabled child follows enabled parent");
        check(!aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,table),"Individual child can be disabled");
        master.set(false);
        check(!aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,portal),"Parent suppresses child at runtime");
        check(!aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,details),"Nested dependency suppresses descendants");
        check(portal.get()&&!table.get(),"Parent preserves stored child preferences");
        master.set(true);
        check(aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,portal)&&!aop.ui.api.SettingsRegistry.enabled("fixture_dependencies",ds,table),"Re-enabling restores individual choices");
        rejects(()->aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,master,details));
        rejects(()->aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,portal,master));
        rejects(()->aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,master,master));
        rejects(()->aop.ui.api.SettingsRegistry.registerDependency("fixture_dependencies",ds,flag,master));
        System.out.println("AOP_UI_CONFIG_RULES_PASS checks="+checks);
    }
}
