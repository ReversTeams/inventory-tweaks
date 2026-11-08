package test;

import invtweaks.api.SortingMethod;
import invtweaks.config.*;
import invtweaks.sorting.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod("invtweaks_smoke")
public final class InventoryTests {
  private static int checks;

  public InventoryTests() {
    NeoForge.EVENT_BUS.addListener(this::test);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }

  static Map<String, Integer> contents(
      AbstractContainerMenu menu, net.minecraft.core.HolderLookup.Provider registries) {
    Map<String, Integer> result = new TreeMap<>();
    for (Slot slot : menu.slots) {
      var stack = slot.getItem();
      if (!stack.isEmpty())
        result.merge(
            stack.copyWithCount(1).save(registries).toString(), stack.getCount(), Integer::sum);
    }
    if (!menu.getCarried().isEmpty()) {
      var stack = menu.getCarried();
      result.merge(
          stack.copyWithCount(1).save(registries).toString(), stack.getCount(), Integer::sum);
    }
    return result;
  }

  static void apply(
      SortPlanner.Plan plan,
      AbstractContainerMenu menu,
      net.minecraft.world.entity.player.Player player) {
    for (var step : plan.steps()) {
      check(
          step.before()
              .matches(
                  plan.slots().stream().map(i -> menu.getSlot(i).getItem()).toList(),
                  menu.getCarried()),
          "Pre-click snapshot mismatch");
      menu.clicked(step.click().slot(), step.click().button(), ClickType.PICKUP, player);
      check(
          step.after()
              .matches(
                  plan.slots().stream().map(i -> menu.getSlot(i).getItem()).toList(),
                  menu.getCarried()),
          "Vanilla click differs from planner");
    }
    check(menu.getCarried().isEmpty(), "Cursor must finish empty");
  }

  private void test(ServerStartedEvent event) {
    var server = event.getServer();
    try {
      var level = server.overworld();
      var player = FakePlayerFactory.getMinecraft(level);
      var registry = level.registryAccess();
      var config = new TweaksConfig(Path.of("test-config"));
      check(Rules.positions("A1-C4", 36, 9).size() == 12, "Rectangle rule");
      check(
          Rules.positions("D", 36, 9)
              .equals(java.util.stream.IntStream.range(27, 36).boxed().toList()),
          "Hotbar coordinates");
      check(Rules.positions("A1-C1V", 36, 9).equals(List.of(0, 9, 18)), "Vertical rectangle");
      check(Rules.positions("C9-A8R", 36, 9).size() == 6, "Reversed descending rectangle");
      check(
          config.tree.matches("sword", new ItemStack(Items.DIAMOND_SWORD)),
          "Original tree sword category");
      check(
          !config.tree.matches("shield", new ItemStack(Items.STONE)),
          "Legacy shield data does not match all items");
      check(
          config.tree.matches("pickaxe", new ItemStack(Items.DIAMOND_PICKAXE)),
          "Legacy tool class migration");
      check(
          !config.tree.matches("helmet", new ItemStack(Items.IRON_CHESTPLATE)),
          "Armor categories remain distinct");
      check(
          config.tree.matches("#minecraft:planks", new ItemStack(Items.OAK_PLANKS)),
          "Modern tag selector");
      List<ItemStack> prototypes = new ArrayList<>();
      for (Item item :
          new Item[] {
            Items.STONE,
            Items.DIRT,
            Items.OAK_PLANKS,
            Items.APPLE,
            Items.ENDER_PEARL,
            Items.DIAMOND_SWORD,
            Items.BREAD
          }) prototypes.add(new ItemStack(item));
      ItemStack large = new ItemStack(Items.APPLE);
      large.set(DataComponents.MAX_STACK_SIZE, 99);
      prototypes.add(large);
      ItemStack named = new ItemStack(Items.APPLE);
      named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved apples"));
      prototypes.add(named);
      ItemStack data = new ItemStack(Items.CHEST);
      CompoundTag custom = new CompoundTag();
      custom.putString("owner", "inventory-preservation-test");
      custom.putIntArray("payload", new int[] {1, 9, 777});
      data.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
      prototypes.add(data);
      ItemStack enchanted = new ItemStack(Items.DIAMOND_PICKAXE);
      enchanted.enchant(
          registry
              .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
              .getOrThrow(Enchantments.UNBREAKING),
          3);
      enchanted.setDamageValue(321);
      prototypes.add(enchanted);
      prototypes.add(PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING));
      Random random = new Random(438129);
      for (int sample = 0; sample < 180; sample++) {
        int size = sample % 2 == 0 ? 27 : 54;
        var storage = new SimpleContainer(size);
        var menu =
            size == 27
                ? ChestMenu.threeRows(1, player.getInventory(), storage)
                : ChestMenu.sixRows(1, player.getInventory(), storage);
        player.containerMenu = menu;
        for (int i = 0; i < size; i++)
          if (sample % 3 == 0 || random.nextInt(5) != 0) {
            ItemStack stack = prototypes.get(random.nextInt(prototypes.size())).copy();
            stack.setCount(1 + random.nextInt(stack.getMaxStackSize()));
            storage.setItem(i, stack);
          }
        var region = MenuRegions.storage(menu, player, null);
        check(region != null && MenuRegions.accepts(menu, region, player), "Chest region accepted");
        var before = contents(menu, registry);
        for (SortingMethod method :
            new SortingMethod[] {
              SortingMethod.DEFAULT, SortingMethod.HORIZONTAL, SortingMethod.VERTICAL
            }) {
          var plan =
              SortPlanner.sort(
                  region.slots(), region.stacks(menu), method, 9, config, region.logical());
          apply(plan, menu, player);
          check(
              contents(menu, registry).equals(before),
              "Item components/counts conserved in randomized chest");
          var again =
              SortPlanner.sort(
                  region.slots(), region.stacks(menu), method, 9, config, region.logical());
          if (!again.steps().isEmpty()) {
            System.out.println("NOT_IDEMPOTENT sample=" + sample + " method=" + method);
            for (int at = 0; at < region.slots().size(); at++)
              if (!ItemStack.matches(region.stacks(menu).get(at), again.result().stacks().get(at)))
                System.out.println(
                    "DIFF "
                        + at
                        + " before="
                        + region.stacks(menu).get(at).saveOptional(registry)
                        + " after="
                        + again.result().stacks().get(at).saveOptional(registry));
          }
          check(again.steps().isEmpty(), "Sorting is idempotent");
        }
      }
      Files.writeString(
          config.directory.resolve("InvTweaksRules.txt"),
          "Default:\n"
              + "D LOCKED\n"
              + "C FROZEN\n"
              + "A1 minecraft:diamond\n"
              + "B #minecraft:planks\n"
              + "Other:\n"
              + "D LOCKED\n");
      config.reload();
      config.rules.next();
      config.reload();
      check(config.rules.current().name().equals("Other"), "Ruleset survives reload");
      config.rules.next();
      player.containerMenu = player.inventoryMenu;
      for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
      player.getInventory().setItem(0, data.copyWithCount(19));
      player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 14));
      player.getInventory().setItem(16, new ItemStack(Items.DIAMOND, 3));
      player.getInventory().setItem(27, named.copyWithCount(13));
      var region = MenuRegions.inventory(player.inventoryMenu, player, config.rules, true);
      var before = contents(player.inventoryMenu, registry);
      var plan =
          SortPlanner.sort(
              region.slots(),
              region.stacks(player.inventoryMenu),
              SortingMethod.INVENTORY,
              9,
              config,
              region.logical());
      apply(plan, player.inventoryMenu, player);
      check(player.getInventory().getItem(9).is(Items.DIAMOND), "A1 item placement rule");
      check(
          player.getInventory().getItem(0).getCount() == 19
              && player.getInventory().getItem(0).has(DataComponents.CUSTOM_DATA),
          "Locked hotbar untouched");
      check(
          ItemStack.matches(player.getInventory().getItem(27), named.copyWithCount(13)),
          "Frozen row untouched");
      check(
          contents(player.inventoryMenu, registry).equals(before),
          "Inventory rules conserve all contents");
      var crafting = MenuRegions.crafting(player.inventoryMenu);
      check(crafting.slots().size() == 4, "Only crafting inputs selected");
      player.inventoryMenu.getSlot(crafting.slots().get(0)).set(new ItemStack(Items.STICK, 64));
      player.inventoryMenu.getSlot(crafting.slots().get(1)).set(new ItemStack(Items.STICK, 8));
      player.inventoryMenu.getSlot(crafting.slots().get(2)).set(new ItemStack(Items.STICK, 2));
      plan =
          SortPlanner.sort(
              crafting.slots(),
              crafting.stacks(player.inventoryMenu),
              SortingMethod.EVEN_STACKS,
              2,
              config,
              crafting.logical());
      apply(plan, player.inventoryMenu, player);
      check(
          player.inventoryMenu.getSlot(crafting.slots().get(0)).getItem().getCount() == 64,
          "Full crafting stack unchanged");
      check(
          player.inventoryMenu.getSlot(crafting.slots().get(1)).getItem().getCount() == 5
              && player.inventoryMenu.getSlot(crafting.slots().get(2)).getItem().getCount() == 5,
          "Crafting stacks evenly distributed");
      var transfer = new SimpleContainer(27);
      var chest = ChestMenu.threeRows(2, player.getInventory(), transfer);
      player.containerMenu = chest;
      transfer.setItem(0, named.copyWithCount(7));
      plan =
          SortPlanner.transferOne(
              List.of(0, 1), List.of(transfer.getItem(0), transfer.getItem(1)), 0, 1);
      apply(plan, chest, player);
      check(
          transfer.getItem(0).getCount() == 6
              && transfer.getItem(1).getCount() == 1
              && transfer.getItem(1).has(DataComponents.CUSTOM_NAME),
          "One-item transfer preserves components");
      var shulker = new ShulkerBoxMenu(3, player.getInventory(), new SimpleContainer(27));
      check(MenuRegions.storage(shulker, player, null) != null, "Vanilla shulker supported");
      var furnace = new FurnaceMenu(4, player.getInventory());
      check(MenuRegions.storage(furnace, player, null) == null, "Machine outputs excluded");
      System.out.println("INVENTORY_SERVER_SUCCESS checks=" + checks + " randomized=540");
      Files.writeString(
          Path.of("inventory-server-result.txt"),
          "PASS " + checks + " checks, 540 randomized sorts");
    } catch (Throwable t) {
      t.printStackTrace();
      System.out.println("INVENTORY_SERVER_FAILURE " + t);
      try {
        Files.writeString(Path.of("inventory-server-result.txt"), "FAIL " + t);
      } catch (Exception ignored) {
      }
    } finally {
      if (server.isDedicatedServer()) server.halt(false);
    }
  }
}
