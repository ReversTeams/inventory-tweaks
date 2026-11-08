package invtweaks.config;

import com.mojang.logging.LogUtils;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Uses separate 1.21 files, so legacy configuration is never overwritten. */
public final class TweaksConfig {
  public static final Logger LOG = LogUtils.getLogger();
  public final Path directory;
  public final Properties properties = new Properties();
  public ItemTree tree;
  public Rules rules;

  public TweaksConfig(Path path) {
    directory = path;
    reload();
  }

  public static TweaksConfig load() {
    return new TweaksConfig(FMLPaths.CONFIGDIR.get().resolve("inventorytweaks-1.21"));
  }

  public synchronized void reload() {
    try {
      Files.createDirectories(directory);
      copyDefault("InvTweaksTree.xml", "itemtree.xml");
      copyDefault("InvTweaksRules.txt", "defaultconfig.dat");
      properties.clear();
      Path props = directory.resolve("InvTweaks.cfg");
      if (Files.exists(props))
        try (var in = Files.newInputStream(props)) {
          properties.load(in);
        }
      defaults(
          "enableMiddleClick",
          "true",
          "showChestButtons",
          "true",
          "enableAutoRefill",
          "true",
          "refillWithSimilarTools",
          "true",
          "autoRefillBeforeBreak",
          "false",
          "autoRefillDamageThreshhold",
          "5",
          "enableAutoEquipArmor",
          "false",
          "enableSortingOnPickup",
          "false",
          "enableShortcuts",
          "true",
          "enableSounds",
          "true",
          "invertToolDamageSorting",
          "false",
          "clicksPerTick",
          "4");
      save();
      ItemTree loaded = ItemTree.load(directory.resolve("InvTweaksTree.xml"));
      Rules loadedRules = Rules.load(directory.resolve("InvTweaksRules.txt"));
      if (rules != null) loadedRules.select(rules.current().name());
      tree = loaded;
      rules = loadedRules;
    } catch (Exception e) {
      LOG.error(
          "Inventory Tweaks configuration could not be loaded; keeping previous valid rules", e);
      if (tree == null) tree = ItemTree.empty();
      if (rules == null) rules = Rules.defaults();
    }
  }

  private void defaults(String... kv) {
    for (int i = 0; i < kv.length; i += 2) properties.putIfAbsent(kv[i], kv[i + 1]);
  }

  private void copyDefault(String target, String resource) throws IOException {
    Path file = directory.resolve(target);
    if (Files.exists(file)) return;
    try (var input =
        TweaksConfig.class.getResourceAsStream("/assets/inventorytweaks/" + resource)) {
      if (input == null) throw new IOException("Missing " + resource);
      Files.copy(input, file);
    }
  }

  public boolean enabled(String key) {
    return Boolean.parseBoolean(properties.getProperty(key, "false"));
  }

  public int number(String key, int fallback, int min, int max) {
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(properties.getProperty(key))));
    } catch (Exception e) {
      return fallback;
    }
  }

  public void toggle(String key) {
    properties.setProperty(key, Boolean.toString(!enabled(key)));
    save();
  }

  public void save() {
    try (var out = Files.newOutputStream(directory.resolve("InvTweaks.cfg"))) {
      properties.store(out, "Inventory Tweaks NeoForge 1.21.1");
    } catch (IOException e) {
      LOG.error("Could not save Inventory Tweaks configuration", e);
    }
  }
}
