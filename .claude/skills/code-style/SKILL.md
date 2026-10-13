---
name: code-style
description: Code-Stil und Erweiterbarkeits-Regeln für Stracciatella-Java-Code — Formatierung, Imports, Benennung, Klassenaufbau, Logging, Exceptions, Javadoc und Mixins, dazu der Zuschnitt öffentlicher APIs, Erweiterungspunkte, Laufobjekte und Ergebnis-Handles, abgeleitet aus loader, core und fullscreen und aus den Integrationsproblemen des pathfinding-Moduls (STR-122). Verwenden, wenn der Nutzer nach Code-Style, Konventionen, Stil, Formatierung, API-Design, Schnittstellen, Erweiterbarkeit oder dem Einbinden eines Moduls fragt oder "wie soll ich das schreiben/schneiden" sagt. Auch unaufgefordert verwenden, bevor eine neue Klasse, ein neues Modul, eine öffentliche API, ein Registry- oder Erweiterungspunkt oder ein langlaufender Vorgang (Lauf, Reise, Behavior) geschrieben wird, und beim Review solcher Änderungen.
---

# Code-Stil und Erweiterbarkeit

Zwei Teile:

- **Teil A — Stil:** wie Code aussieht. Abgeleitet aus dem loader (Referenzstil), core und fullscreen
  plus der Formatter-Konfiguration `.idea/codeStyles/Project.xml`.
- **Teil B — Erweiterbarkeit:** wie ein Modul geschnitten wird, damit andere es reibungslos einbinden
  und erweitern. Jede Regel stammt aus einem belegten Problem beim Einbinden des pathfinding-Moduls.

Entscheidung, Alternativen und Begründung: Ticket **STR-122**.

**Ticket-Verweise lesen:** Eine ID wie `STR-091` steht für die Datei
`knowledge-manager/Vault/Tickets/STR-091.md`, die direkt gelesen wird. Lange Tickets sind per
semantischer Suche oft nicht auffindbar, weil nur die ersten rund 200 Wörter in den Index eingehen
(TOOL-006).

**Referenzen** (bei Bedarf lesen, nicht alle auf einmal):

| Datei | Wann |
|---|---|
| `references/stil.md` | Bei Zweifel an einer Stilfrage; beim Review; Liste der Muster, die im Bestand kein Vorbild sind |
| `references/erweiterbarkeit.md` | **Immer** vor einer neuen öffentlichen API, einem neuen Modul, einem Erweiterungspunkt oder einem langlaufenden Vorgang |

---

## Grundsätze

1. **Der loader ist der Referenzstil.** fullscreen ist eine Portierung des Fremd-Mods *Borderless
   Mining* (`getInstance()`, `setX()`, explizite Typen, log4j) und kein Vorbild; core ist Gerüst.
2. **Nur geänderter Code folgt dem Leitfaden.** Bestehende Dateien werden nicht nebenbei
   umformatiert oder umbenannt — das bläht Diffs auf und verdeckt die eigentliche Änderung.
3. **Erweiterbarkeit entscheidet sich am API-Schnitt, nicht an der Formatierung.** Ein sauber
   formatiertes Modul, in dessen Innereien andere Module greifen müssen, ist nicht erweiterbar.
4. **Eine neue Regel braucht einen Beleg.** Neue Lehren kommen mit Ticket-ID in die passende
   Referenzdatei (siehe *Diesen Skill pflegen*).

---

## Teil A — Stil in Kurzform

- **Formatter verbindlich:** 4 Spaces, kein Zeilenumbruch (rechter Rand 999), höchstens eine
  Leerzeile, Enum-Konstanten je Zeile. Der Formatter fügt manuelle Umbrüche wieder zusammen
  (`KEEP_LINE_BREAKS=false`); mehrzeilige Ketten nur zwischen `// @formatter:off` und
  `// @formatter:on`.
- **Imports:** statisch (immer `*`) → `java.*` → Rest alphabetisch, Gruppen durch Leerzeile getrennt;
  normale Imports nie mit `*`.
- **Namen:** Accessoren ohne `get`/`set` (`name()`, Setter `name(value)`, oft package-private);
  `X` + `SimpleX`/`DefaultX`; Suffixe `Hack`, `Utils`, `Setup`, `Module`, `Mixin`;
  `LOGGER`, Strings und Primitive in `UPPER_SNAKE`, andere `static final`-Objekte camelCase;
  Kurzformen `cls`, `e`/`t`, `in`/`out`, `it`, `f`; ungenutzt heißt `ignored` bzw. `unused`.
- **Klassenaufbau:** öffentliche Konstanten → `LOGGER`/`static`-Felder → `static {}` → `final`-Felder,
  Leerzeile, veränderliche Felder → Konstruktoren → API → private Helfer → verschachtelte Typen.
- **Java 21:** `var` für Lokale, Switch-Ausdrücke, Pattern Matching, Records mit `of`/`parse`,
  Methodenreferenzen, try-with-resources für alles Schließbare.
- **Kompakt:** Guard-Clauses einzeilig ohne Klammern (`if (x == null) return null;`); Klammern ab
  `else`, mehreren Anweisungen oder Verschachtelung; lange Methoden in `{ // phase … }`-Blöcke.
- **Markierungen:** `@NotNull`/`@Nullable` in Type-Use-Position (`public @Nullable Foo bar()`),
  `@ApiStatus.Internal`, `@Contract("_->fail")`.
- **Exceptions:** `throw StracciatellaThrowables.propagate(t);`; `IllegalStateException`,
  `IllegalArgumentException`, `UnsupportedOperationException`; Text englisch mit Kontext.
- **Logging:** SLF4J, `{}`-Platzhalter, Exception zuletzt, nie `System.out`/`printStackTrace()`.
- **Kommentare:** englisch, erklären das Warum. Javadoc: Zusammenfassungssatz, dann `<p>`-Absatz
  mit dem Warum. Kein auskommentierter Code.
- **Mixins:** Paket `<modul>.mixin`, registriert in `<modul>.mixins.json` **und**
  `build.gradle.kts`; Injector-Attribut `method` zuerst; hinzugefügte Methoden mit Präfix
  `stracciatella$` über ein Hook-Interface.
- **Modul-Einstieg:** Hauptklasse trivial, Lifecycle-Methoden mit `@Task`, alles Weitere in einer
  `XSetup`-Klasse (Vorbild `MinerModule`/`MinerSetup`, Klassenlader-Regel in CLAUDE.md).

Details und Beispiele: `references/stil.md`.

---

## Teil B — Erweiterbarkeit in Kurzform

| Regel | Kern |
|---|---|
| **B1** Eine Fassade pro Fähigkeit | Ein Aufruf erreicht das Ziel des Aufrufers; Interna in `<modul>.internal.*` mit `@ApiStatus.Internal`; fremde Module importieren nie aus `.internal.` |
| **B2** Handle mit Zustand und Grund | Langlaufendes gibt `status()` + `failReason()` zurück; vor dem Start `PENDING`, nie ein Endzustand; Ankunft entscheidet der Anbieter; Fehler als Wert, Chat nur in Commands |
| **B3** Ein Lauf ist ein Objekt | Laufzustand im Laufobjekt, nicht in statischen Feldern; Optionen als Record; keine Methodenvarianten, keine globalen Setter; statischer Einstieg hält nur den aktuellen Lauf |
| **B4** Vorbedingungen selbst herstellen | Sonst `FAILED` mit Grund; keine „vorher muss X aufgerufen sein“-Regeln; Tests durch denselben Einstieg wie die Produktion |
| **B5** Neues als Option mit altem Default | Erweiterung ändert nie das Ergebnis bestehender Aufrufer; Default-Änderung nur mit Ticket und Aufruferliste |
| **B6** Interface unten, Registrierung von oben | Abhängigkeit nur nach unten; Registrierung in `XSetup`; jede mit `unregister` in `STOPPING`; ohne Registrierung keine Fähigkeit, kein Flag |
| **B7** Pflicht abstrakt, Default harmlos | `default` nur, wenn Vergessen harmlos ist; Zusatzdaten als Record statt weiterer Methoden |
| **B8** Eine Wahrheit pro Fachfrage | Ein Prädikat an einer Stelle; gleich aussehende, verschiedene Fragen bekommen eigene Methoden |
| **B9** Besitzer, Grenze, Aufräumen | Keine öffentlichen veränderlichen statischen Sammlungen; jede wachsende Sammlung begrenzt und in `STOPPING` geleert |
| **B10** Neuer Modus = neue Klasse | Implementierung hinter einem Interface statt weiterer `boolean`; Strategie und Ausführung getrennt |
| **B11** Vertrag dokumentiert | Javadoc: Zweck, Vorbedingungen, Zustände/Gründe, Thread, Lifecycle-Phase; Abschnitt „Einbinden“ in der Modul-CLAUDE.md |

Belege, Code-Schemata und Vorbilder im Projekt: `references/erweiterbarkeit.md`.

---

## Ablauf

### 1. Vor dem Schreiben

- Die CLAUDE.md des betroffenen Moduls lesen und im Vault suchen (2–3 Formulierungen):
  `.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"`.
- Entsteht eine öffentliche API, ein neues Modul, ein Erweiterungspunkt oder ein langlaufender
  Vorgang: `references/erweiterbarkeit.md` lesen und den **API-Steckbrief** (unten) ausfüllen.
- Zuerst die **Aufrufer** benennen: Wer ruft das auf, und was will er in einem Aufruf erreichen? Eine
  API, die nach den eigenen Schritten statt nach dem Bedarf der Aufrufer geschnitten ist, wird
  umgangen (STR-091).

### 2. Den Steckbrief dem Nutzer vorlegen

Bei neuer öffentlicher API den Steckbrief vorlegen, bevor implementiert wird. Die Wahl zwischen
Alternativen ist dokumentationspflichtig (STR-001).

### 3. Schreiben

Teil A anwenden. Vorhandene Vorbilder im Projekt nutzen statt neu zu erfinden
(`TravelMethod` + `Navigator.register`, `BlockPlacer` + `PathPlacement.register`,
`BehaviorRunner.register`, `GuiRegistry.register`, `BotPolicy`).

### 4. Nach dem Schreiben

- Die Checkliste unten abhaken.
- In der Modul-CLAUDE.md die Karte aktualisieren und bei neuer API den Abschnitt „Einbinden“ ergänzen
  — nur die Karte, keine Begründungen.
- Design-Entscheidungen über den Skill `store-knowledge` ins Vault legen.

---

## API-Steckbrief

```
Fähigkeit:        <was ein anderes Modul damit erreicht, ein Satz>
Aufrufer:         <welche Module/Klassen; was wollen sie in EINEM Aufruf>
Fassade:          <Klasse.methode(args, options) -> Rückgabe>
Ergebnis:         <Handle; Zustände; Fehlergründe; Zustand vor dem Start>
Vorbedingungen:   <welche stellt die API selbst her; was scheitert mit welchem Grund>
Optionen:         <Record; Defaults = bisheriges Verhalten>
Laufzustand:      <wo lebt er; was hält ein statischer Einstieg>
Erweiterung:      <Interface + Registry; wer registriert in welcher Setup-Klasse; unregister in STOPPING>
Defaults:         <welche Interface-Methoden default, warum ist Vergessen harmlos>
Ressourcen:       <Caches/Sammlungen; Besitzer; Grenze; Aufräumen>
Intern:           <was kommt nach internal>
Thread/Lifecycle: <Thread; ab welcher Lifecycle-Phase benutzbar>
Bestehende Aufrufer: <ändert sich ihr Ergebnis? wenn ja: Liste + Ticket>
Alternativen:     <mind. 2, Pro/Contra, Grund der Wahl>
```

---

## Checkliste (Definition of Done)

Stil — jede Änderung:

- [ ] Formatter-konform, Imports in drei Gruppen, keine Wildcard außer statisch
- [ ] Namen und Klassenaufbau wie Teil A; nur geänderter Code angepasst
- [ ] Kein `System.out`, kein `printStackTrace()`, kein auskommentierter Code, kein Test-/Demo-Code im Produktivpfad
- [ ] Schließbares in try-with-resources; Logging mit `{}` statt Verkettung
- [ ] Neue Mixins in der JSON **und** in `build.gradle.kts` registriert

Erweiterbarkeit — bei öffentlicher API, neuem Modul oder Erweiterungspunkt:

- [ ] Alles, was nicht API ist, liegt in `internal` und ist markiert
- [ ] Ein Aufrufer erreicht sein Ziel mit einem Aufruf
- [ ] Langlaufendes liefert ein Handle mit `status()` und `failReason()`; vor dem Start kein Endzustand
- [ ] Vorbedingungen stellt die API selbst her
- [ ] Optionen als Record, keine globalen Setter, kein Laufzustand in statischen Feldern
- [ ] Ergebnis bestehender Aufrufer unverändert — oder Ticket mit Aufruferliste
- [ ] Jede Registrierung in einer Setup-Klasse, mit `unregister` in `STOPPING`
- [ ] Jede `default`-Methode ist harmlos, wenn man sie vergisst
- [ ] Jede wachsende Sammlung hat Besitzer, Grenze und Aufräumen
- [ ] Vertrag im Javadoc, Abschnitt „Einbinden“ in der Modul-CLAUDE.md, Vault-Ticket angelegt

---

## Diesen Skill pflegen

- Eine neue Stil-Entscheidung (dort, wo der Bestand uneinheitlich ist) kommt mit Begründung in
  `references/stil.md`.
- Eine neue Erweiterbarkeits-Lehre kommt mit Ticket-ID als Beleg in `references/erweiterbarkeit.md`
  und, wenn sie eine neue Regel ist, als Zeile in die Tabelle oben.
- STR-122 per `update` um die Änderung ergänzen.
