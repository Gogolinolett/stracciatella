# Stracciatella — Project Instructions

## Wissensdatenbank (PFLICHT-Workflow)

Erkenntnisse, Bugursachen und Design-Entscheidungen leben in der
Obsidian-Wissensdatenbank `knowledge-manager/Vault`, nicht in
Markdown-Dateien des Repos.

1. **Vor jeder Aufgabe** die relevanten Tickets einlesen — Suche mit 2–3
   Formulierungen zum Thema:
   `.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"`
2. **Nach der Arbeit** neue Erkenntnisse (überraschende Ursachen, Workarounds,
   Design-Entscheidungen inkl. Warum und verworfener Alternativen) über den
   `store-knowledge`-Skill ablegen: erst suchen, Treffer per `update`
   ergänzen, sonst `save`. Niemals Ticket-Dateien von Hand anlegen.
3. CLAUDE.md bleibt die reine Architektur-Landkarte — Begründungen,
   Alternativen und Lessons Learned gehören ins Vault.

Ticket-IDs in diesem Projekt: Präfix `STR`, fortlaufend ab `STR-002`
(`STR-001` = Dokumentations-Standards).

### Dokumentations-Standards für Design-Entscheidungen

Eine Entscheidung ist dokumentationspflichtig, wenn mindestens eines zutrifft:

- es wurde zwischen zwei oder mehr sinnvollen Ansätzen gewählt
- die Wahl beeinflusst Architektur oder zukünftige Erweiterbarkeit
- ein späterer Leser könnte sich fragen, warum es so gemacht wurde

Jedes Design-Entscheidungs-Ticket muss enthalten: die getroffene Entscheidung,
die erwogenen Alternativen, Pro und Contra je Alternative sowie die Begründung
der Wahl — eine Entscheidung ohne Begründung ist beim nächsten abweichenden
Fall wertlos. Naheliegende, aber verworfene Ansätze kurz mit Grund der
Verwerfung nennen.

## Coordinates
- The minecraft coordinate format: **Y is height**, Z is forward/back. **Z IS NOT THE HEIGHT, Y IS**

## Technology Stack
- **Platform**: Fabric mod loader for Minecraft
- **Mappings**: Official Mojang mappings (not Yarn)
- **Language**: Java (source code), Kotlin (build scripts)
- **Build system**: Gradle with Fabric Loom 1.14+
- **Group**: `net.stracciatella`, **Mod ID**: `stracciatella`
- **Java package root**: `net.stracciatella.<module>`

## Project Structure

```
stracciatella/
├── CLAUDE.md                      # This file — project-wide instructions
├── build.gradle.kts               # Root build script (Loom, dependencies, run configs)
├── settings.gradle.kts            # Module includes, plugin management, repositories
├── gradle.properties              # Version, group, JVM args
├── build-extensions/              # Custom Gradle plugins (stracciatella-root, etc.)
├── loader/                        # Fabric mod loader integration
│   ├── injected/                  # Code injected into Minecraft at load time
│   └── test3module/               # Loader test module
├── modules/                       # All game modules live here
│   ├── build.gradle.kts           # Declares all modules for aggregation
│   ├── core/                      # Core module — shared state, mixins
│   ├── camera/                    # Camera module — human-like yaw/pitch smoothing
│   ├── pathfinding/               # Pathfinding module — autonomous movement & parkour
│   ├── bot/                       # Bot module — task execution + behavior layer
│   ├── miner/                     # Miner module — specialized mining behaviors (diamond strip miner)
│   ├── gui/                       # Gui module — in-game UI framework, root menu, /gui
│   ├── testing/                   # Testing framework — in-game integration tests
│   ├── fullscreen/                # Fullscreen module — config and utilities
│   └── anonymous-modlist/         # Anonymous mod list module
├── run/                           # Minecraft runtime directory
│   └── logs/                      # Game logs (latest.log, etc.)
└── mods.versions.toml             # Third-party mod version catalog
```

## Modules

Each module follows the same layout:
```
modules/<name>/
├── CLAUDE.md                      # Module-specific instructions (if exists — check before editing!)
├── build.gradle.kts               # Module build script
└── src/main/java/net/stracciatella/<name>/
    ├── <Name>Module.java          # Entry point (registers commands, ticks, etc.)
    ├── mixin/                     # Minecraft injection points
    └── ...                        # Module-specific packages
```

**Cross-module class loading**: every module gets its own class loader; classes from other modules resolve through a parent fallback that only works once all modules are loaded — and the load ORDER of modules is not deterministic. The module main class is loaded with verification + `initialize=true` during the load phase, so it must not resolve other modules' types at class-load time. That includes more than static initializers: the bytecode VERIFIER loads classes for subtype checks anywhere in the main class's methods (e.g. `init()` passing an own type into a parameter typed by another module's interface). Safe pattern: keep the main class trivial — `init()` delegates to a separate setup class (see `MinerModule`/`MinerSetup`), which is only loaded when init() actually runs in the lifecycle phase, after all modules are present.

### Module Overview

| Module | Purpose | Has CLAUDE.md |
|--------|---------|---------------|
| **bot** | Human-like task execution (mine/chop/gather) + behavior layer for strategies | Yes |
| **camera** | Human-like camera movement (yaw/pitch smoothing, angle utilities) | Yes |
| **core** | Shared state management, core mixins | No |
| **gui** | In-game UI framework: page registry, root menu, key binding, `/gui` | Yes |
| **miner** | Specialized mining behaviors on the bot's behavior layer (diamond strip miner, chunk miner) | Yes |
| **pathfinding** | Autonomous player movement, mesh generation, A* pathfinding, parkour | Yes |
| **testing** | In-game integration test framework with annotations and test runner | Yes |
| **fullscreen** | Fullscreen configuration and utilities | No |
| **anonymous-modlist** | Anonymous mod list functionality | No |

### Key Module Files

**Pathfinding** (the most actively developed module):
- `PathWalker.java` — Core movement controller (static, tick-driven, simulates keyboard input)
- `ChunkMeshBuilder.java` — Converts chunks into walkable node graphs
- `MeshPathfinder.java` — A* pathfinding algorithm
- `MeshManager.java` — Per-entity, per-chunk mesh storage and cross-chunk linking
- `PathCommands.java` — All `/path` subcommands
- `PathDisplay.java` — In-game mesh/path rendering
- `PathWalkerTests.java` — In-game integration tests for pathwalking

**Bot**:
- `BotController.java` — Task execution engine (static, tick-driven state machine)
- `behavior/BehaviorRunner.java` — Registry + executor for long-running strategies (`BotBehavior`)
- `task/TaskQueue.java` — Task queue with nearest-from-player selection

**Miner**:
- `DiamondMinerBehavior.java` — Diamond strip miner (LOCATE → DESCEND → TUNNEL), plans `MineBlockTask` batches on the BotController
- `ChunkMinerBehavior.java` — Chunk miner (SELECT_SLAB → DESCEND → CLEAR), serpentine 2-layer slabs, resumes by reading the world rather than saving a cursor

**Testing**:
- `TestRunner.java` — Singleton test execution engine
- `TestContext.java` — Passed to test methods, call `complete()`/`fail()`
- `MovementController.java` — Static player movement utilities for tests

## Build & Run

```bash
# Build everything
./gradlew build

# Run the client with all mods AND every Stracciatella module
./gradlew runStracciatella

# Same, but only the light third-party mod set (what the in-game tests use)
./gradlew runStracciatellaLight

# Run automated in-game tests (default 10x speed, use 20x for fastest runs)
./gradlew runMinecraftTests

# Run only the suites of the module you are changing (substring match on suite name)
./gradlew runMinecraftTests -Psuites=bot,miner

# Build a single module
./gradlew :modules:<name>:build
```

**One client, enforced in the build.** Loom registers `runClient`, `runServer` and
`runClientRenderDoc` in every project that applies it — root, `loader`,
`loader:test3module` and all nine modules — so an unqualified `gradlew runClient` used to
start twelve at once. Only the root run configurations are real; the module copies have no
merged loader jar, no classTweaker remapped into the `named` namespace and no jol-core on
the game classpath, so each died in loader init behind its own Fabric error window
(`ClassTweakerFormatException` / `NoClassDefFoundError: org/openjdk/jol/datamodel/DataModel`).
The root `build.gradle.kts` therefore sets `enabled = isRootProject` on every `RunGameTask`,
so only the root one ever executes.

Do not re-enable them to run a single module: those tasks cannot work, and their crash
happens *before* a log is written, so `run/logs/latest.log` shows one healthy startup while
eleven error windows sit on screen — the failure is invisible in the log. To exercise one
module, run the full client and use that module's commands.

**`runClient` is not the Stracciatella client.** It is Loom's bare `client` run
configuration: the game and Fabric start, but no module is on the classpath, so
`ModuleManager` walks its lifecycle without a single `Loading module` line and every module
command (`/miner`, `/bot`, `/path`, `/gui`) comes back as *Unknown or incomplete command*.
Only `runStracciatella` (full mod list, 86 mods) and `runStracciatellaLight` (light list)
add the module classpath, via `registerStracciatellaTask` + `GenerateClasspath` in
`StracciatellaRootPlugin`. `runStracciatella` inherits from `runStracciatellaLight`, which
inherits from Loom's `client`.

**Only one client at a time.** A second instance dies at loader init with
`FileSystemException: stracciatella\system_injections\injected.jar: ... used by another
process` (from `KnotClassLoaderHack.hack`), plus `Unable to delete file run/logs/latest.log`.
Close the running client before launching another — the message names a file lock, not a
code fault.

## Testing-Workflow (PFLICHT)

**Während der Entwicklung nur die Suites der geänderten Module laufen lassen**
(`-Psuites=<teil-des-suite-namens>`, kommasepariert). Den vollständigen Lauf
ohne `-Psuites` erst am Ende einmal, um Regressionen in anderen Modulen zu
finden. Grund: ein Komplettlauf dauert Minuten und mischt Suites bei, die mit
der Änderung nichts zu tun haben — deren bekannte Flakiness sieht dann wie eine
Regression aus.

Nur zwei Signale zählen als Testergebnis:

- Der `TEST RESULTS`-Block in `run/logs/latest.log`. **Exit-Code 0 von Gradle
  bedeutet NICHT, dass die Tests bestanden haben.**
- Nie einen zweiten Testlauf starten, solange der erste noch läuft (siehe
  TOOL-002): der zweite Gradle-Aufruf endet mit Exit 1 ohne Client, und die
  Zusatzlast verfälscht den laufenden Lauf.

## Logs
- Located in `run/logs/`, mainly use the newest one (`latest.log`)
- Always check the latest logs for context on what was happening during the last execution

## Module Goals

**Pathfinding**: Automate player movements and pathfinding in an as authentic as possible way. The goal is to have the player pathfind and parkour completely automatically, using realistic physics-based controls (simulated keyboard input, not teleportation).

**Testing**: Provide a client-side integration testing framework that runs inside a live Minecraft instance, driven by tick-based execution with annotation-based test discovery.