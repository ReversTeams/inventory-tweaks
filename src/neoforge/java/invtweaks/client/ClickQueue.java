package invtweaks.client;

import invtweaks.sorting.SortPlanner;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;

/** Checks predicted contents before and after each ordinary vanilla click. */
public final class ClickQueue {
  private AbstractContainerMenu menu;
  private SortPlanner.Plan plan;
  private int step;
  private Runnable complete;

  public boolean busy() {
    return plan != null;
  }

  public void start(AbstractContainerMenu menu, SortPlanner.Plan plan, Runnable complete) {
    if (busy()) return;
    this.menu = menu;
    this.plan = plan;
    this.step = 0;
    this.complete = complete;
    if (plan.steps().isEmpty()) finish();
  }

  public void clear() {
    menu = null;
    plan = null;
    complete = null;
    step = 0;
  }

  private void finish() {
    Runnable action = complete;
    clear();
    if (action != null) action.run();
  }

  public void tick(int speed) {
    var mc = Minecraft.getInstance();
    if (!busy()) return;
    if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != menu) {
      clear();
      return;
    }
    for (int i = 0; i < speed && busy(); i++) {
      if (step >= plan.steps().size()) {
        finish();
        break;
      }
      var next = plan.steps().get(step);
      if (!next.before()
          .matches(
              plan.slots().stream().map(id -> menu.getSlot(id).getItem()).toList(),
              menu.getCarried())) {
        interrupt();
        break;
      }
      mc.gameMode.handleInventoryMouseClick(
          menu.containerId,
          next.click().slot(),
          next.click().button(),
          ClickType.PICKUP,
          mc.player);
      if (!next.after()
          .matches(
              plan.slots().stream().map(id -> menu.getSlot(id).getItem()).toList(),
              menu.getCarried())) {
        interrupt();
        break;
      }
      step++;
    }
  }

  private void interrupt() {
    // Never discard a cursor stack or issue an outside-slot/drop click on interruption.
    clear();
    ClientController.message(
        "Sort interrupted by an inventory change. If holding a stack, place it normally.");
  }
}
