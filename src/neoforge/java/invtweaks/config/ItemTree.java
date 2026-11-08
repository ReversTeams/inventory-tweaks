package invtweaks.config;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import javax.xml.parsers.DocumentBuilderFactory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import org.w3c.dom.*;

/** Reads the original hierarchical tree, plus modern registry IDs and item tags. */
public final class ItemTree {
  private record Entry(int order, Set<String> categories, Predicate<ItemStack> predicate) {}

  private final List<Entry> entries = new ArrayList<>();

  public static ItemTree empty() {
    return new ItemTree();
  }

  public static ItemTree load(Path file) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    var tree = new ItemTree();
    tree.read(
        factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement(),
        new LinkedHashSet<>());
    return tree;
  }

  private void read(Element node, Set<String> parents) {
    Set<String> categories = new LinkedHashSet<>(parents);
    categories.add(node.getTagName().toLowerCase(Locale.ROOT));
    String id = node.getAttribute("id"),
        tag = node.getAttribute("tag"),
        clazz = node.getAttribute("class");
    Predicate<ItemStack> predicate = null;
    if (!tag.isEmpty()) predicate = tag(tag);
    else if (!id.isEmpty()) {
      String mapped = mapLegacy(id, node.getAttribute("damage"));
      ResourceLocation key = ResourceLocation.tryParse(mapped);
      if (key != null && BuiltInRegistries.ITEM.containsKey(key))
        predicate = s -> s.is(BuiltInRegistries.ITEM.get(key));
    } else if (!clazz.isEmpty())
      predicate = node.hasAttribute("data") ? legacyKind(node.getAttribute("data")) : kind(clazz);
    else if (!node.getAttribute("oreDictName").isEmpty())
      predicate = ore(node.getAttribute("oreDictName"));
    if (predicate != null)
      entries.add(new Entry(entries.size(), Set.copyOf(categories), predicate));
    for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e) read(e, categories);
  }

  public int rank(ItemStack stack) {
    for (Entry e : entries) if (e.predicate.test(stack)) return e.order;
    return Integer.MAX_VALUE;
  }

  public boolean matches(String keyword, ItemStack stack) {
    if (stack.isEmpty()) return false;
    String word = keyword.toLowerCase(Locale.ROOT);
    if (word.equals("stuff") || word.equals("*")) return true;
    if (word.startsWith("#")) return tag(word.substring(1)).test(stack);
    if (word.contains(":")) {
      String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      if (word.endsWith(":*")) return item.startsWith(word.substring(0, word.length() - 1));
      return item.equals(word);
    }
    for (Entry e : entries) if (e.categories.contains(word) && e.predicate.test(stack)) return true;
    return semantic(word, stack);
  }

  private static Predicate<ItemStack> tag(String id) {
    var key = ResourceLocation.tryParse(id);
    return key == null ? s -> false : s -> s.is(TagKey.create(Registries.ITEM, key));
  }

  private static Predicate<ItemStack> legacyKind(String data) {
    String d = data.toLowerCase(Locale.ROOT);
    if (d.contains("isshield")) return s -> s.getItem() instanceof ShieldItem;
    for (String tool : new String[] {"pickaxe", "shovel", "axe", "hoe", "shears", "fishingrod"})
      if (d.contains("\"" + tool + "\""))
        return switch (tool) {
          case "shears" -> s -> s.getItem() instanceof ShearsItem;
          case "fishingrod" -> s -> s.getItem() instanceof FishingRodItem;
          default -> kind(tool + "Item");
        };
    for (var slot : net.minecraft.world.entity.EquipmentSlot.values())
      if (d.contains("\"" + slot.getName() + "\""))
        return s -> s.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot;
    return s -> false;
  }

  private static Predicate<ItemStack> kind(String name) {
    String simple = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return switch (simple) {
      case "itemsword", "sworditem" -> s -> s.getItem() instanceof SwordItem;
      case "itempickaxe", "pickaxeitem" -> s -> s.getItem() instanceof PickaxeItem;
      case "itemaxe", "axeitem" -> s -> s.getItem() instanceof AxeItem;
      case "itemspade", "shovelitem" -> s -> s.getItem() instanceof ShovelItem;
      case "itemhoe", "hoeitem" -> s -> s.getItem() instanceof HoeItem;
      case "itemtool", "diggeritem", "tiereditem" -> s -> s.getItem() instanceof TieredItem;
      case "itemarmor", "armoritem" -> s -> s.getItem() instanceof ArmorItem;
      case "itemfood", "fooditem" -> s -> s.has(DataComponents.FOOD);
      case "itembow", "bowitem" -> s -> s.getItem() instanceof BowItem;
      case "arrow", "arrowitem" -> s -> s.getItem() instanceof ArrowItem;
      case "itemblock", "blockitem" -> s -> s.getItem() instanceof BlockItem;
      default ->
          s -> {
            for (Class<?> c = s.getItem().getClass(); c != null; c = c.getSuperclass())
              if (c.getSimpleName().equalsIgnoreCase(simple)) return true;
            return false;
          };
    };
  }

  private static boolean semantic(String key, ItemStack s) {
    return switch (key) {
      case "ediblefood", "food" -> s.has(DataComponents.FOOD);
      case "blocks", "block" -> s.getItem() instanceof BlockItem;
      case "tools", "tool" -> s.getItem() instanceof TieredItem;
      case "armor" -> s.getItem() instanceof ArmorItem;
      default -> false;
    };
  }

  private static Predicate<ItemStack> ore(String name) {
    for (String prefix : new String[] {"ingot", "nugget", "block", "ore", "dust", "gem"})
      if (name.startsWith(prefix) && name.length() > prefix.length())
        return tag(
            "c:"
                + (prefix.equals("block") ? "storage_blocks" : prefix + "s")
                + "/"
                + name.substring(prefix.length()).toLowerCase(Locale.ROOT));
    return s -> false;
  }

  private static String mapLegacy(String id, String damage) {
    if (!id.contains(":")) id = "minecraft:" + id;
    int meta;
    try {
      meta = Integer.parseInt(damage);
    } catch (Exception e) {
      meta = 0;
    }
    String[] variants =
        switch (id) {
          case "minecraft:stone" ->
              new String[] {
                "stone",
                "granite",
                "polished_granite",
                "diorite",
                "polished_diorite",
                "andesite",
                "polished_andesite"
              };
          case "minecraft:dirt" -> new String[] {"dirt", "coarse_dirt", "podzol"};
          case "minecraft:planks" ->
              new String[] {
                "oak_planks",
                "spruce_planks",
                "birch_planks",
                "jungle_planks",
                "acacia_planks",
                "dark_oak_planks"
              };
          case "minecraft:log" -> new String[] {"oak_log", "spruce_log", "birch_log", "jungle_log"};
          case "minecraft:log2" -> new String[] {"acacia_log", "dark_oak_log"};
          case "minecraft:leaves" ->
              new String[] {"oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves"};
          case "minecraft:leaves2" -> new String[] {"acacia_leaves", "dark_oak_leaves"};
          case "minecraft:sapling" ->
              new String[] {
                "oak_sapling",
                "spruce_sapling",
                "birch_sapling",
                "jungle_sapling",
                "acacia_sapling",
                "dark_oak_sapling"
              };
          case "minecraft:fish" -> new String[] {"cod", "salmon", "tropical_fish", "pufferfish"};
          case "minecraft:cooked_fish" -> new String[] {"cooked_cod", "cooked_salmon"};
          case "minecraft:coal" -> new String[] {"coal", "charcoal"};
          case "minecraft:golden_apple" -> new String[] {"golden_apple", "enchanted_golden_apple"};
          default -> null;
        };
    if (variants != null) return "minecraft:" + variants[Math.floorMod(meta, variants.length)];
    if (id.startsWith("minecraft:")) {
      String path = id.substring(10);
      String suffix =
          switch (path) {
            case "wool",
                    "carpet",
                    "concrete",
                    "concrete_powder",
                    "stained_glass",
                    "stained_glass_pane",
                    "bed" ->
                path;
            case "stained_hardened_clay" -> "terracotta";
            default -> null;
          };
      if (suffix != null) {
        String[] colors = {
          "white",
          "orange",
          "magenta",
          "light_blue",
          "yellow",
          "lime",
          "pink",
          "gray",
          "light_gray",
          "cyan",
          "purple",
          "blue",
          "brown",
          "green",
          "red",
          "black"
        };
        return "minecraft:" + colors[Math.floorMod(meta, 16)] + "_" + suffix;
      }
      return switch (path) {
        case "wooden_door" -> "minecraft:oak_door";
        case "boat" -> "minecraft:oak_boat";
        case "reeds" -> "minecraft:sugar_cane";
        case "hardened_clay" -> "minecraft:terracotta";
        case "slime_ball" -> "minecraft:slime_ball";
        default -> id;
      };
    }
    return id;
  }
}
