# Teil B — Erweiterbarkeit

Jede Regel ist aus einem belegten Problem beim Einbinden des pathfinding-Moduls abgeleitet
(Entscheidung und Alternativen: STR-122). Die Code-Beispiele sind **Schemata** mit frei gewählten
Namen, keine vorhandenen Klassen.

## Woran pathfinding beim Einbinden gehakt hat (Stand STR-122)

1. **Keine passende Fassade.** `BotController.navigateToStandoff` greift in drei Klassen durch:
   `MeshManager.ensureArea`, `MeshManager.findOrBuildNearestNode` und direkt das Feld
   `MeshManager.meshes`, `MeshPathfinder.findPathToFirst`, dazu `PathWalker.startStraight`,
   `isActive`, `stop` und die interne Bremshilfe `PathWalker.applyCounterBrake`.
   `Navigator.navigate` gab es; der bot hat es nie aufgerufen (STR-091).
2. **Kein Ergebnis.** `PathWalker.isActive()` sagt nur „läuft noch“; Erfolg und Fehlschlag sind nicht
   unterscheidbar, jeder Aufrufer prüft die Ankunft selbst mit eigener Schwelle
   (`WalkTravelMethod` 2 Blöcke, `BotController` `isWithinReach`). `Journey.status()` steht vor dem
   Start auf `ARRIVED`; beide Reisephasen des Restocks waren deshalb sofort „fertig“, und jeder
   Aufrufer hält jetzt ein eigenes „gestartet“-Flag (pathfinding/CLAUDE.md).
3. **Versteckte Vorbedingung.** Die Standoff-Suche las nur Meshes, die schon existierten. Die Tests
   bauten sie vorher (`BotTests.generateMesh`), die Produktion nicht — der Bot lief im echten Spiel
   nie einen Pfad (STR-106).
4. **Globaler Zustand statt Laufobjekt.** `PathWalker` ist komplett statisch; `start` setzt über 30
   Felder von Hand zurück. Modi kamen als Methodenvarianten (`start`, `startStraight`,
   `startTravel`) plus Bool-Flags (`cruising`, `travelling`), laufbezogene Optionen als globale Setter
   (`setTravelGlanceChance`, das erst `Journey.stop` zurücksetzt; `PathPlacement.setAllowed` vor
   jeder Reise). 31 öffentliche statische Methoden, 19 davon Setter.
5. **Ressource ohne Besitzer.** `MeshManager.meshes` wurde nirgends geleert und wuchs pro Entity und
   Chunk über die ganze Sitzung (STR-091).
6. **Doppelte Fachlogik.** Mesh, Walker und Journey prüften „begehbar“ jeweils selbst mit `isAir` und
   widersprachen sich (STR-107); `findNearestNode` stand doppelt im `BotController` (STR-106).
7. **Änderung für einen trifft alle.** Das Y-Band durfte nur als Parameter kommen (ein neuer Default
   hätte dem Diamant-Miner Knoten genommen); String-Pulling im A* wurde verworfen, weil es das
   Ergebnis für jeden Aufrufer ändert (STR-091, STR-111).

## Vorbilder im Projekt

| Muster | Wo | Ticket |
|---|---|---|
| Interface + Registry, Auswahl nach `canUse`/`cost` | `TravelMethod` + `Navigator.register` | — |
| Abhängigkeit umkehren: Interface unten, Implementierung oben | `BlockPlacer` + `PathPlacement.register`, implementiert von `RoutePlacer` im bot | STR-089 |
| Beiträge zu einem gemeinsamen Ort | `GuiPage` + `GuiRegistry.register`, `BotBehavior` + `BehaviorRunner.register` | STR-060 |
| Pflichtentscheidung als abstrakte Methode, Daten als Record | `BotBehavior.policy()` → `BotPolicy`, `restockNeeds()` → `RestockNeeds` | STR-056, STR-087 |
| Harmloser Default | `BotBehavior.expectsTeleport()` → `false` | STR-101 |
| Eine Wahrheit pro Prädikat | `Terrain.isPassable`/`isStandable`/`isStraightWalk` | STR-107 |
| Verdrahtung außerhalb der Hauptklasse | `MinerSetup`, `BotSetup`, `GuiSetup` | STR-060 |
| API/Implementierung getrennt | loader: `util.Provider` / `internal.util.LazyProvider` mit `@ApiStatus.Internal` | — |

---

## B1. Eine Fassade pro Fähigkeit, geschnitten nach dem Bedarf der Aufrufer

- Was andere Module importieren dürfen, bleibt in den Fachpaketen (heute z. B. `travel`, `place`).
  Implementierungsdetails kommen nach `<modul>.internal.*` und tragen `@ApiStatus.Internal` — wie im
  loader. Ein Import aus `<anderes Modul>.internal.` ist ein Regelverstoß und per Suche prüfbar.
- Die Fassade beantwortet die Frage des Aufrufers in **einem** Aufruf („geh zu diesem Ziel“), nicht
  die Schritte dahin (Mesh bauen, Startknoten suchen, A*, Walker starten).
- Braucht ein Aufrufer etwas aus dem Inneren (wie `applyCounterBrake`), wird es bewusst in die API
  gehoben — nicht einfach `public` gemacht, und nicht kopiert.
- Vor dem Entwurf die Aufrufer benennen und ihren Bedarf aufschreiben (API-Steckbrief in SKILL.md).

*Warum:* Jede Klasse, in die ein anderes Modul greift, ist de facto API und kann nicht mehr frei
geändert werden. `Navigator` existierte und wurde umgangen: Er meldet Fehler nur per Chat und
Log (B2) und nimmt ein festes Ziel, während der bot den besten *erreichbaren* von mehreren
Standplätzen braucht (`findPathToFirst` über eine Kandidatenliste).

## B2. Langlaufende Vorgänge geben ein Handle mit Zustand und Grund zurück

```java
public interface WalkRun {
    WalkStatus status();

    @Nullable String failReason();

    void cancel();
}

public enum WalkStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
```

- Vor dem Start gibt es nie einen Endzustand (`PENDING`, nicht `ARRIVED`).
- Ob das Ziel erreicht ist, entscheidet der Anbieter — nicht jeder Aufrufer mit eigener Schwelle.
- Fehler gehen als Wert zurück (`FAILED` + Grund). Chat-Ausgaben macht nur die Command-Schicht;
  `Navigator` meldete Fehler nur per Chat und `LOGGER.warn`, also nichts Programmatisches.
- Keine Checked Exceptions über Modulgrenzen; `throws Throwable` bleibt in den Lade-Pfaden des
  loaders.
- Bestehende Formen (`TravelStatus`, `BehaviorStatus`) haben dieselbe Idee; neue Handles bekommen
  zusätzlich `PENDING` und einen Grund. Ein gemeinsamer Typ für alle Module ist aufgeschoben, bis
  ein drittes Modul dieselbe Form braucht (STR-122).

## B3. Ein Lauf ist ein Objekt, seine Optionen sind ein Record

```java
public record WalkOptions(boolean cruise, boolean legRules, double glanceChance) {
    public static final WalkOptions PRECISE = new WalkOptions(false, false, 0);

    public WalkOptions withGlanceChance(double glanceChance) {
        return new WalkOptions(cruise, legRules, glanceChance);
    }
}

public final class Walking {
    private static @Nullable SimpleWalkRun current;

    public static WalkRun start(List<MeshNode> path, WalkOptions options) {
        if (current != null) current.cancel();
        current = new SimpleWalkRun(path, options);
        return current;
    }
}
```

- Der Zustand eines Laufs lebt im Laufobjekt, nicht in statischen Feldern. Jeder Start erzeugt ein
  neues Objekt — es gibt keine Reset-Liste, die man beim nächsten neuen Feld vergisst.
- Ein statischer Einstieg ist erlaubt (es gibt einen Spieler und einen Tick-Hook); er hält höchstens
  den aktuellen Lauf.
- Modi und Optionen kommen als Record-Parameter — nicht als Methodenvariante (`startStraight`,
  `startTravel`) und nie als globaler Setter, den danach jemand zurücksetzen muss.
- Nutzer-Konfiguration (JSON, Konfig-Commands) ist etwas anderes: sie bleibt global, wird aber nicht
  von anderen Modulen gesetzt, um einen einzelnen Lauf zu steuern.

*Warum:* STR-056 hat dieselbe Lehre für den bot gezogen: Flags als Setter am Controller haben keinen
Besitzer, niemand setzt sie zuverlässig zurück, und der Zustand überlebt das Ende eines Laufs.

## B4. Vorbedingungen stellt die API selbst her — oder scheitert laut

- Die Fassade baut, was sie braucht, selbst (bei Kosten budgetiert, z. B. Meshes pro Tick). Regeln
  der Form „vorher muss X aufgerufen worden sein“ gibt es nicht.
- Lässt sich eine Vorbedingung nicht herstellen: `FAILED` mit Grund, kein stiller Fallback.
- Ein vorübergehender Zustand ist kein Fehler: auf das Ereignis warten (Chunk geladen), nicht auf eine
  feste Zeit, die ein laggender Server aushebelt (STR-091, Nachtrag 2026-10-08).
- Tests gehen durch denselben Einstieg wie die Produktion. Eine Fixture, die vorbereitet, was die
  Produktion nie vorbereitet, testet anderen Code als den ausgelieferten (STR-106).

## B5. Neues Verhalten kommt als Option mit dem alten Default

- Eine Erweiterung ändert nie das Ergebnis bestehender Aufrufer. Sie kommt als neue Option
  (Record-Feld, neue Implementierung), deren Default das bisherige Verhalten ist.
- Soll sich ein Default doch ändern, ist das eine Design-Entscheidung mit Vault-Ticket; vorher werden
  alle Aufrufer aufgelistet und geprüft.

*Beleg:* Das Y-Band der Mesh-Erzeugung musste Parameter bleiben — global gekappt hätte es dem
Diamant-Miner tief unter Tage Knoten genommen. String-Pulling im A* wurde verworfen, weil es das
Ergebnis für alle Aufrufer ändert (STR-091, STR-111).

## B6. Erweiterungspunkte: Interface unten, Registrierung von oben

```java
// lower module: declares what it needs
public interface SupportBuilder {
    boolean build(BlockPos pos, BlockPos support);
}

public final class SupportBuilders {
    private static final List<SupportBuilder> builders = new CopyOnWriteArrayList<>();

    public static void register(SupportBuilder builder) {
        builders.add(builder);
    }

    public static void unregister(SupportBuilder builder) {
        builders.remove(builder);
    }
}

// upper module, in XSetup — never in XModule
SupportBuilders.register(supportBuilder);
```

- Die Abhängigkeit zeigt nur nach unten (`compileOnly` in `build.gradle.kts`). Braucht die untere
  Schicht eine Fähigkeit der oberen, deklariert sie ein Interface und lässt sich die Implementierung
  registrieren (STR-089). Pathfinding entscheidet *wo* ein Block fehlt, der bot weiß *wie* man ihn
  menschlich setzt.
- Ohne Registrierung ist die Fähigkeit nicht da — kein Flag, kein Sonderfall, kein halber Zustand.
- Registriert wird in der Setup-Klasse, nie in der Modul-Hauptklasse: Der Bytecode-Verifier der
  Hauptklasse prüft Subtypen gegen Interfaces anderer Module, und deren Klassenlader sind beim Laden
  vielleicht noch nicht da (CLAUDE.md, STR-060).
- Jede Registrierung hat ein `unregister`, aufgerufen in `STOPPING`. Der loader kann Module neu laden
  (`ModuleManager.reload`); eine Registry, die Instanzen eines entladenen Moduls festhält, hält dessen
  Klassenlader am Leben — genau das meldet `MemoryLeakDetector` als „Probable Memory leak“.

## B7. Pflichtentscheidungen abstrakt, nur harmlose Defaults

- Eine Interface-Methode bekommt nur dann eine `default`-Implementierung, wenn ein vergessenes
  Überschreiben harmlos ist (`expectsTeleport()` → `false`, STR-101).
- Wo Weglassen gefährlich wäre, ist die Methode abstrakt (`policy()`, STR-056): Wer eine neue
  Implementierung schreibt, muss sich entscheiden; ein Default würde Sicherheitslosigkeit per
  Unterlassung vererben.
- Interfaces klein halten; Zusatzdaten als Record (`BotPolicy`, `RestockNeeds`) statt weiterer
  Methoden. Ein Record mit Lambdas schlägt eine Vererbungshierarchie mit Template-Methoden (STR-060,
  `IdListScreen`-Spec).

## B8. Eine Wahrheit pro Fachfrage — aber nur pro gleicher Frage

- Fachprädikate leben an einer Stelle, und alle Schichten fragen dort (`Terrain` nach STR-107: Mesh,
  Walker und Journey fragen dieselbe Methode, damit Plan und Gang sich nicht widersprechen). Keine
  Kopie im Aufrufer.
- Zwei Fragen, die gleich aussehen, bekommen zwei Methoden mit Namen, die die Frage sagen:
  `hasFloorWithinOneBlock` (ist ein *Schritt* überlebbar?) ist nicht `isStandable` und nicht
  `isSturdyFloor` (ist das *der Boden, auf dem gearbeitet wird*?) — die geliehene Antwort ließ den Bot
  in eine Senke laufen (bot/CLAUDE.md, STR-089). Zusammenlegen ist genauso falsch wie Kopieren.

## B9. Jede Ressource hat Besitzer, Grenze und Aufräumen

- Caches und Sammlungen sind privat; Zugriff nur über Methoden. Kein öffentliches veränderliches
  statisches Feld (`MeshManager.meshes` wurde vom bot direkt gelesen).
- Jede wachsende Sammlung hat eine Obergrenze oder Verdrängung und wird in `STOPPING` geleert.
- Eine Verdrängung darf keinem Aufrufer etwas unter den Füßen wegziehen, das er gerade benutzt —
  vorher alle Nutzer der Sammlung auflisten (STR-091).

## B10. Neue Modi als eigene Klasse, nicht als weiteres Flag

- Eine neue Art, etwas zu tun, ist eine neue Implementierung hinter einem Interface (wie
  `TravelMethod`), nicht ein weiterer `boolean` in einer bestehenden Klasse (`cruising`,
  `travelling` in `PathWalker`).
- Strategie und Ausführung bleiben getrennt (bot: Behaviors planen, Controller führt aus, Runner
  bewacht). Restock wurde deshalb eine eigene Behavior statt einer weiteren Phase im `BotController`
  — als Phase hätte es die Ausführungsschicht zur Strategieschicht gemacht (STR-087).

## B11. Der Vertrag ist dokumentiert

- Javadoc an jedem API-Typ: was er tut, welche Vorbedingungen er selbst herstellt, welche Zustände und
  Gründe er liefert, auf welchem Thread er läuft, ab welcher Lifecycle-Phase er benutzbar ist.
- Die Modul-CLAUDE.md hat einen kurzen Abschnitt „Einbinden“: wie ein anderes Modul die Fähigkeit in
  wenigen Zeilen nutzt oder erweitert — wie „Adding a new travel method“ in pathfinding.
- Begründungen und verworfene Alternativen gehören ins Vault, nicht in die CLAUDE.md.

---

## Neue Lehren

Neue Belege oder Regeln hier mit Ticket-ID ergänzen und STR-122 per `update` nachtragen.
