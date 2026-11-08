package invtweaks.client;

import com.mojang.blaze3d.platform.InputConstants;
import invtweaks.api.*;
import invtweaks.api.container.ContainerSection;
import invtweaks.config.*;
import invtweaks.sorting.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class ClientController implements InvTweaksAPI {
  public static ClientController INSTANCE;
  public final TweaksConfig config;
  public final ClickQueue queue = new ClickQueue();
  public final KeyMapping sortKey =
      new KeyMapping(
          "key.inventorytweaks.sort",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_R,
          "key.categories.inventorytweaks");
  public final KeyMapping rulesKey =
      new KeyMapping(
          "key.inventorytweaks.rules",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_UNKNOWN,
          "key.categories.inventorytweaks");
  private boolean keyEnabled = true, textbox;
  private AbstractContainerScreen<?> lastScreen;
  private ItemStack lastMain = ItemStack.EMPTY, lastOff = ItemStack.EMPTY;
  private int selected = -1, pickupDelay;
  private Map<String, Integer> lastTotals = Map.of();
  private final Deque<Transfer> transfers = new ArrayDeque<>();
  private AbstractContainerMenu transferMenu;

  private record Transfer(int slot, ItemStack stack) {}

  private long lastMiddle;
  private int middleMethod;

  public ClientController(IEventBus bus, ModContainer container) {
    INSTANCE = this;
    config = TweaksConfig.load();
    bus.addListener(this::keys);
    container.registerExtensionPoint(
        IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent, this));
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::key);
    NeoForge.EVENT_BUS.addListener(this::mouse);
    NeoForge.EVENT_BUS.addListener(this::buttons);
  }

  private void keys(RegisterKeyMappingsEvent event) {
    event.register(sortKey);
    event.register(rulesKey);
  }

  public static void message(String text) {
    var player = Minecraft.getInstance().player;
    if (player != null)
      player.displayClientMessage(Component.literal("Inventory Tweaks: " + text), true);
  }

  public boolean busy() {
    return queue.busy() || !transfers.isEmpty();
  }

  private boolean available() {
    var mc = Minecraft.getInstance();
    return mc.player != null
        && mc.gameMode != null
        && !mc.player.isSpectator()
        && !(mc.screen instanceof CreativeModeInventoryScreen)
        && !busy()
        && mc.player.containerMenu.getCarried().isEmpty();
  }

  public boolean request(MenuRegions.Region region, SortingMethod method) {
    var mc = Minecraft.getInstance();
    if (!available() || region == null || region.slots().isEmpty()) return false;
    var menu = mc.player.containerMenu;
    if (!MenuRegions.accepts(menu, region, mc.player)) {
      message("This menu has restricted slots and cannot be sorted.");
      return false;
    }
    config.reload();
    if (region.player()) region = MenuRegions.inventory(menu, mc.player, config.rules, true);
    try {
      var plan =
          SortPlanner.sort(
              region.slots(),
              region.stacks(menu),
              method,
              region.columns(),
              config,
              region.logical());
      boolean player = region.player();
      queue.start(
          menu,
          plan,
          () -> {
            if (player && config.enabled("enableAutoEquipArmor")) equipArmor();
            if (config.enabled("enableSounds"))
              mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1f);
          });
      return true;
    } catch (Exception e) {
      TweaksConfig.LOG.error("Inventory Tweaks could not plan sorting", e);
      message("Could not sort this inventory; see the log.");
      return false;
    }
  }

  public void sortHovered(SortingMethod method) {
    var mc = Minecraft.getInstance();
    if (!available()) return;
    AbstractContainerScreen<?> screen =
        mc.screen instanceof AbstractContainerScreen<?> s ? s : null;
    Slot hovered = screen == null ? null : screen.getSlotUnderMouse();
    if (hovered != null && hovered.container instanceof CraftingContainer) {
      request(MenuRegions.crafting(mc.player.containerMenu), SortingMethod.EVEN_STACKS);
      return;
    }
    if (screen != null
        && !(screen instanceof InventoryScreen)
        && (hovered == null || hovered.container != mc.player.getInventory())) {
      var region = MenuRegions.storage(mc.player.containerMenu, mc.player, hovered);
      if (region != null) {
        request(region, method);
        return;
      }
    }
    request(
        MenuRegions.inventory(mc.player.containerMenu, mc.player, config.rules, true),
        SortingMethod.INVENTORY);
  }

  private void key(ScreenEvent.KeyPressed.Post event) {
    if (!(event.getScreen() instanceof AbstractContainerScreen<?>)
        || event.getScreen().getFocused() instanceof EditBox
        || textbox) return;
    if (keyEnabled && sortKey.matches(event.getKeyCode(), event.getScanCode())) {
      sortHovered(SortingMethod.DEFAULT);
      event.setCanceled(true);
    } else if (rulesKey.matches(event.getKeyCode(), event.getScanCode())) {
      message("Ruleset: " + config.rules.next());
      event.setCanceled(true);
    }
  }

  private void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
    if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
        || screen instanceof CreativeModeInventoryScreen
        || screen.getFocused() instanceof EditBox
        || textbox) return;
    if (busy()) {
      event.setCanceled(true);
      return;
    }
    Slot hovered = screen.getSlotUnderMouse();
    if (hovered == null) return;
    if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && config.enabled("enableMiddleClick")) {
      long now = System.currentTimeMillis();
      middleMethod = now - lastMiddle < 1000 ? (middleMethod + 1) % 3 : 0;
      lastMiddle = now;
      sortHovered(
          new SortingMethod[] {
                SortingMethod.DEFAULT, SortingMethod.HORIZONTAL, SortingMethod.VERTICAL
              }
              [middleMethod]);
      event.setCanceled(true);
    } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
        && config.enabled("enableShortcuts")) {
      if (Screen.hasAltDown()) {
        transferMany(screen, hovered, Screen.hasControlDown());
        event.setCanceled(true);
      } else if (Screen.hasControlDown() && !Screen.hasShiftDown() && transferOne(screen, hovered))
        event.setCanceled(true);
    }
  }

  private void buttons(ScreenEvent.Init.Post event) {
    if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
        || screen instanceof CreativeModeInventoryScreen
        || !config.enabled("showChestButtons")) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null) return;
    boolean storage =
        !(screen instanceof InventoryScreen)
            && MenuRegions.storage(screen.getMenu(), mc.player, null) != null;
    if (!storage && !(screen instanceof InventoryScreen)) return;
    int count = storage ? 3 : 1,
        x = screen.getGuiLeft() + screen.getXSize() - count * 17 - 4,
        y = screen.getGuiTop() + 4;
    for (int i = 0; i < count; i++) {
      SortingMethod method =
          new SortingMethod[] {
                SortingMethod.DEFAULT, SortingMethod.HORIZONTAL, SortingMethod.VERTICAL
              }
              [i];
      String title = new String[] {"S", "H", "V"}[i];
      event.addListener(
          Button.builder(
                  Component.literal(title),
                  button -> {
                    if (storage)
                      request(MenuRegions.storage(screen.getMenu(), mc.player, null), method);
                    else sort(ContainerSection.INVENTORY, SortingMethod.INVENTORY);
                  })
              .bounds(x + i * 17, y, 16, 14)
              .tooltip(
                  Tooltip.create(
                      Component.literal("Sort: " + method.name().toLowerCase(Locale.ROOT))))
              .build());
    }
    event.addListener(
        Button.builder(
                Component.literal("..."), button -> mc.setScreen(new SettingsScreen(screen, this)))
            .bounds(screen.getGuiLeft() + screen.getXSize() + 2, y, 20, 14)
            .tooltip(Tooltip.create(Component.literal("Inventory Tweaks settings")))
            .build());
  }

  private void tick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.gameMode == null) {
      queue.clear();
      transfers.clear();
      lastTotals = Map.of();
      lastMain = ItemStack.EMPTY;
      lastOff = ItemStack.EMPTY;
      selected = -1;
      return;
    }
    if (mc.screen != lastScreen) {
      textbox = false;
      lastScreen = mc.screen instanceof AbstractContainerScreen<?> s ? s : null;
    }
    queue.tick(config.number("clicksPerTick", 4, 1, 16));
    if (!queue.busy() && !transfers.isEmpty()) {
      if (mc.player.containerMenu != transferMenu || !transferMenu.getCarried().isEmpty())
        transfers.clear();
      else {
        Transfer t = transfers.removeFirst();
        if (ItemStack.isSameItemSameComponents(transferMenu.getSlot(t.slot).getItem(), t.stack))
          mc.gameMode.handleInventoryMouseClick(
              transferMenu.containerId, t.slot, 0, ClickType.QUICK_MOVE, mc.player);
      }
    }
    while (sortKey.consumeClick())
      if (mc.screen == null && keyEnabled) sortHovered(SortingMethod.DEFAULT);
    while (rulesKey.consumeClick())
      if (mc.screen == null) message("Ruleset: " + config.rules.next());
    if (mc.screen == null && available() && !mc.player.isCreative()) {
      if (config.enabled("enableAutoRefill")) {
        ItemStack main = mc.player.getMainHandItem(), off = mc.player.getOffhandItem();
        if (selected == mc.player.getInventory().selected)
          refill(lastMain, main, mc.player.getInventory().selected);
        refill(lastOff, off, 40);
      }
      Map<String, Integer> totals = totals();
      if (config.enabled("enableSortingOnPickup")
          && !lastTotals.isEmpty()
          && totals.entrySet().stream()
              .anyMatch(e -> e.getValue() > lastTotals.getOrDefault(e.getKey(), 0)))
        pickupDelay = 4;
      if (pickupDelay > 0 && --pickupDelay == 0) sortHovered(SortingMethod.INVENTORY);
      lastTotals = totals;
    } else lastTotals = totals();
    lastMain = mc.player.getMainHandItem().copy();
    lastOff = mc.player.getOffhandItem().copy();
    selected = mc.player.getInventory().selected;
  }

  private Map<String, Integer> totals() {
    Map<String, Integer> totals = new HashMap<>();
    for (ItemStack s : Minecraft.getInstance().player.getInventory().items)
      if (!s.isEmpty())
        totals.merge(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem())
                + "/"
                + s.getComponentsPatch(),
            s.getCount(),
            Integer::sum);
    return totals;
  }

  public boolean refill(ItemStack previous, ItemStack current, int hotbarButton) {
    var mc = Minecraft.getInstance();
    if (!available() || mc.player.containerMenu != mc.player.inventoryMenu) return false;
    boolean breaking =
        config.enabled("autoRefillBeforeBreak")
            && !current.isEmpty()
            && current.isDamageableItem()
            && current.getMaxDamage() - current.getDamageValue()
                <= config.number("autoRefillDamageThreshhold", 5, 1, 100);
    ItemStack wanted = breaking ? current : previous;
    if (wanted.isEmpty()
        || (!current.isEmpty() && !breaking && !remainder(wanted, current))
        || !config.rules.refillAllowed(config.tree, wanted)) return false;
    for (int pass = 0; pass < (config.enabled("refillWithSimilarTools") ? 2 : 1); pass++) {
      for (int i = 9; i < 36; i++) {
        ItemStack candidate = mc.player.getInventory().getItem(i);
        if (candidate.isEmpty()
            || !(pass == 0 ? replacement(wanted, candidate) : similarTool(wanted, candidate))
            || breaking
                && candidate.getMaxDamage() - candidate.getDamageValue()
                    <= current.getMaxDamage() - current.getDamageValue()) continue;
        for (int id = 0; id < mc.player.inventoryMenu.slots.size(); id++) {
          Slot slot = mc.player.inventoryMenu.getSlot(id);
          if (slot.container == mc.player.getInventory()
              && slot.getContainerSlot() == i
              && slot.mayPickup(mc.player)) {
            mc.gameMode.handleInventoryMouseClick(
                mc.player.inventoryMenu.containerId, id, hotbarButton, ClickType.SWAP, mc.player);
            return true;
          }
        }
      }
    }
    return false;
  }

  public static boolean similarTool(ItemStack a, ItemStack b) {
    return !a.isEmpty()
        && !b.isEmpty()
        && a.getItem().getClass() == b.getItem().getClass()
        && (a.getItem() instanceof TieredItem
            || a.getItem() instanceof BowItem
            || a.getItem() instanceof CrossbowItem
            || a.getItem() instanceof ShearsItem
            || a.getItem() instanceof FishingRodItem);
  }

  public static boolean remainder(ItemStack old, ItemStack now) {
    var food = old.get(DataComponents.FOOD);
    return food != null
            && food.usingConvertsTo()
                .filter(s -> ItemStack.isSameItemSameComponents(s, now))
                .isPresent()
        || old.getItem() instanceof PotionItem && now.is(Items.GLASS_BOTTLE)
        || old.getItem() instanceof MilkBucketItem && now.is(Items.BUCKET);
  }

  public static boolean replacement(ItemStack old, ItemStack candidate) {
    if (!old.is(candidate.getItem())) return false;
    ItemStack a = old.copy(), b = candidate.copy();
    if (a.isDamageableItem()) {
      a.set(DataComponents.DAMAGE, 0);
      b.set(DataComponents.DAMAGE, 0);
    }
    return ItemStack.isSameItemSameComponents(a, b);
  }

  private void equipArmor() {
    var mc = Minecraft.getInstance();
    if (!available() || mc.player.containerMenu != mc.player.inventoryMenu) return;
    var menu = mc.player.inventoryMenu;
    for (EquipmentSlot equipment :
        new EquipmentSlot[] {
          EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
      int target = -1, source = -1;
      double best = -1;
      for (int id = 0; id < menu.slots.size(); id++) {
        Slot slot = menu.getSlot(id);
        if (slot.container == mc.player.getInventory()
            && slot.getContainerSlot() == 36 + equipment.getIndex()) {
          target = id;
          best = armorScore(slot.getItem());
          break;
        }
      }
      if (target < 0 || !menu.getSlot(target).mayPickup(mc.player)) continue;
      for (int id = 0; id < menu.slots.size(); id++) {
        Slot slot = menu.getSlot(id);
        if (slot.container != mc.player.getInventory()
            || slot.getContainerSlot() < 9
            || slot.getContainerSlot() > 35
            || !(slot.getItem().getItem() instanceof ArmorItem armor)
            || armor.getEquipmentSlot() != equipment
            || net.minecraft.world.item.enchantment.EnchantmentHelper.has(
                slot.getItem(),
                net.minecraft.world.item.enchantment.EnchantmentEffectComponents
                    .PREVENT_ARMOR_CHANGE)) continue;
        double score = armorScore(slot.getItem());
        if (score > best && menu.getSlot(target).mayPlace(slot.getItem())) {
          best = score;
          source = id;
        }
      }
      if (source >= 0) {
        List<Integer> ids = List.of(source, target);
        List<ItemStack> stacks = ids.stream().map(i -> menu.getSlot(i).getItem().copy()).toList();
        var before = SortPlanner.State.copy(stacks, ItemStack.EMPTY);
        List<SortPlanner.Step> steps = new ArrayList<>();
        // Swapping through the cursor uses vanilla armor validation and returns the old piece to
        // the source slot.
        List<ItemStack> a = List.of(ItemStack.EMPTY, stacks.get(1));
        var s1 = SortPlanner.State.copy(a, stacks.get(0));
        steps.add(new SortPlanner.Step(before, new SortPlanner.Click(source, 0), s1));
        List<ItemStack> b = List.of(ItemStack.EMPTY, stacks.get(0));
        var s2 = SortPlanner.State.copy(b, stacks.get(1));
        steps.add(new SortPlanner.Step(s1, new SortPlanner.Click(target, 0), s2));
        var end = SortPlanner.State.copy(List.of(stacks.get(1), stacks.get(0)), ItemStack.EMPTY);
        if (!stacks.get(1).isEmpty())
          steps.add(new SortPlanner.Step(s2, new SortPlanner.Click(source, 0), end));
        queue.start(menu, new SortPlanner.Plan(ids, steps, end), this::equipArmor);
        return;
      }
    }
  }

  private static double armorScore(ItemStack stack) {
    return stack.getItem() instanceof ArmorItem a
        ? a.getDefense()
            + a.getToughness() * 0.1
            + (stack.getMaxDamage() - stack.getDamageValue()) * 0.00001
        : stack.isEmpty() ? -1 : Double.POSITIVE_INFINITY;
  }

  public boolean transferOne(AbstractContainerScreen<?> screen, Slot source) {
    var mc = Minecraft.getInstance();
    if (!available()
        || !source.hasItem()
        || !source.mayPickup(mc.player)
        || !MenuRegions.plainSlot(source)) return false;
    var menu = screen.getMenu();
    MenuRegions.Region other =
        source.container == mc.player.getInventory()
            ? MenuRegions.storage(menu, mc.player, null)
            : MenuRegions.inventory(menu, mc.player, config.rules, false);
    if (other == null) return false;
    for (int id : other.slots()) {
      Slot target = menu.getSlot(id);
      ItemStack stack = target.getItem();
      if (id == source.index || !target.mayPlace(source.getItem())) continue;
      if (stack.isEmpty()
          || ItemStack.isSameItemSameComponents(stack, source.getItem())
              && stack.getCount() < target.getMaxStackSize(stack)) {
        var ids = List.of(source.index, id);
        var items = ids.stream().map(i -> menu.getSlot(i).getItem().copy()).toList();
        queue.start(menu, SortPlanner.transferOne(ids, items, 0, 1), null);
        return true;
      }
    }
    return false;
  }

  public void transferMany(AbstractContainerScreen<?> screen, Slot source, boolean all) {
    var mc = Minecraft.getInstance();
    if (!available() || !source.hasItem()) return;
    var menu = screen.getMenu();
    MenuRegions.Region region =
        source.container == mc.player.getInventory()
            ? MenuRegions.inventory(menu, mc.player, config.rules, false)
            : MenuRegions.storage(menu, mc.player, source);
    if (region == null) return;
    transferMenu = menu;
    for (int id : region.slots()) {
      Slot slot = menu.getSlot(id);
      if (slot.hasItem()
          && slot.mayPickup(mc.player)
          && (all || ItemStack.isSameItemSameComponents(slot.getItem(), source.getItem())))
        transfers.add(new Transfer(id, slot.getItem().copy()));
    }
  }

  @Override
  public void setSortKeyEnabled(boolean enabled) {
    keyEnabled = enabled;
  }

  @Override
  public void setTextboxMode(boolean enabled) {
    textbox = enabled;
  }

  @Override
  public int compareItems(ItemStack a, ItemStack b) {
    return SortPlanner.comparator(config).compare(a, b);
  }

  @Override
  public int compareItems(ItemStack a, ItemStack b, boolean onlyTree) {
    return onlyTree
        ? Integer.compare(config.tree.rank(a), config.tree.rank(b))
        : compareItems(a, b);
  }

  @Override
  public void sort(ContainerSection section, SortingMethod method) {
    var mc = Minecraft.getInstance();
    if (mc.player == null) return;
    switch (section) {
      case INVENTORY, INVENTORY_NOT_HOTBAR ->
          request(
              MenuRegions.inventory(mc.player.containerMenu, mc.player, config.rules, true),
              SortingMethod.INVENTORY);
      case CHEST -> request(MenuRegions.storage(mc.player.containerMenu, mc.player, null), method);
      case CRAFTING_IN, CRAFTING_IN_PERSISTENT ->
          request(MenuRegions.crafting(mc.player.containerMenu), SortingMethod.EVEN_STACKS);
      default -> message("This section is not a sortable storage inventory.");
    }
  }

  @Override
  public void reloadConfiguration() {
    config.reload();
    message("Configuration reloaded.");
  }
}
