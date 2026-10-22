package aop.ui.verification;

import aop.ui.api.*;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Development-only fixture. This source set is never included in the gameplay artifact. */
@Mod("aop_ui_verification")
public final class VerificationMod {
    public VerificationMod(ModContainer container) {
        if(!Boolean.parseBoolean(System.getProperty("aopui.fixture","true")))return;
        var b=new ModConfigSpec.Builder();b.push("interface");b.define("showTags",false);b.define("wandDialBottom",false);b.defineInRange("bookZoom",1d,.5,2);b.pop();
        var tooltips=b.define("interface.tooltips",true);
        var portal=b.define("interface.portalTooltips",true);
        var table=b.define("interface.tableTooltips",false);
        var client=b.build();container.registerConfig(ModConfig.Type.CLIENT,client);
        b=new ModConfigSpec.Builder();b.push("world");b.define("enabled",true);b.defineInRange("count",4,0,100);b.pop();
        container.registerConfig(ModConfig.Type.SERVER,b.build());
        b=new ModConfigSpec.Builder();b.gameRestart().define("content",true);
        container.registerConfig(ModConfig.Type.STARTUP,b.build());
        SettingsRegistry.register("aop_ui_verification","UI Verification",UiTheme.THAUMCRAFT);
        SettingsRegistry.registerDependency("aop_ui_verification",client,portal,tooltips);
        SettingsRegistry.registerDependency("aop_ui_verification",client,table,tooltips);
    }
}
