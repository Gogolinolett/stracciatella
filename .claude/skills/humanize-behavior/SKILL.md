---
name: humanize-behavior
description: Neue menschliche Verhaltensweisen für den Bot entwickeln oder einen gemeldeten Bot-Tell beheben — mit Steckbrief, den Leitplanken aus den bisherigen Vault-Tickets, Messung und Tests. Verwenden, wenn der Nutzer sagt "wirkt wie ein Bot", "unnatürlich", "menschlicher machen", "ein Mensch würde ...", "Humanness", "Blunder", oder wenn es um Pausen, Blickführung, Zielen, Sichtfeld-Priorisierung, vorausschauende Aktionen oder Haltungen wie Ducken/Rückwärtslaufen beim Bridging geht. Auch unaufgefordert verwenden vor jeder Änderung an HumanBehavior, an der Kamera- oder Bewegungs-Humanisierung, an der Task-Auswahl nach Blickrichtung oder an Blundern.
---

# Menschliche Verhaltensweisen entwickeln

Dieser Skill ist der Ablauf für jede neue oder geänderte Verhaltensweise, die den Bot
menschlicher machen soll. Er bündelt die Muster aus rund 30 Vault-Tickets (STR-008 … STR-120).
Die Einzelheiten stehen in den Tickets.

**Ticket-Verweise lesen:** Eine ID wie `STR-074` steht für die Datei
`knowledge-manager/Vault/Tickets/STR-074.md`, die direkt gelesen wird. Lange Tickets sind per
semantischer Suche oft nicht auffindbar, weil nur die ersten rund 200 Wörter in den Index eingehen
(TOOL-006).

**Referenzen** (bei Bedarf lesen, nicht alle auf einmal):

| Datei | Wann |
|---|---|
| `references/tell-katalog.md` | Schritt 1: Ist dieser Tell schon einmal aufgetreten? |
| `references/bausteine.md` | Schritt 3/4: Gibt es schon einen Baustein, der sich erweitern lässt? |
| `references/leitplanken.md` | Schritt 4: **immer** vor dem Entwurf lesen |
| `references/ideen.md` | Wenn der Nutzer fragt, was sich als Nächstes humanisieren ließe |

---

## Was „menschlich“ heißt

Ein Verhalten ist menschlich, wenn **kein Beobachter** einen Unterschied zu einem Spieler erkennt:

| Beobachter | sieht | typischer Tell |
|---|---|---|
| **Zuschauer** | Kamera, Körper, Haltung, Arm | Schnappen, starrer Blick, Umdrehen nach jedem Block |
| **Server** | Pakete und ihre Abfolge | fehlende Swing-Pakete, zusätzliche ABORTs (STR-065) |
| **Statistik über Minuten** | Kadenz, Verteilungen | jede Pause gleich lang, nie ein Fehler (STR-036, STR-114) |

Jede Verhaltensweise sagt, welchen Beobachter sie täuscht, und verschlechtert die anderen nicht.

---

## Sieben Grundsätze

1. **Erst das Artefakt entfernen, dann Varianz hinzufügen.** Die meisten Tells sind Spuren der
   Implementierung, kein fehlender Zufall. Die häufigste Quelle ist die Einzelschritt-Architektur:
   Der Controller sieht nur *eine* Aufgabe und setzt zwischen gleichartigen Schritten alles zurück
   (Hocke hoch, Kopf herum, neu zielen, Pause). Behoben wurde das meist durch **weniger**
   Bewegung (STR-074, STR-113, STR-042, STR-111).
2. **Jedes Verhalten braucht eine Geschichte in einem Satz, und sie beschreibt die ganze Folge.**
   Zum Beispiel: „Ich drehe der Lücke den Rücken zu, gehe in die Hocke, laufe rückwärts, bis ich
   die Kante sehe, setze den Block und bleibe unten, bis die Brücke fertig ist.“ Ohne Geschichte ist
   es Rauschen, und Rauschen wirkt ebenfalls maschinell.
3. **Die Aufmerksamkeit folgt dem Sichtfeld.** Ein Mensch nimmt zuerst, was er vor sich sieht. Er
   entscheidet von der aktuellen Position und Blickrichtung aus, nicht nach einer vorher sortierten
   Liste, und er zielt auf einen Punkt, den er sieht (STR-116, STR-034).
4. **Ein Mensch denkt voraus.** Er zieht die nächste Handlung vor und tut Dinge gleichzeitig. Er hält
   eine Haltung über viele Einzelschritte und stellt sich dorthin, wo die *nächsten* Schritte bequem
   sind (STR-074, STR-113, STR-117, STR-068).
5. **Menschlichkeit darf nie einen Fehlschlag auslösen und nie langsamer sein als ein Mensch.**
   Sicherheitsrelevante Präzision hat Vorrang. Das Tempo-Budget ist eine menschliche
   Referenzmessung: 12,5 Ticks pro Steinblock (STR-068). Die echte Technik ist fast immer auch die
   effiziente. Macht ein „menschliches“ Verhalten den Bot langsamer oder fehleranfälliger, ist meist
   das Modell falsch.
6. **Rauschen gehört in die Physik, nicht daneben.** Das Ziel wird verschoben, die Kamerafeder fängt
   die Bewegung auf. Den Yaw nie direkt schreiben (STR-020).
7. **Ohne Messung zählt keine Änderung.** Erst die Kennzahl für den Tell, dann der Ist-Wert. Das Ziel
   kommt aus der Absicht oder von einem Menschen, nie aus dem Ist-Zustand. Zum Schluss
   falsifizieren (STR-068).

---

## Ablauf

### 1. Anlass genau fassen

- Den Tell als **beobachtbare Größe** formulieren. Beispiele: „rund 315° Yaw pro Brückenblock“,
  „6,5–9,8° Blickbewegung zwischen Kopf- und Fußblock“, „100 % der Lauf-Ticks auf der Blockmitte“.
- Den Beobachter benennen.
- Belege sammeln: `run/logs/latest.log` aus echten Welten, temporäre Traces mit eigenem Präfix
  (z. B. `ROWDBG`, am Ende wieder entfernen), Paket-Traces.
- Im Vault suchen, mit 2–3 Formulierungen:
  `.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"`.
  Dazu `references/tell-katalog.md` lesen.

### 2. Die menschliche Geschichte

1. **Was sieht der Spieler gerade?** Was liegt im Blick, was ist verdeckt?
2. **Was hat er als Nächstes vor?** Was bereitet er schon vor, was tut er gleichzeitig?
3. **Welche Haltung hält er über mehrere Schritte?** Wann beginnt sie, wann endet sie?
4. **Eine Handlung oder mehrere getrennte?** Pausen gehören nur zwischen getrennte Handlungen
   (STR-042). Nebenhandlungen wie Essen gehören an die Grenzen.
5. **In welchem Kontext?** Dasselbe Symptom kann richtig oder falsch sein. Eine stille Kamera ist am
   Rechner falsch und beim Austabben richtig (STR-036).

Wo möglich, einen echten Menschen messen. Die Referenz von 16 Blöcken in 10 s stammt vom Nutzer.

### 3. Einordnen

| Kategorie | Frage |
|---|---|
| **A. Varianz** | Variiert ein Wert, der sonst immer gleich ist? |
| **B. Takt und Pausen** | Fehlt ein Moment des Nachdenkens, oder ist die Kadenz konstant? |
| **C. Motorische Unschärfe** | Ist die Ausführung zu exakt (Linie, Blick, Tastendruck)? |
| **D. Aufmerksamkeit und Auswahl** | Würde ein Mensch nach dem, was er sieht, anders wählen (Ziel, Weg)? |
| **E. Vorausschau und Kontinuität** | Würde ein Mensch etwas vorziehen, gleichzeitig tun oder eine Haltung halten? |
| **F. Blunder** | Fehlt der seltene, korrigierte Fehler? |
| **G. Paket-Parität** | Sieht die Leitung anders aus als bei einem Spieler? |

Berührt ein Verhalten mehrere Kategorien (die Brücke ist D und E), gelten die Leitplanken aller
dieser Kategorien. In `references/bausteine.md` nachsehen, ob sich ein vorhandener Baustein
erweitern lässt. Neue Bausteine nur, wenn das nicht geht.

### 4. Entwerfen und dem Nutzer vorlegen

- `references/leitplanken.md` lesen.
- Den **Steckbrief** (unten) ausfüllen, mit mindestens zwei Alternativen samt Pro und Contra
  (STR-001). Der naheliegende Ansatz war oft falsch: „Durchgehend laufen“ beim Brücken brückte am
  Ziel vorbei (STR-074), ein A*-Strafkreis als Umweg wurde vom Cruise wieder geradegezogen
  (STR-114).
- Das Verhalten in die Schicht legen, die die *sichtbare* Ausgabe bestimmt. Behaviors simulieren nie
  Eingaben, das tut nur der Controller (Schichtung in `modules/bot/CLAUDE.md`).
- **Den Steckbrief dem Nutzer vorlegen, bevor implementiert wird.** Was „natürlich“ ist, entscheidet
  der Nutzer. Bisherige Verhaltensweisen brauchten bis zu sechs Feedback-Runden (STR-074, STR-117).

### 5. Messbar machen

- Die Kennzahl muss **den Tell** erfassen und nichts Benachbartes (STR-111).
- Kontinuität misst man mit Quoten und Summen: Hocke nie gelöst ab dem ersten Klick, W auf
  mindestens 95 % der Ticks, Yaw pro Block (`BridgeYaw` höchstens 40°), kein Schritt zurück.
- Die Auswahl misst man daran, welches Ziel zuerst genommen wird.
- Ausgangswert messen, Ziel aus der Absicht ableiten, Schwelle begründen.
- Sensoren prüfen: Misst der Sensor die Wirkung oder eine API-Antwort? Immer die Tickrate angeben
  (STR-068).

### 6. Testen

- Der In-Game-Test misst den **Verlauf**: Abtastung pro Tick im `waitFor`-Prädikat, nur
  Feldarithmetik, kein Logging pro Tick.
- **Die Fixture muss das Verhalten erzwingen.** Eine Fixture, in der der Bot nicht laufen muss,
  testet den Lauf nicht (STR-074). Für die Auswahl nach Sichtfeld heißt das: Das nähere Ziel liegt
  hinter dem Bot, das fernere im Blick, und das hintere wird zuerst eingereiht.
- Die Baseline gehört in die Setup-Phase, vor den Start (STR-059).
- Zufall steuern:
  - Fixtures unterdrücken Blunder.
  - Der Test eines Blunders hebt das auf und setzt die Chance auf 1, alles im `finally`.
  - Bei Bedarf den Jitter festsetzen.
  - Bei stochastischem Verhalten `repeat 3`.
  - Eine Zufallschance auf 1 macht einen Ausreißer, der 1-mal in 3 Läufen auftritt,
    reproduzierbar.
- Verteilungen per JUnit an reinen Funktionen prüfen (`HumanBehaviorTest`,
  `./gradlew :modules:bot:test`).
- Zweiseitig prüfen: das menschliche Merkmal **und** die Funktion.
- **Falsifikation ist Pflicht:** den Fix zurücknehmen (Konstante auf 0, Flag ignorieren,
  `git stash`), dann muss der Test rot werden.
- Läufe nach dem Projekt-Workflow:
  - Erst `./gradlew runMinecraftTests -Psuites=<modul>`, am Ende einmal ohne `-Psuites`.
  - Ergebnis ist nur der `TEST RESULTS`-Block in `run/logs/latest.log`. Nie zwei Läufe
    gleichzeitig.
  - Die Live-`run/stracciatella/bot.json` vorher beiseitelegen, denn die Suite liest sie
    (TOOL-007).
  - Fehlschläge mit den bekannten Flakes abgleichen, bevor sie als Regression gelten.

### 7. Iterieren und dokumentieren

- Nutzer-Feedback durchläuft immer dieselbe Schleife: Beobachtung → Trace → Ursache → Fix →
  strengerer Test. Zusicherungen kommen hinzu und werden nie entfernt.
- In der Modul-`CLAUDE.md` eine Zeile in *Human-Like Behavior* und neue Config-Knöpfe in *Config*.
  Das ist nur die Karte, keine Begründungen.
- Ein Vault-Ticket über den Skill `store-knowledge` anlegen: Entscheidung, Alternativen, Messung
  vorher/nachher, Falsifikation, Lehre. Die **ersten rund 200 Wörter sind der Suchhaken**
  (TOOL-006).
- **Diesen Skill pflegen:** Eine neue allgemeine Lehre kommt mit Ticket-ID in
  `references/leitplanken.md`, ein neuer Baustein in `references/bausteine.md`, eine neue Tell-Klasse
  in `references/tell-katalog.md`.

---

## Steckbrief

```
Name:              <kurz, Verb + Objekt>
Tell:              <beobachtbare Größe + gemessener Ist-Wert>
Beobachter:        Zuschauer | Server | Statistik
Geschichte:        <ein Satz über die ganze Folge: was tut ein Mensch hier und warum>
Kategorie:         A Varianz | B Takt | C Motorik | D Aufmerksamkeit | E Vorausschau | F Blunder | G Leitung
Artefakt zuerst:   <welche Implementierungsspur wird entfernt, oder "keine">
Sichtfeld:         <was sieht der Spieler; wird nach Blick gewichtet; Rückfall für Verdecktes>
Vorausschau:       <was kommt als Nächstes; was wird vorgezogen oder gleichzeitig getan;
                    unter welcher Bedingung kommt die Folgeaktion sicher>
Haltung:           <welche Haltung über welche Schritte; Beginn ("jetzt", nicht "geschuldet");
                    Ende als Zustandsbedingung; Rückfall in IDLE/stop>
Auslöser/Ort:      <Phase/Übergang/Behavior, Klasse>
Häufigkeit/Ebene:  <pro Tick | Ziel | Phase | Reihe | Etappe | Sitzung>, Chance
Verteilung:        <log-normal | Gauß geklemmt | gleichverteilt (nur kleine Fenster)>
Ausschlüsse:       <Sprünge, Kanten, Gates, Platzierung, Tests, festgelegte Reihenfolge>
Wechselwirkungen:  <Planer (plant er aus der Körperposition?), Queue-Reihenfolge,
                    Zeitfenster, andere Humanisierer>
Tempo-Kosten:      <Ticks pro Einheit gegen das Budget; verschobene feste Kosten?>
Config:            <Knopf, Standardwert, Blunder-Schalter ja/nein>
Kennzahl/Ziel:     <Metrik, Schwelle, Herleitung>
Falsifikation:     <womit wird der Test rot>
Tests:             <Fixture, die das Verhalten erzwingt; repeat n; JUnit>
Alternativen:      <mind. 2, Pro/Contra, Grund der Wahl>
```

---

## Definition of Done

- [ ] Steckbrief ausgefüllt und vom Nutzer gesehen, Geschichte über die ganze Folge
- [ ] Artefakt geprüft und, falls vorhanden, zuerst entfernt (besonders Rücksetzer zwischen Einheiten)
- [ ] Auswahl nach Sichtfeld gewichtet, nicht gefiltert; eine festgelegte Reihenfolge respektiert
- [ ] Vorausschau nur unter sicherer Folgebedingung; der Planer wird nicht überholt
- [ ] Haltung: Beginn bei „jetzt“, Ende an einer Zustandsbedingung, Rückfall für IDLE/stop, Taste nur bei Änderung angefasst
- [ ] Kein Gate geschwächt, keine Sicherheitsregel aufgeweicht
- [ ] Tempo gegen das Budget gemessen, mit Tickrate; auf verschobene feste Kosten geprüft
- [ ] Zufall in der richtigen Ebene und Verteilung über `HumanBehavior`; Blunder hinter `HumanBehavior.blunder`
- [ ] Knöpfe in `BotConfig`, Chance 0 schaltet ab
- [ ] Keine neuen Paketmuster
- [ ] Die Fixture erzwingt das Verhalten; Baseline im Setup; zweiseitig geprüft; falsifiziert
- [ ] Modul-Suites grün, Gesamtlauf mit bekannten Flakes abgeglichen, Live-`bot.json` beiseitegelegt
- [ ] `CLAUDE.md` aktualisiert, Vault-Ticket mit Suchhaken angelegt, temporäre Traces entfernt
- [ ] Skill-Referenzen um neue Lehre, neuen Baustein oder neue Tell-Klasse ergänzt
