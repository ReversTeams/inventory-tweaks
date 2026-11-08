package invtweaks.sorting;

import invtweaks.api.SortingMethod;
import invtweaks.config.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Pure click planner. Never writes inventory slots; every change is a vanilla pickup click. */
public final class SortPlanner {
  public record Click(int slot, int button) {}

  public record State(List<ItemStack> stacks, ItemStack carried) {
    public static State copy(List<ItemStack> stacks, ItemStack carried) {
      return new State(stacks.stream().map(ItemStack::copy).toList(), carried.copy());
    }

    public boolean matches(List<ItemStack> actual, ItemStack cursor) {
      if (actual.size() != stacks.size() || !ItemStack.matches(carried, cursor)) return false;
      for (int i = 0; i < stacks.size(); i++)
        if (!ItemStack.matches(stacks.get(i), actual.get(i))) return false;
      return true;
    }
  }

  public record Step(State before, Click click, State after) {}

  public record Plan(List<Integer> slots, List<Step> steps, State result) {}

  public static Comparator<ItemStack> comparator(TweaksConfig config) {
    return (a, b) -> {
      if (a.isEmpty()) return b.isEmpty() ? 0 : 1;
      if (b.isEmpty()) return -1;
      int value = Integer.compare(config.tree.rank(a), config.tree.rank(b));
      if (value == 0)
        value =
            BuiltInRegistries.ITEM
                .getKey(a.getItem())
                .compareTo(BuiltInRegistries.ITEM.getKey(b.getItem()));
      if (value == 0)
        value =
            Integer.compare(a.getDamageValue(), b.getDamageValue())
                * (config.enabled("invertToolDamageSorting") ? -1 : 1);
      if (value == 0)
        value = a.getComponentsPatch().toString().compareTo(b.getComponentsPatch().toString());
      if (value == 0) value = Integer.compare(b.getCount(), a.getCount());
      return value;
    };
  }

  public static Plan sort(
      List<Integer> slotIds,
      List<ItemStack> input,
      SortingMethod method,
      int columns,
      TweaksConfig config,
      List<Integer> logical) {
    var sim = new Simulation(slotIds, input);
    if (method == SortingMethod.EVEN_STACKS) {
      sim.even();
      return sim.finish();
    }
    sim.consolidate();
    List<ItemStack> pool =
        new ArrayList<>(
            sim.stacks.stream().filter(s -> !s.isEmpty()).map(ItemStack::copy).toList());
    pool.sort(comparator(config));
    List<ItemStack> desired = new ArrayList<>(Collections.nCopies(input.size(), ItemStack.EMPTY));
    Set<Integer> assigned = new HashSet<>();
    if (method == SortingMethod.INVENTORY) {
      for (Rules.Rule rule : config.rules.current().rules()) {
        if (rule.keyword().equalsIgnoreCase("LOCKED") || rule.keyword().equalsIgnoreCase("FROZEN"))
          continue;
        for (int position : Rules.positions(rule.target(), 36, 9)) {
          int at = logical.indexOf(position);
          if (at < 0 || assigned.contains(at)) continue;
          for (int i = 0; i < pool.size(); i++)
            if (config.tree.matches(rule.keyword(), pool.get(i))) {
              desired.set(at, pool.remove(i));
              assigned.add(at);
              break;
            }
        }
      }
    }
    List<Integer> order = new ArrayList<>();
    int rows = (input.size() + columns - 1) / columns;
    if (method == SortingMethod.VERTICAL) {
      for (int col = 0; col < columns; col++)
        for (int row = 0; row < rows; row++)
          if (row * columns + col < input.size()) order.add(row * columns + col);
    } else for (int i = 0; i < input.size(); i++) order.add(i);
    int cursor = 0;
    ItemStack previous = ItemStack.EMPTY;
    while (!pool.isEmpty()) {
      ItemStack stack = pool.removeFirst();
      if (method == SortingMethod.HORIZONTAL
          && !previous.isEmpty()
          && previous.getItem() != stack.getItem()
          && cursor % columns != 0) {
        int padding = columns - cursor % columns;
        if (input.size() - cursor - padding >= pool.size() + 1) cursor += padding;
      }
      while (cursor < order.size() && assigned.contains(order.get(cursor))) cursor++;
      if (cursor >= order.size()) throw new IllegalStateException("Not enough destination slots");
      desired.set(order.get(cursor++), stack);
      previous = stack;
    }
    sim.permute(desired);
    var planned = sim.finish();
    return planned.result().matches(input, ItemStack.EMPTY)
        ? new Plan(planned.slots(), List.of(), State.copy(input, ItemStack.EMPTY))
        : planned;
  }

  public static Plan transferOne(List<Integer> ids, List<ItemStack> input, int source, int target) {
    var sim = new Simulation(ids, input);
    sim.click(source, 0);
    sim.click(target, 1);
    if (!sim.cursor.isEmpty()) sim.click(source, 0);
    return sim.finish();
  }

  private static final class Simulation {
    final List<Integer> ids;
    final List<ItemStack> stacks;
    final List<Step> steps = new ArrayList<>();
    ItemStack cursor = ItemStack.EMPTY;

    Simulation(List<Integer> ids, List<ItemStack> stacks) {
      this.ids = List.copyOf(ids);
      this.stacks = new ArrayList<>(stacks.stream().map(ItemStack::copy).toList());
    }

    void click(int i, int button) {
      State before = State.copy(stacks, cursor);
      ItemStack at = stacks.get(i);
      if (cursor.isEmpty()) {
        if (!at.isEmpty()) {
          int count = button == 1 ? (at.getCount() + 1) / 2 : at.getCount();
          cursor = at.copyWithCount(count);
          at.shrink(count);
          if (at.isEmpty()) stacks.set(i, ItemStack.EMPTY);
        }
      } else if (at.isEmpty()) {
        int count = button == 1 ? 1 : cursor.getCount();
        stacks.set(i, cursor.copyWithCount(count));
        cursor.shrink(count);
      } else if (ItemStack.isSameItemSameComponents(at, cursor)) {
        int count =
            Math.min(button == 1 ? 1 : cursor.getCount(), at.getMaxStackSize() - at.getCount());
        at.grow(count);
        cursor.shrink(count);
      } else if (button == 0) {
        stacks.set(i, cursor);
        cursor = at;
      }
      if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
      steps.add(new Step(before, new Click(ids.get(i), button), State.copy(stacks, cursor)));
    }

    void consolidate() {
      for (int i = 0; i < stacks.size(); i++) {
        ItemStack dest = stacks.get(i);
        if (dest.isEmpty() || !dest.isStackable()) continue;
        for (int j = i + 1; j < stacks.size() && dest.getCount() < dest.getMaxStackSize(); j++)
          if (!stacks.get(j).isEmpty() && ItemStack.isSameItemSameComponents(dest, stacks.get(j))) {
            click(j, 0);
            click(i, 0);
            if (!cursor.isEmpty()) click(j, 0);
            dest = stacks.get(i);
          }
      }
    }

    void permute(List<ItemStack> target) {
      for (int i = 0; i < stacks.size(); i++) {
        if (target.get(i).isEmpty() || ItemStack.matches(stacks.get(i), target.get(i))) continue;
        int j = 0;
        while (j < stacks.size()
            && (j == i
                || !ItemStack.matches(stacks.get(j), target.get(i))
                || (!target.get(j).isEmpty() && ItemStack.matches(stacks.get(j), target.get(j)))))
          j++;
        if (j == stacks.size())
          throw new IllegalStateException(
              "Sort would"
                  + " change"
                  + " item"
                  + " contents"); /* A smaller identical stack cannot swap onto a full one: pick the full stack first. */
        if (ItemStack.isSameItemSameComponents(stacks.get(i), target.get(i))
            && stacks.get(i).getCount() > target.get(i).getCount()) {
          click(i, 0);
          click(j, 0);
          if (!cursor.isEmpty()) click(i, 0);
        } else {
          click(j, 0);
          click(i, 0);
          if (!cursor.isEmpty()) click(j, 0);
        }
      }
      for (int i = 0; i < stacks.size(); i++)
        if (!ItemStack.matches(stacks.get(i), target.get(i)))
          throw new IllegalStateException("Click plan could not produce the requested arrangement");
    }

    void even() {
      Set<Integer> done = new HashSet<>();
      for (int i = 0; i < stacks.size(); i++) {
        if (done.contains(i) || stacks.get(i).isEmpty()) continue;
        List<Integer> group = new ArrayList<>();
        int total = 0;
        for (int j = i; j < stacks.size(); j++)
          if (ItemStack.isSameItemSameComponents(stacks.get(i), stacks.get(j))
              && stacks.get(j).getCount() < stacks.get(j).getMaxStackSize()) {
            group.add(j);
            total += stacks.get(j).getCount();
            done.add(j);
          }
        if (group.isEmpty()) continue;
        int base = total / group.size(), extra = total % group.size();
        for (int k = 0; k < group.size(); k++) {
          int at = group.get(k), wanted = base + (k < extra ? 1 : 0);
          while (stacks.get(at).getCount() < wanted) {
            int source = -1;
            for (int m = 0; m < group.size(); m++)
              if (stacks.get(group.get(m)).getCount() > base + (m < extra ? 1 : 0)) {
                source = group.get(m);
                break;
              }
            if (source < 0) break;
            int amount =
                Math.min(
                    wanted - stacks.get(at).getCount(),
                    stacks.get(source).getCount()
                        - (base + (group.indexOf(source) < extra ? 1 : 0)));
            click(source, 0);
            for (int n = 0; n < amount; n++) click(at, 1);
            if (!cursor.isEmpty()) click(source, 0);
          }
        }
      }
    }

    Plan finish() {
      if (!cursor.isEmpty()) throw new IllegalStateException("Planner left a carried stack");
      return new Plan(ids, List.copyOf(steps), State.copy(stacks, cursor));
    }
  }
}
