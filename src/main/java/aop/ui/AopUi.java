package aop.ui;

import aop.ui.network.SettingsNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("aop_ui")
public final class AopUi {
    public AopUi(IEventBus bus) { bus.addListener(SettingsNetwork::register); }
}
