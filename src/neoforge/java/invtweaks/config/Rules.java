package invtweaks.config;

import java.nio.file.*;
import java.util.*;

/** Original A-D / 1-9 slot notation, rectangles, reverse/vertical traversal, and named rulesets. */
public final class Rules {
  public record Rule(String target, String keyword) {}

  public record Ruleset(String name, List<Rule> rules, List<String> refill) {}

  private final List<Ruleset> sets;
  private int active;

  private Rules(List<Ruleset> sets) {
    this.sets = sets;
  }

  public static Rules defaults() {
    return new Rules(
        new ArrayList<>(
            List.of(
                new Ruleset(
                    "Default",
                    new ArrayList<>(List.of(new Rule("D", "LOCKED"))),
                    new ArrayList<>()))));
  }

  public static Rules load(Path file) throws Exception {
    List<Ruleset> sets = new ArrayList<>();
    Ruleset current = new Ruleset("Default", new ArrayList<>(), new ArrayList<>());
    sets.add(current);
    for (String line : Files.readAllLines(file)) {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#") || s.startsWith("|") || s.startsWith("=")) continue;
      if (s.matches("[\\w ]+:")) {
        if (current.rules.isEmpty() && current.refill.isEmpty()) sets.remove(current);
        current = new Ruleset(s.substring(0, s.length() - 1), new ArrayList<>(), new ArrayList<>());
        sets.add(current);
        continue;
      }
      String[] pair = s.split("\\s+", 2);
      if (pair.length != 2) continue;
      if (pair[0].equalsIgnoreCase("autorefill")) {
        current.refill.add(pair[1]);
        continue;
      }
      if (!positions(pair[0], 36, 9).isEmpty()) current.rules.add(new Rule(pair[0], pair[1]));
    }
    return sets.isEmpty() ? defaults() : new Rules(sets);
  }

  public Ruleset current() {
    return sets.get(active);
  }

  public void select(String name) {
    for (int i = 0; i < sets.size(); i++)
      if (sets.get(i).name.equals(name)) {
        active = i;
        return;
      }
  }

  public String next() {
    active = (active + 1) % sets.size();
    return current().name;
  }

  public boolean refillAllowed(ItemTree tree, net.minecraft.world.item.ItemStack stack) {
    List<String> list = current().refill;
    return list.isEmpty()
        || list.stream().anyMatch(k -> !k.equalsIgnoreCase("nothing") && tree.matches(k, stack));
  }

  public Set<Integer> excluded() {
    Set<Integer> locked = new HashSet<>(), frozen = new HashSet<>();
    for (Rule r : current().rules) {
      if (r.keyword.equalsIgnoreCase("LOCKED")) locked.addAll(positions(r.target, 36, 9));
      if (r.keyword.equalsIgnoreCase("FROZEN")) frozen.addAll(positions(r.target, 36, 9));
    }
    for (Rule r : current().rules)
      if (!r.keyword.equalsIgnoreCase("LOCKED") && !r.keyword.equalsIgnoreCase("FROZEN"))
        locked.removeAll(positions(r.target, 36, 9));
    locked.addAll(frozen);
    return locked;
  }

  public static List<Integer> positions(String constraint, int size, int columns) {
    List<Integer> result = new ArrayList<>();
    String c = constraint.toUpperCase(Locale.ROOT);
    boolean reverse = c.endsWith("R"),
        vertical = c.endsWith("V") || c.endsWith("VR") || c.endsWith("RV");
    c = c.replace("V", "").replace("R", "");
    int rows = (size + columns - 1) / columns;
    try {
      if (c.contains("-")) {
        String[] endpoints = c.split("-");
        if (endpoints.length != 2) return result;
        int[] a = point(endpoints[0], columns, rows), b = point(endpoints[1], columns, rows);
        if (a == null || b == null) return result;
        for (int y = 0; y <= Math.abs(b[0] - a[0]); y++)
          for (int x = 0; x <= Math.abs(b[1] - a[1]); x++) {
            int row = a[0] + y * Integer.signum(b[0] - a[0]),
                col = a[1] + x * Integer.signum(b[1] - a[1]);
            int index = row * columns + col;
            if (index < size) result.add(index);
          }
        if (vertical)
          result.sort(
              Comparator.comparingInt((Integer i) -> i % columns)
                  .thenComparingInt(i -> i / columns));
      } else if (c.matches("[A-Z][1-9]")) {
        int[] a = point(c, columns, rows);
        if (a != null && a[0] * columns + a[1] < size) result.add(a[0] * columns + a[1]);
      } else if (c.matches("[A-Z]")) {
        int row = c.charAt(0) - 'A';
        if (row < rows)
          for (int col = 0; col < columns && row * columns + col < size; col++)
            result.add(row * columns + col);
      } else if (c.matches("[1-9]")) {
        int col = c.charAt(0) - '1';
        if (col < columns)
          for (int row = rows - 1; row >= 0; row--)
            if (row * columns + col < size) result.add(row * columns + col);
      }
    } catch (RuntimeException ignored) {
      result.clear();
    }
    if (reverse) Collections.reverse(result);
    return result;
  }

  private static int[] point(String s, int cols, int rows) {
    if (!s.matches("[A-Z][1-9]")) return null;
    int row = s.charAt(0) - 'A', col = s.charAt(1) - '1';
    return row < rows && col < cols ? new int[] {row, col} : null;
  }
}
