package test;

import invtweaks.api.*;
import invtweaks.api.container.ContainerSection;
import invtweaks.client.*;
import invtweaks.sorting.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "invtweaks_smoke", value = Dist.CLIENT)
public final class InventoryClientTests {
  private static int phase, age, totalTicks, checks;
  private static volatile boolean ready = true;
  private static volatile Throwable error;
  private static boolean done;
  private static Map<String, Integer> before;
  private static ItemStack reserved;
  private static AbstractContainerScreen<?> chestScreen;

  private static void check(boolean condition, String text) {
    if (!condition) throw new AssertionError(text);
    checks++;
    System.out.println("INVENTORY_CLIENT_PASS " + text);
  }

  private static void next() {
    phase++;
    age = 0;
  }

  private static void onServer(Consumer<ServerPlayer> action) {
    var mc = Minecraft.getInstance();
    var id = mc.player.getUUID();
    ready = false;
    mc.getSingleplayerServer()
        .execute(
            () -> {
              try {
                ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                action.accept(player);
                player.containerMenu.broadcastChanges();
              } catch (Throwable t) {
                error = t;
              } finally {
                ready = true;
              }
            });
  }

  private static void clear(ServerPlayer p) {
    for (int i = 0; i < 41; i++) p.getInventory().setItem(i, ItemStack.EMPTY);
    p.getInventory().selected = 0;
  }

  private static ItemStack namedApples(int count) {
    ItemStack s = new ItemStack(Items.APPLE, count);
    s.set(DataComponents.CUSTOM_NAME, Component.literal("Refill test apples"));
    return s;
  }

  private static void assertServerMatches() {
    var mc = Minecraft.getInstance();
    List<ItemStack> expected =
        mc.player.containerMenu.slots.stream().map(s -> s.getItem().copy()).toList();
    onServer(
        p -> {
          check(p.containerMenu.slots.size() == expected.size(), "Server/client menu agree");
          for (int i = 0; i < expected.size(); i++)
            if (!ItemStack.matches(expected.get(i), p.containerMenu.getSlot(i).getItem()))
              throw new AssertionError("Server differs at slot " + i);
          check(p.containerMenu.getCarried().isEmpty(), "Server cursor empty");
        });
  }

  private static int stoneSlot() {
    var menu = Minecraft.getInstance().player.containerMenu;
    for (int i = 0; i < 54; i++) if (menu.getSlot(i).getItem().is(Items.STONE)) return i;
    throw new AssertionError("No stone source");
  }

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (done || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
    try {
      if (error != null) throw new AssertionError("Server-side test failed", error);
      if (++totalTicks > 2200) throw new AssertionError("Client test timed out at phase " + phase);
      age++;
      var c = ClientController.INSTANCE;
      switch (phase) {
        case 0 -> {
          mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
          Files.writeString(c.config.directory.resolve("InvTweaksRules.txt"), "D LOCKED\n");
          c.config.reload();
          c.config.properties.setProperty("autoRefillBeforeBreak", "false");
          c.config.properties.setProperty("enableAutoEquipArmor", "false");
          c.config.properties.setProperty("enableSortingOnPickup", "false");
          c.config.save();
          onServer(
              p -> {
                clear(p);
                reserved = new ItemStack(Items.CHEST, 2);
                CompoundTag tag = new CompoundTag();
                tag.putString("marker", "keep-hotbar-data");
                reserved.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                p.getInventory().setItem(0, reserved.copy());
                p.getInventory().setItem(9, new ItemStack(Items.STONE, 17));
                p.getInventory().setItem(10, new ItemStack(Items.STONE, 42));
                p.getInventory().setItem(11, new ItemStack(Items.DIAMOND, 2));
                p.getInventory().setItem(12, namedApples(8));
                p.getInventory().setItem(13, new ItemStack(Items.APPLE, 31));
              });
          next();
        }
        case 1 -> {
          if (ready && age > 20) {
            before = InventoryTests.contents(mc.player.inventoryMenu, mc.level.registryAccess());
            var screen = new InventoryScreen(mc.player);
            mc.setScreen(screen);
            var book = screen.getRecipeBookComponent();
            if (!book.isVisible()) book.toggleVisibility();
            book.keyPressed(mc.options.keyChat.getKey().getValue(), 0, 0);
            var pre = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_R, 0, 0);
            NeoForge.EVENT_BUS.post(pre);
            check(
                !pre.isCanceled() && screen.keyPressed(GLFW.GLFW_KEY_R, 0, 0),
                "Recipe search consumes R before sorting");
            check(!c.busy(), "Typing in recipe search does not sort");
            book.toggleVisibility();
            var key = new ScreenEvent.KeyPressed.Post(screen, GLFW.GLFW_KEY_R, 0, 0);
            NeoForge.EVENT_BUS.post(key);
            check(key.isCanceled(), "R key handled in inventory");
            check(c.queue.busy(), "R starts vanilla click queue");
            next();
          }
        }
        case 2 -> {
          if (age > 40 && !c.busy()) {
            check(
                InventoryTests.contents(mc.player.inventoryMenu, mc.level.registryAccess())
                    .equals(before),
                "Real client sorting preserves counts and components");
            check(
                ItemStack.matches(mc.player.getInventory().getItem(0), reserved),
                "Real client sort preserves locked hotbar");
            check(
                mc.player.containerMenu.getCarried().isEmpty(),
                "Client cursor empty after sorting");
            assertServerMatches();
            next();
          }
        }
        case 3 -> {
          if (ready && age > 5) {
            mc.player.closeContainer();
            onServer(
                p -> {
                  clear(p);
                  p.getInventory().setItem(0, namedApples(1));
                  p.getInventory().setItem(9, namedApples(12));
                });
            next();
          }
        }
        case 4 -> {
          if (ready && age > 20) {
            onServer(p -> p.getInventory().setItem(0, ItemStack.EMPTY));
            next();
          }
        }
        case 5 -> {
          if (ready && age > 40) {
            check(
                ItemStack.matches(mc.player.getMainHandItem(), namedApples(12)),
                "Consumed hotbar stack refills automatically with its custom name");
            check(
                mc.player.getInventory().getItem(9).isEmpty(),
                "Auto-refill moves rather than copies items");
            assertServerMatches();
            next();
          }
        }
        case 6 -> {
          if (ready && age > 5) {
            onServer(
                p -> {
                  clear(p);
                  p.getInventory()
                      .setItem(
                          40, PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING));
                  p.getInventory()
                      .setItem(
                          9, PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING));
                });
            next();
          }
        }
        case 7 -> {
          if (ready && age > 20) {
            onServer(p -> p.getInventory().setItem(40, new ItemStack(Items.GLASS_BOTTLE)));
            next();
          }
        }
        case 8 -> {
          if (ready && age > 40) {
            check(
                mc.player.getOffhandItem().is(Items.POTION),
                "Offhand potion refills automatically");
            check(
                mc.player
                    .getOffhandItem()
                    .get(DataComponents.POTION_CONTENTS)
                    .potion()
                    .orElseThrow()
                    .equals(Potions.STRONG_HEALING),
                "Refill preserves potion components");
            check(
                mc.player.getInventory().getItem(9).is(Items.GLASS_BOTTLE),
                "Potion refill keeps the empty bottle");
            assertServerMatches();
            next();
          }
        }
        case 9 -> {
          if (ready && age > 5) {
            c.config.properties.setProperty("autoRefillBeforeBreak", "true");
            c.config.save();
            onServer(
                p -> {
                  clear(p);
                  var worn = new ItemStack(Items.IRON_PICKAXE);
                  worn.setDamageValue(worn.getMaxDamage() - 2);
                  var spare = new ItemStack(Items.IRON_PICKAXE);
                  spare.setDamageValue(10);
                  p.getInventory().setItem(0, worn);
                  p.getInventory().setItem(9, spare);
                });
            next();
          }
        }
        case 10 -> {
          if (ready && age > 40) {
            check(
                mc.player.getMainHandItem().is(Items.IRON_PICKAXE)
                    && mc.player.getMainHandItem().getDamageValue() == 10,
                "Near-broken tool replaced before breaking");
            var worn = mc.player.getInventory().getItem(9);
            check(
                worn.is(Items.IRON_PICKAXE) && worn.getDamageValue() == worn.getMaxDamage() - 2,
                "Worn tool returned to inventory");
            assertServerMatches();
            next();
          }
        }
        case 11 -> {
          if (ready && age > 5) {
            c.config.properties.setProperty("autoRefillBeforeBreak", "false");
            c.config.properties.setProperty("enableAutoEquipArmor", "true");
            c.config.save();
            onServer(
                p -> {
                  clear(p);
                  p.getInventory().setItem(0, reserved.copy());
                  p.getInventory().setItem(9, new ItemStack(Items.IRON_CHESTPLATE));
                  p.getInventory().setItem(10, new ItemStack(Items.DIAMOND_CHESTPLATE));
                });
            next();
          }
        }
        case 12 -> {
          if (ready && age > 20) {
            mc.setScreen(new InventoryScreen(mc.player));
            c.sort(ContainerSection.INVENTORY, SortingMethod.INVENTORY);
            next();
          }
        }
        case 13 -> {
          if (age > 50 && !c.busy()) {
            check(
                mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "Sort equips the better armor");
            check(
                mc.player.getInventory().items.stream().anyMatch(s -> s.is(Items.IRON_CHESTPLATE)),
                "Other armor remains in inventory");
            assertServerMatches();
            next();
          }
        }
        case 14 -> {
          if (ready && age > 5) {
            mc.player.closeContainer();
            c.config.properties.setProperty("enableAutoEquipArmor", "false");
            c.config.save();
            onServer(
                p -> {
                  var chest = new SimpleContainer(54);
                  chest.setItem(0, new ItemStack(Items.STONE, 32));
                  chest.setItem(2, new ItemStack(Items.STONE, 11));
                  chest.setItem(1, new ItemStack(Items.DIAMOND, 3));
                  chest.setItem(3, namedApples(12));
                  chest.setItem(4, new ItemStack(Items.ENDER_PEARL, 7));
                  p.openMenu(
                      new SimpleMenuProvider(
                          (id, inv, player) -> ChestMenu.sixRows(id, inv, chest),
                          Component.literal("Inventory Tweaks test")));
                });
            next();
          }
        }
        case 15 -> {
          if (ready
              && age > 20
              && mc.screen instanceof AbstractContainerScreen<?> screen
              && screen.getMenu() instanceof ChestMenu) {
            chestScreen = screen;
            before = InventoryTests.contents(mc.player.containerMenu, mc.level.registryAccess());
            var button =
                screen.children().stream()
                    .filter(w -> w instanceof Button b && b.getMessage().getString().equals("V"))
                    .map(w -> (Button) w)
                    .findFirst()
                    .orElseThrow();
            button.onPress();
            check(c.queue.busy(), "Chest vertical-sort button works");
            next();
          }
        }
        case 16 -> {
          if (age > 40 && !c.busy()) {
            check(
                InventoryTests.contents(mc.player.containerMenu, mc.level.registryAccess())
                    .equals(before),
                "Real chest sorting preserves all menu contents");
            assertServerMatches();
            next();
          }
        }
        case 17 -> {
          if (ready && age > 5) {
            check(
                c.transferOne(chestScreen, mc.player.containerMenu.getSlot(stoneSlot())),
                "One-item shortcut starts");
            next();
          }
        }
        case 18 -> {
          if (age > 30 && !c.busy()) {
            check(
                InventoryTests.contents(mc.player.containerMenu, mc.level.registryAccess())
                    .equals(before),
                "One-item shortcut conserves items");
            check(
                mc.player.getInventory().items.stream()
                        .filter(s -> s.is(Items.STONE))
                        .mapToInt(ItemStack::getCount)
                        .sum()
                    == 1,
                "One-item shortcut moves exactly one");
            assertServerMatches();
            next();
          }
        }
        case 19 -> {
          if (ready && age > 5) {
            c.transferMany(chestScreen, mc.player.containerMenu.getSlot(stoneSlot()), false);
            next();
          }
        }
        case 20 -> {
          if (age > 40 && !c.busy()) {
            check(
                java.util.stream.IntStream.range(0, 54)
                    .noneMatch(i -> mc.player.containerMenu.getSlot(i).getItem().is(Items.STONE)),
                "Matching-stack shortcut moves all matching stacks");
            check(
                InventoryTests.contents(mc.player.containerMenu, mc.level.registryAccess())
                    .equals(before),
                "Matching shortcut conserves items");
            assertServerMatches();
            next();
          }
        }
        case 21 -> {
          if (ready && age > 5) {
            int source =
                java.util.stream.IntStream.range(0, 54)
                    .filter(i -> mc.player.containerMenu.getSlot(i).hasItem())
                    .findFirst()
                    .orElseThrow();
            c.transferMany(chestScreen, mc.player.containerMenu.getSlot(source), true);
            next();
          }
        }
        case 22 -> {
          if (age > 70 && !c.busy()) {
            check(
                java.util.stream.IntStream.range(0, 54)
                    .allMatch(i -> mc.player.containerMenu.getSlot(i).getItem().isEmpty()),
                "Transfer-everything shortcut empties the chest");
            check(
                InventoryTests.contents(mc.player.containerMenu, mc.level.registryAccess())
                    .equals(before),
                "Bulk transfer conserves all components and counts");
            assertServerMatches();
            next();
          }
        }
        case 23 -> {
          if (ready && age > 5) {
            mc.setScreen(new SettingsScreen(chestScreen, c));
            next();
          }
        }
        case 24 -> {
          if (age > 10) {
            check(mc.screen instanceof SettingsScreen, "Settings screen opens");
            for (var child : mc.screen.children())
              if (child instanceof Button b)
                check(
                    b.getY() >= 0 && b.getY() + b.getHeight() <= mc.screen.height,
                    "Settings controls fit the screen");
            net.minecraft.client.Screenshot.grab(
                mc.gameDirectory,
                "inventory-tweaks-settings.png",
                mc.getMainRenderTarget(),
                message -> {});
            next();
          }
        }
        case 25 -> {
          if (age > 30) {
            done = true;
            System.out.println("INVENTORY_CLIENT_SUCCESS checks=" + checks);
            Files.writeString(
                Path.of("inventory-client-result.txt"),
                "PASS " + checks + " real client/network checks");
            mc.stop();
          }
        }
      }
    } catch (Throwable t) {
      done = true;
      t.printStackTrace();
      System.out.println("INVENTORY_CLIENT_FAILURE phase=" + phase + " " + t);
      try {
        Files.writeString(Path.of("inventory-client-result.txt"), "FAIL phase=" + phase + " " + t);
      } catch (Exception ignored) {
      }
      mc.stop();
    }
  }
}
