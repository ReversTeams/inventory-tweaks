package invtweaks.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends Screen {
  private final Screen parent;
  private final ClientController controller;

  public SettingsScreen(Screen parent, ClientController controller) {
    super(Component.literal("Inventory Tweaks"));
    this.parent = parent;
    this.controller = controller;
  }

  @Override
  protected void init() {
    String[][] options = {
      {"enableMiddleClick", "Middle-click sort"},
      {"showChestButtons", "Show sort buttons"},
      {"enableAutoRefill", "Auto-refill"},
      {"autoRefillBeforeBreak", "Replace worn tools"},
      {"enableAutoEquipArmor", "Auto-equip armor"},
      {"enableSortingOnPickup", "Sort on pickup"},
      {"enableShortcuts", "Transfer shortcuts"},
      {"enableSounds", "Sorting sounds"}
    };
    int top = 32;
    for (int i = 0; i < options.length; i++) {
      String key = options[i][0], label = options[i][1];
      addRenderableWidget(
          Button.builder(
                  label(label, key),
                  button -> {
                    controller.config.toggle(key);
                    button.setMessage(label(label, key));
                  })
              .bounds(width / 2 - 155 + (i % 2) * 158, top + (i / 2) * 22, 152, 20)
              .build());
    }
    addRenderableWidget(
        Button.builder(
                Component.literal(
                    "Click speed: " + controller.config.number("clicksPerTick", 4, 1, 16)),
                button -> {
                  int speed = controller.config.number("clicksPerTick", 4, 1, 16) % 8 + 1;
                  controller.config.properties.setProperty(
                      "clicksPerTick", Integer.toString(speed));
                  controller.config.save();
                  button.setMessage(Component.literal("Click speed: " + speed));
                })
            .bounds(width / 2 - 155, top + 92, 152, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("Ruleset: " + controller.config.rules.current().name()),
                button -> {
                  button.setMessage(
                      Component.literal("Ruleset: " + controller.config.rules.next()));
                })
            .bounds(width / 2 + 3, top + 92, 152, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("Open configuration folder"),
                button -> Util.getPlatform().openUri(controller.config.directory.toUri()))
            .bounds(width / 2 - 155, top + 116, 200, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("Reload rules/tree"), button -> controller.reloadConfiguration())
            .bounds(width / 2 + 48, top + 116, 107, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(width / 2 - 60, height - 28, 120, 20)
            .build());
  }

  private Component label(String label, String key) {
    return Component.literal(label + ": " + (controller.config.enabled(key) ? "ON" : "OFF"));
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public void render(GuiGraphics graphics, int x, int y, float partial) {
    renderBackground(graphics, x, y, partial);
    super.render(graphics, x, y, partial);
    graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
    int yHelp = height - 64;
    graphics.drawCenteredString(
        font, "R / middle-click: sort | S/H/V: sort modes", width / 2, yHelp, 0xdddddd);
    graphics.drawCenteredString(
        font, "Ctrl-click: move one | Alt-click: matching stacks", width / 2, yHelp + 12, 0xdddddd);
    graphics.drawCenteredString(
        font,
        "Ctrl+Alt-click: move all | Change keys in Controls",
        width / 2,
        yHelp + 24,
        0xdddddd);
  }
}
