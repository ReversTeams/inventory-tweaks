# Inventory Tweaks runtime validation

This development-only mod is excluded from the production release JAR.
It loads the built release JAR and tests sorting against actual Minecraft `AbstractContainerMenu.clicked` behavior, then checks the live client/server synchronization path.

With Java 21, from the repository root:

```powershell
.\gradlew.bat build
.\validation\run-validation.ps1
```

The script prepares an isolated local test world under `validation/run`, runs the dedicated menu suite, copies that world for client Quick Play, opens the client, performs inventory/network/UI checks, and closes it. It does not use CurseForge instances or player saves outside this test directory.

Manual equivalents:

```powershell
.\gradlew.bat -p validation runServer
.\gradlew.bat -p validation runClient
```

Before a manual server run, create `validation/run/eula.txt` containing `eula=true`. For Quick Play, copy `validation/run/world` into `validation/run/saves/InventoryTest`. The scripted entry point performs this setup.

Expected markers:

- `INVENTORY_SERVER_SUCCESS checks=78879 randomized=540`
- `INVENTORY_CLIENT_SUCCESS checks=57`

The suites write `inventory-server-result.txt` and `inventory-client-result.txt`. A Gradle success alone is insufficient: behavior failures are reported in the result files and log markers. The helper script verifies the result files.

The client screenshot is saved to `validation/run/screenshots/inventory-tweaks-settings.png`.
