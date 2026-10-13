# Teil A — Stil

Abgeleitet aus loader (rund 3.700 Zeilen, Referenzstil), core (rund 70 Zeilen Gerüst) und
fullscreen (Portierung von *Borderless Mining*, Fremdstil; nur die Mixin-Konventionen sind
übernommen) sowie `.idea/codeStyles/Project.xml`. Wo der Bestand uneinheitlich ist, ist die
überwiegende Variante zur Regel gemacht und mit *(Entscheidung)* markiert (STR-122).

## 1. Formatierung (vom Formatter erzwungen)

- 4 Spaces, öffnende Klammer auf derselben Zeile.
- **Kein Zeilenumbruch:** rechter Rand 999. Lange Aufrufe, Bedingungen und Exception-Messages
  bleiben einzeilig.
- `KEEP_LINE_BREAKS=false`: manuelle Umbrüche im Code fügt der Formatter wieder zusammen. Muss eine
  Kette mehrzeilig bleiben, steht sie zwischen `// @formatter:off` und `// @formatter:on`
  (`FORMATTER_TAGS_ENABLED`, Beispiel: `SodiumCompat`).
- Höchstens eine Leerzeile am Stück, auch vor schließenden Klammern.
- Enum-Konstanten je eine Zeile (`ENUM_CONSTANTS_WRAP`).
- Zeilenkommentare `// text` mit Leerzeichen, eingerückt, nie in Spalte 0.
- Kotlin (Build-Skripte): offizieller Kotlin-Stil.

## 2. Imports

```java
import static net.stracciatella.module.Module.LifeCycle.*;

import java.nio.file.Path;
import java.util.ArrayList;

import com.google.gson.Gson;
import net.stracciatella.Stracciatella;
import org.slf4j.Logger;
```

- Drei Gruppen, durch Leerzeile getrennt: statisch → `java.*` → alles andere alphabetisch.
- Statische Imports immer als Wildcard (Formatter-Schwelle 1), normale nie (Schwelle 99).
- Typische statische Imports: eigene Enums (`LifeCycle.*`), ASM `Opcodes.*`/`Type.*`,
  JUnit `Assertions.*`.

## 3. Benennung

| Was | Konvention | Beispiel |
|---|---|---|
| Interface + Standardimpl. | `X` + `SimpleX` / `DefaultX` | `ModuleManager`/`SimpleModuleManager`, `TransformerRegistry`/`DefaultTransformerRegistry` |
| Invasive Reflection/Unsafe | Suffix `Hack`, Paket `init.hack` | `KnotClassLoaderHack` |
| Package-private Helfer | Suffix `Utils` | `ModuleUtils`, `ModuleLifeCycleUtils` |
| Interne Implementierung | Paket `internal.*` + `@ApiStatus.Internal` | `LazyProvider`, `SimpleServiceProvider` |
| Modul-Einstieg / Verdrahtung | `XModule` / `XSetup` | `BotModule` / `BotSetup` |
| Mixin | `XMixin` | `WindowMixin` |

- **Accessoren ohne `get`/`set`:** Getter `name()`, Setter gleichnamig mit Parameter
  `lifeCycle(LifeCycle)`. Setter sind oft enger sichtbar (package-private) als der Getter. `get…`
  nur, wo eine fremde API es vorgibt (`getResource`, `GameProvider`).
- **Singleton:** `private static final Stracciatella instance = new …;` +
  `public static Stracciatella instance()`.
- **Konstanten** *(Entscheidung)*: `LOGGER`, Strings und Primitive in `UPPER_SNAKE`; sonstige
  `static final`-Objekte (Maps, Gson, Singleton-Instanz) in camelCase — die Mehrheit im loader
  (`gson`, `map`, `mapping`, `instance`, `systemClassLoader`); `LOADER`, `UNSAFE`, `MODEL` sind
  Ausreißer.
- **Service-Schlüssel:** snake_case-String als Konstante in `Stracciatella`
  (`WORKING_DIRECTORY = "working_directory"`), nicht als Inline-Literal.
- **Lokale Variablen:** ausgeschrieben (`entrypointStorageField`, `moduleConfiguration`). Etablierte
  Kurzformen in engem Scope: `cls` (Class, nicht `clazz`), `e`/`t` (Exceptions), `in`/`out` (Streams),
  `it` (Iterator), `f` (Field), `node`/`cw`/`mv` (ASM). Reflection-Felder mit Suffix `Field`.
- Ungenutzte Parameter: `unused` (Lambda), `ignored` (catch).

## 4. Aufbau einer Klasse

1. öffentliche Konstanten
2. `LOGGER`, weitere `static`-Felder
3. `static {}`-Block (z. B. `registerAsParallelCapable()`)
4. Instanzfelder: erst `final`, Leerzeile, dann veränderliche/`volatile`
5. Konstruktoren, dann statischer Accessor (`instance()`)
6. öffentliche Methoden / `@Override`s, dann private Helfer
7. verschachtelte Typen (Interfaces, Records, `private static class`) am Ende

Leerzeile direkt nach dem Klassenkopf: kommt mit und ohne vor — egal.

## 5. Java-21-Sprachmittel

- `var` für alle Lokalen, deren Typ rechts ersichtlich ist. Explizit nur, wo nötig:
  `null`-Initialisierung (`ClassNode node = null;`), generische Casts, Lambda-Zieltyp.
- `final var` für lokale „Konstanten“ in langen Methoden.
- Switch-Ausdrücke mit `->`, `yield` in Blöcken, Pattern-Switch mit `when`, `instanceof`-Pattern
  (`dependency instanceof MavenModuleDependency maven`).
- Records für Werttypen, mit statischer Fabrik `of`/`parse` (`Pair.of`, `ModuleConfiguration.parse`).
- Enums mit konstantenspezifischen Methoden als Zustandsautomat (`AccessWidenerConfig`), erlaubte
  Übergänge im `static`-Block (`Module.LifeCycle`).
- Methodenreferenzen statt Lambdas, wo möglich (`Provider.of(SimpleModuleManager::new)`).
- try-with-resources für alles Schließbare, auch Executors (`newVirtualThreadPerTaskExecutor()`).
- Kurze Streams mit `.toList()`.

## 6. Kontrollfluss — kompakt

```java
if (entry == null) throw new IllegalStateException("Module not registered in ModuleManager: " + name);
if (!method.isAnnotationPresent(Module.Task.class)) continue;
for (var file : files) entries.add(register(file));
```

- Guard-Clauses einzeilig ohne Klammern (`return`, `throw`, `continue`, `break`); mehrere Guards
  untereinander.
- Einzeilige `for`-Schleifen ohne Klammern bei einer einfachen Anweisung.
- Klammern, sobald `else`, mehrere Anweisungen oder Verschachtelung.
- Lange Methoden in Phasen gliedern: Block + Kommentar `{ // load access widener … }`.

## 7. Nullability & API-Markierungen

- JetBrains `@NotNull`/`@Nullable` an öffentlicher API in Type-Use-Position:
  `public @Nullable Class<?> loadInAll(…)`, `Module.@NotNull LifeCycle`.
- `@ApiStatus.Internal` für Implementierungsdetails, `@Contract("_->fail")` für Methoden, die immer
  werfen.
- Sichtbarkeit so eng wie möglich: Helferklassen und Setter package-private.

## 8. Exceptions

- In unchecked umwandeln: `throw StracciatellaThrowables.propagate(t);` — mit `throw` davor, damit der
  Compiler das Ende des Pfads kennt. Mit eigenem Typ:
  `propagate(e, AssertionError.class, AssertionError::new)`.
- Statische Initialisierer: `try { … } catch (Throwable t) { throw StracciatellaThrowables.propagate(t); }`.
- `throws Throwable` gibt es in den Lade- und Lifecycle-Pfaden des loaders; über Modulgrenzen
  hinweg keine Checked Exceptions (siehe `erweiterbarkeit.md`, B2).
- `IllegalStateException` (falscher Zustand), `IllegalArgumentException` (falsches Argument),
  `UnsupportedOperationException` (nicht unterstützte Default-Methode), eigene `ModuleException` /
  `ModuleDependencyNotFoundException`.
- Messages englisch, mit Kontext (Name, ID, Pfad) per `+`.
- Wo eine Schleife weiterlaufen soll (Tasks, Downloads): loggen statt werfen.
- Bewusst ignoriert: `catch (ClassNotFoundException ignored) { // ignore this }`.

## 9. Logging

```java
private static final Logger LOGGER = LoggerFactory.getLogger("ModuleManager");
LOGGER.error("Failed to download maven dependency {}", maven, e);
```

- SLF4J, Logger-Name als Klartext-String. In Modulen den Modul-Logger über `logger()` aus `Module`.
- Platzhalter `{}`, Exception als letztes Argument, keine String-Verkettung.
- Englisch, Großbuchstabe am Anfang; Aufzählungen als `" - {}"`-Zeilen.
- Kein `System.out`/`System.err`, kein `printStackTrace()`. Temporäre Debug-Traces mit eigenem Präfix
  und vor dem Abschluss wieder entfernen.

## 10. Kommentare & Javadoc

- Code und Kommentare englisch.
- Zeilenkommentare erklären einen Schritt oder ein Warum
  (`// Modules dropped next to the game, they override bundled ones of the same id`).
- Javadoc für öffentliche API und Nicht-Offensichtliches. *(Entscheidung)* Stil wie
  `StracciatellaLanguageAdapter.extractBundledModules`: Zusammenfassungssatz, dann `<p>`-Absatz mit
  dem Warum — nicht der ältere „ein `<p>` pro Satz“-Stil aus `Module.LifeCycle`.
- Klassen im Javadoc voll qualifizieren, wenn nicht importiert (`CLASS_NAMES_IN_JAVADOC`).
- Bug-Workarounds mit Link auf den Bug (`VideoModeFixMixin`).
- Kein auskommentierter Code — Git hält die Geschichte.

## 11. Mixins

- Paket `<modul>.mixin`, `public abstract class XMixin`, Registrierung in `<modul>.mixins.json`
  (`"required": true`) **und** `mixin("<modul>.mixins.json")` in `build.gradle.kts` — eine nicht
  registrierte Config scheitert lautlos (TOOL-003).
- Annotationen je auf eigener Zeile, bei Feldern `@Shadow` vor `@Final`; `@Unique` an eigenen Feldern.
- *(Entscheidung, 7:2 im Bestand)* Injector-Attribute `method` zuerst:
  `@Inject(method = "setMode", at = @At("HEAD"))`.
- Parameter `CallbackInfo ci`; Injector-Name nach Zweck (`beforeUpdateWindowRegion`,
  `onToggleFullscreen`), darüber ein Kommentar, was er ändert und warum.
- Hinzugefügte Methoden über ein Hook-Interface mit `stracciatella$`-Präfix
  (`WindowHooks.stracciatella$apply()`), Zugriff per `(WindowHooks) (Object) window`.
- Zugriffserweiterung in `<modul>.classtweaker`/`.accesswidener` (`accessWidener v2 named`), in
  `build.gradle.kts` als `accessWidener(…)`.

## 12. Modul-Einstieg

Vorbild `MinerModule`/`MinerSetup`:

```java
public class MinerModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        MinerSetup.init();
    }
}
```

- Lifecycle-Methoden parameterlos mit `@Task`; Namen `init`/`loaded`/`preLaunch`/`started`.
  Sichtbarkeit egal (der loader macht sie per Reflection zugänglich; core nutzt `private`, miner
  und fullscreen `public`).
- Hauptklasse trivial, Verdrahtung in `XSetup` — der Verifier lädt beim Laden der Hauptklasse
  Typen aus deren Methoden, und andere Module sind dann vielleicht noch nicht da (CLAUDE.md,
  STR-060).
- Keine leeren Klassen, die nur per `new` wegen ihres Konstruktors angestoßen werden (Muster der
  `core.state.*`-Stubs).

## 13. Unit-Tests (JUnit)

- JUnit 5, `import static org.junit.jupiter.api.Assertions.*;`, Testmethoden package-private.
- Eigene Assert-Helfer als `private static void assertX(…)`.
- `assertEquals(expected, actual)` — im Bestand teils vertauscht.
- In-Game-Tests: siehe die CLAUDE.md des testing-Moduls und den Testing-Workflow in der Root-CLAUDE.md.

## Kein Vorbild — Muster im Bestand (Stand STR-122)

| Muster | Wo |
|---|---|
| Debug-Ausgaben per `System.out`/`System.err` | `core/state/CoreModuleStarted`, `core/mixin/TestMixin`, `StracciatellaClassLoader`, `FabricParentClassLoader`, `init/mixin/TestMixin` |
| Auskommentierter Code | `CoreModule`, `EventRegistry`, `StracciatellaClassLoader`, `WindowMixin`, `SodiumCompat` |
| Test-/Demo-Code im Produktivpfad | beide `TestMixin`, die `FindMe`-Generierung in `StracciatellaClassLoader` |
| Leere Klassen, nur per `new` angestoßen | die vier `core.state.*`-Klassen |
| `close()` von Hand statt try-with-resources | `SimpleModuleClassLoader.defineClass`, `SimpleModuleManager.constructModule`, `KnotClassLoaderHack.hack` |
| String-Verkettung im Log-Aufruf | `SimpleModuleClassLoader.loadClass`, `DimensionsResolver` |
| Vertauschte `assertEquals`-Argumente | `ModuleManagerTest`, `EventRegistryTest` |
| Fremdstil (`getInstance()`, `setX()`, explizite Typen, log4j, öffentliche veränderliche Felder) | fullscreen — beim Ändern nicht großflächig umstellen, in neuem Code nicht nachahmen |
