package aop.ui.client;

import aop.ui.api.SettingsRegistry;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class ClientEvents {
    @EventBusSubscriber(modid="aop_ui",value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent public static void setup(FMLClientSetupEvent e) { e.enqueueWork(() -> {
            SettingsClient.install();
            if(!SettingsRegistry.providers().isEmpty())ModList.get().getModContainerById("aop_ui").orElseThrow().registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory)(container,parent)->SettingsClient.screen(parent,SettingsRegistry.providers().getFirst().modId()));
            for(var provider:SettingsRegistry.providers()) ModList.get().getModContainerById(provider.modId()).orElseThrow()
                .registerExtensionPoint(IConfigScreenFactory.class,(IConfigScreenFactory)(container,parent) -> SettingsClient.screen(parent,provider.modId()));
        }); }
    }
    @EventBusSubscriber(modid="aop_ui",value=Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {SettingsClient.tickPending();}
        @SubscribeEvent public static void init(ScreenEvent.Init.Post e) {
            if(!(e.getScreen() instanceof PauseScreen screen) || SettingsRegistry.providers().isEmpty()) return;
            // Find a genuinely unoccupied position, including buttons contributed by other mods.
            for(int y=screen.height-25;y>=4;y-=24) {
                int x=screen.width-92;
                final int candidateY=y;
                boolean occupied=e.getListenersList().stream().filter(l -> l instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(l -> (net.minecraft.client.gui.components.AbstractWidget)l)
                    .anyMatch(w -> w.visible && w.getX()<x+88 && w.getRight()>x && w.getY()<candidateY+20 && w.getBottom()>candidateY);
                if(!occupied) { e.addListener(new ThemeButton(x,y,88,20,Component.literal("AOP: UI"),aop.ui.api.UiTheme.UI,false,b -> SettingsClient.open(screen,SettingsRegistry.providers().getFirst().modId())).modIcon()); break; }
            }
        }
    }
    private ClientEvents() { }
}
