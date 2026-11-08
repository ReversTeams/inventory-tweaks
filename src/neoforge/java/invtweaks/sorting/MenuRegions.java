package invtweaks.sorting;

import invtweaks.config.Rules;
import java.util.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** Explicit menu regions avoid crafting outputs, armor, offhand and machine result slots. */
public final class MenuRegions {
  public record Region(
      List<Integer> slots, List<Integer> logical, int columns, boolean player, boolean crafting) {
    public List<ItemStack> stacks(AbstractContainerMenu menu) {
      return slots.stream().map(i -> menu.getSlot(i).getItem().copy()).toList();
    }
  }

  public static Region inventory(
      AbstractContainerMenu menu, Player player, Rules rules, boolean useRules) {
    List<Integer> ids = new ArrayList<>(), logical = new ArrayList<>();
    Set<Integer> excluded = useRules ? rules.excluded() : Set.of();
    for (int i = 0; i < 36; i++) {
      int index = i < 27 ? i + 9 : i - 27;
      for (int j = 0; j < menu.slots.size(); j++) {
        Slot slot = menu.getSlot(j);
        if (slot.container == player.getInventory()
            && slot.getContainerSlot() == index
            && !excluded.contains(i)) {
          ids.add(j);
          logical.add(i);
          break;
        }
      }
    }
    return new Region(List.copyOf(ids), List.copyOf(logical), 9, true, false);
  }

  public static Region crafting(AbstractContainerMenu menu) {
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < menu.slots.size(); i++)
      if (menu.getSlot(i).container instanceof CraftingContainer) slots.add(i);
    int cols = slots.size() == 4 ? 2 : 3;
    return new Region(slots, new ArrayList<>(), cols, false, true);
  }

  public static Region storage(AbstractContainerMenu menu, Player player, Slot hovered) {
    Container target = null;
    if (hovered != null
        && hovered.container != player.getInventory()
        && !(hovered.container instanceof CraftingContainer)) target = hovered.container;
    if (target == null)
      for (Slot s : menu.slots)
        if (s.container != player.getInventory() && !(s.container instanceof CraftingContainer)) {
          target = s.container;
          break;
        }
    if (target == null) return null;
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < menu.slots.size(); i++)
      if (menu.getSlot(i).container == target) slots.add(i);
    boolean vanilla =
        menu instanceof ChestMenu
            || menu instanceof HopperMenu
            || menu instanceof ShulkerBoxMenu
            || menu instanceof DispenserMenu;
    if (!vanilla && (slots.size() < 9 || slots.size() != target.getContainerSize())) return null;
    for (int i : slots) if (!plainSlot(menu.getSlot(i))) return null;
    int cols = menu instanceof HopperMenu ? 5 : menu instanceof DispenserMenu ? 3 : 9;
    return new Region(List.copyOf(slots), new ArrayList<>(), cols, false, false);
  }

  public static boolean plainSlot(Slot slot) {
    return slot.getClass() == Slot.class || slot instanceof ShulkerBoxSlot;
  }

  public static boolean accepts(AbstractContainerMenu menu, Region region, Player player) {
    List<ItemStack> stacks = region.stacks(menu);
    for (int id : region.slots) {
      Slot slot = menu.getSlot(id);
      if (!slot.isActive() || !slot.mayPickup(player)) return false;
      for (ItemStack stack : stacks)
        if (!stack.isEmpty()
            && (!slot.mayPlace(stack) || slot.getMaxStackSize(stack) < stack.getMaxStackSize()))
          return false;
    }
    return true;
  }
}
