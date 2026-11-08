package invtweaks.neoforge;

import invtweaks.client.ClientController;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(value = "inventorytweaks", dist = Dist.CLIENT)
public final class InventoryTweaksMod {
  public InventoryTweaksMod(IEventBus bus, ModContainer container) {
    new ClientController(bus, container);
  }
}
