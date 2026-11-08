# NeoForge 1.21.1 port status

Tested with Java 21 and NeoForge 21.1.265 on Minecraft 1.21.1.

## Runtime evidence

- Production Java 21 build successful.
- Dedicated server loads the jar without client-class initialization.
- **540 randomized real-menu sorts** (180 populated 27/54-slot inventories × three layouts), including full inventories, 16/64/99-item stacks, damaged/enchanted tools, potions, custom names and nested custom data.
- **78,879 assertions** in the dedicated runtime suite verify each predicted click against Minecraft's actual menu behavior, component/count conservation, empty final cursor, idempotence, rules and exclusions.
- Explicit checks cover frozen/locked positions, named ruleset reload, item/tag selectors, legacy tool/armor categories, crafting input balancing, one-item transfer, supported shulker slots and excluded machine outputs.
- Integrated client repeats the real-menu suite and passes **57 additional client/network checks**: R-key handling, storage buttons, component preservation, locked hotbar, named stack refill, offhand potion refill and retained bottles, before-break tool replacement, armor upgrade, one/matching/all transfers, server/client slot agreement, empty cursors, and settings layout.
- The settings screen was captured from the Minecraft framebuffer and visually reviewed. Labels fit the buttons, controls fit the screen, and the menu text is sharp.

The execution path uses only standard vanilla menu-click packets. The integrated-server comparison checks that server slot contents match the client's final view. A separate unmodified remote server, plugin anti-cheat configurations, every pickup timing edge case, and all modded menu/armor behaviors have not been tested.

## Intended compatibility limits

The active implementation is `src/neoforge/`; original 1.12 code is retained for reference and attribution in `src/main/` and is excluded from the new jar. Original ASM/coremod APIs and integrations cannot be installed as 1.21.1 binaries. Advanced legacy NBT selectors and tree-merge behavior require modern configuration updates. Restricted custom menu slots require an explicit modern adapter.

This is an unofficial ReversTeams port. The upstream repository remains unchanged.
