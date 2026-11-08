package invtweaks.api;

import invtweaks.api.container.ContainerSection;
import net.minecraft.world.item.ItemStack;

/** Source API for the 1.21.1 client port. Legacy binary/coremod integration is not used. */
public interface InvTweaksAPI {
  void setSortKeyEnabled(boolean enabled);

  void setTextboxMode(boolean enabled);

  int compareItems(ItemStack first, ItemStack second);

  int compareItems(ItemStack first, ItemStack second, boolean onlyTreeSort);

  void sort(ContainerSection section, SortingMethod method);

  void reloadConfiguration();
}
