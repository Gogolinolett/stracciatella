# Leitplanken (Lessons Learned)

Vor jedem Entwurf lesen. Jede Regel hat mindestens ein Ticket, in dem sie teuer gelernt wurde.
Eine neue allgemeine Lehre kommt hier mit Ticket-ID dazu.

## 1. Verteilungen, Häufigkeit, Ebene

- **Dauern log-normal, Versätze gauß-verteilt und geklemmt.** Gleichverteilung nur für winzige
  Fenster von 1–3 Ticks. Gleichverteilte Pausen von 12–25 Ticks ergaben „jede Pause dieselbe
  Sekunde“ (STR-036).
- **Einmal würfeln, für die ganze Einheit nutzen.** Die Einheit ist die, bei der sich der Zustand
  eines Menschen ändert: Ziel, Phase, Reihe, Sitzung. Öfter würfeln erzeugt „zielt bei jedem Block
  neu“ (STR-113, STR-117). Pro Tick nur als Random Walk mit Rückzug oder als seltene Erneuerung.
- **Nicht bei jeder Wiederholung.** Eine Pause bei jeder Wiederholung *ist* die konstante Kadenz,
  die sie brechen soll. Seltene Ereignisse gehören an natürliche Übergänge, etwa „gesehen, gleich
  laufe ich los“ (STR-042, STR-036).
- **Keine unkorrelierten Verzögerungen am selben Übergang stapeln.** Zwei kurze Pausen sind von
  einer etwas längeren nicht zu unterscheiden (STR-042).
- **Konservative Standardwerte**, jeder Knopf in `BotConfig`, Chance 0 schaltet ab.

## 2. Sichtfeld und Auswahl

- **Gewichten statt filtern.** Das Sichtfeld macht ein Ziel billiger, es schließt keines aus. Ein
  harter Filter ließ Ziele hinter dem Bot liegen oder hätte einen Rückfall gebraucht (STR-116).
- **Im Moment der Entscheidung fragen**, von der aktuellen Position und Blickrichtung aus. Eine
  beim Start sortierte Liste ist eine feste Tour: Der Bot drehte der Kiste vor ihm den Rücken zu, um
  zur nächsten auf der Liste zu gehen (*Restocking* in `modules/bot/CLAUDE.md`).
- **Den Abstand von den Füßen messen, die Drehung vom Auge entlang des Blicks.** Den Abstand vom
  Auge zu messen hätte nebenbei die Reihenfolge Kopf vor Fuß verändert (STR-116).
- **Das Sichtfeld schlägt keine Reihenfolge, die ein Behavior schon festgelegt hat**
  (`orderedTasks`). „In Sicht zuerst“ gilt nur in der Sackgasse. Immer angewandt, brachte es die
  Reihenfolge des Sweeps durcheinander (`takeTaskInSightInstead`). Ohne `orderedTasks` griff
  nearest-first hinter eine noch stehende Spalte, und der Aufwand stieg von 19,1 auf 62,0 Ticks pro
  Block (STR-068).
- **Die Sichtprüfung muss dieselbe Linie prüfen, die das Zielen benutzt**, sonst kann Laufen sie
  nicht freimachen (`approachOccluded`: Strahl zur Fläche, nicht zur Blockmitte).
- **Der humanisierte Zielpunkt bleibt aus der Sichtprüfung heraus.** Sonst gibt dieselbe Frage bei
  jedem Aufruf eine leicht andere Antwort. Er wird getrennt auf einen sichtbaren Teil der Fläche
  gelegt, mit Rand, weil Feder und Sakkaden einen gerade noch sichtbaren Punkt verfehlen (STR-034).
- **Was im Blick liegt, ist meist auch das Robusteste:** Es kostet seltener einen Look-Timeout
  (STR-116).

## 3. Vorausschau und Kontinuität

- **„Geschuldet“ und „jetzt“ trennen.** Ein Prädikat, das zugleich steuert, *wohin* und *wie*
  gelaufen wird, ist für eines von beiden falsch. Die Hocke kam 4 Blöcke vor der Kante, weil
  „Kantenschritt geschuldet“ auch „jetzt ducken“ hieß. Mit `atEdgeStepRim` beginnt sie bei 1,5,
  hergeleitet aus der Bodenprüfung bei 1,0 (STR-074).
- **Eine Haltung endet an einer Zustandsbedingung, nicht an der Aufgabengrenze.** Die Hocke löst
  sich erst, wenn wieder Boden unter dem Bot ist **und** keine Folgeaufgabe vom Rand baut. Der
  gerade gesetzte Block ist Client-Vorhersage und zählt nicht als Boden. Für IDLE gibt es einen
  Rückfall, weil ein erfolgreiches Behavior-Ende `BotController.stop()` nicht aufruft (STR-074).
- **Beim Lösen einer Haltung an die Sicherheit denken.** Eine gelöste Hocke plus Restschwung über
  dem Loch ist genau der Sturz, den die Hocke verhindern sollte (STR-074).
- **Eine gehaltene Taste nur bei Änderung anfassen** (`sneakKeyHeld`). Wer sie jeden Tick auf
  `false` setzt, nimmt dem Spieler die Taste weg, solange das Modul geladen ist.
- **Die Haltung so wählen, dass die nächste Aktion schon passt.** Der Blick hinter den Bot ist der
  Yaw, den die Platzierung braucht. Sein Abstand (`BRIDGE_LOOK_BACK` 0,3) setzt den Pitch so, dass
  LOOKING nicht vor jedem Klick nach unten reißt (STR-074).
- **Phasen der Zustandsmaschine nicht blind durchlaufen.** SCANNING vor jedem Brückenblock kostete
  rund 315° pro Block. Eine Phase, die ein Mensch nicht macht, wird übersprungen
  (`beginCurrentTask`, STR-074).
- **Vorziehen nur unter genau der Bedingung, unter der die Folgeaktion sicher kommt.** Das Drehen
  zum nächsten Block während der Bestätigung hat dasselbe Gate wie `continueSeam`. Beim Baum, dessen
  nächster Stamm ein Sub-Target ist, wäre es eine falsche statt einer frühen Drehung gewesen
  (STR-068).
- **Vorausschau darf den Planer nicht überholen.** Plant das Behavior die nächste Zelle aus der
  Fußposition, darf keine Bewegung über den Zielpunkt hinausgehen. „Durchgehend laufen“ beim Brücken
  brückte 14 statt 5 Blöcke (STR-074).
- **Eine Folge zu erkennen braucht Gedächtnis**, denn der Controller sieht nur eine Aufgabe
  (`lastLaid`, `trackRow`).
- **Eine Entscheidung, die nur in einem Zeitfenster fällt, braucht einen Rückfall.** Eine
  Atempause verschluckte das Fenster der Reihenerkennung (STR-117).
- **Eine Laufregel, die auf der Queue fußt, muss den Planungszeitpunkt des Behaviors kennen.** Der
  Miner plant die nächste Spalte während des Bruchs (STR-117).
- **Überlappen kann feste Kosten nur verschieben.** Vanillas `destroyDelay` wanderte von LOOKING in
  den nächsten Bruch (STR-068). Messen, nicht annehmen.
- **Der Standplatz dient den nächsten Schritten.** Am Rand der Reichweite (3,9) lag jeder nächste
  Block außer Reichweite. Von 2,0 aus läuft die Naht, der Abbau ohne Sammeln wurde 3,5-mal
  schneller (`WORK_DISTANCE`).
- **Nebenhandlungen an Grenzen, nie mitten in einer Handlung** (`autoEat`, STR-084).
- **Wiederholte Arbeit: der Körper zielt, die Kamera steht.** Ein fester Winkel braucht einen
  festen Abstand, also lehnt der Körper an der Fläche (STR-117).

## 4. Gates und Sicherheit

- **Ein Gate darf nie brechen.** Jitter nur in der Flächenebene, Sakkaden zählen nicht für
  `isAimedAt` (STR-034, STR-020).
- **Präzision schlägt Menschlichkeit:** keine Strafes bei Sprüngen und Kanten (STR-010), der
  Knotengang für Parkour (STR-111), kein Zögern bei Kettensprüngen (STR-012).
- **Eine zu breit angewandte Vorliebe bricht Sonderfälle.** Die Wandkosten bestraften erst Kanten,
  dann Landeknoten, und Sprünge wurden falsch (STR-120). Auf den Fall beschränken, der der Tell ist,
  und die Nachbar-Suites mitlaufen lassen.
- **Eine Reihenregel, die nur x und z vergleicht, sieht in jeder Treppe eine Reihe.** Eine Regel,
  die den Körper in Zellen laufen lässt, muss prüfen, ob ein Körper hineinpasst (`opensForBody`,
  STR-117).
- **Eine Lage ist erst behoben, wenn auch der Weg heraus funktioniert**, nicht nur einer der Wege
  hinein (STR-117).
- **Blunder nur dort, wo sie sicher zu Ende gehen.** Ein Blunder, der scheitert, beendet den Lauf
  (`canDigByMistake`: in Reichweite, in Sicht, Füllmaterial dabei, Stützfläche zum Auge). **Und nur
  echte Fehler an echten Orten:** „eins zu tief“ wurde entfernt, „zu weit“ gräbt nur in Fels
  (STR-114).
- **Haltungen mit Sicherheitsfunktion** brauchen keine Policy-Option, wenn es keine Gegenseite gibt.
  Ohne sie ist die Aufgabe nicht langsamer, sondern unmöglich (STR-074).

## 5. Physik und Geometrie

- **Ziel verschieben statt Winkel schreiben**, damit die Feder das Rauschen aufnimmt (STR-020).
- **Varianz auch in der Form der Bewegung, nicht nur im Tempo:** Wird nur die Beschleunigung
  skaliert, entsteht ein leichtes Überschwingen. Perfekt gedämpft wirkt bei jedem Tempo maschinell
  (STR-021).
- **Vorsicht bei entarteter Geometrie:** `atan2` über wenige Zentimeter ergibt einen zufälligen Yaw.
  Betroffen sind ein Ziel unter den Füßen, ein Drop direkt unter dem Bot und das Graben nach unten.
  Den Jitter dort nicht abschalten (`atan2(0,0)` zeigt immer nach Norden), sondern entlang des
  vorhandenen Yaws legen (STR-077).
- **Ein Reaktionstakt ist eine Entscheidungspause, kein Einfrieren der Motorik:** Der Körper hält,
  der Blick schwingt aus und zittert weiter (STR-025).

## 6. Server-Wahrheit und Pakete

- **Auf den Server warten, nicht auf die Vorhersage.** In eine gerade abgebaute Zelle wird erst nach
  `ServerBlockSync.isSettled` platziert, ein Reihenwinkel wird erst danach freigegeben (STR-114,
  STR-117).
- **Über Vanilla-Pfade treiben.** Keine Pakete erzeugen, die ein Spieler nicht senden würde. Leere
  Klicks rufen nie `attack` auf, auch keinen Schwung auf einen Mob (STR-065, STR-115).
- **Paket-Inventur ohne Filter auf den eigenen Zustand.** Der Filter „bin ich gerade am Abbauen“ hat
  das Slot-Paket versteckt (STR-065).

## 7. Messung

- **Schwellen nie aus dem Ist-Zustand ableiten.** Sie zertifizieren genau das Verhalten, gegen das
  der Test geschrieben wurde (STR-068).
- **Sensoren können die API-Antwort messen statt der Wirkung:** `isDestroying()` war in 482 Ticks nie
  gesetzt, die Schwung-Quote meldete 79 % statt 25 % echter Arbeit (STR-068).
- **Eine Tempozahl ohne Tickrate ist wertlos.** Roundtrips kosten bei 10x zehnmal so viele Ticks
  (STR-068).
- **Die Kennzahl muss den Tell treffen.** In STR-111 blieb die seitliche Abweichung fast gleich,
  verändert hatte sich der konstante Yaw.
- **Reaktionspausen tauchen in Phasenmessungen nicht auf**, weil `tick()` vor `phaseTicks++`
  zurückkehrt (STR-042).

## 8. Konfiguration und Tests

- **Alle Blunder laufen über `HumanBehavior.blunder(chance)`.** Tests setzen nie Config-Chancen auf
  0, weil sonst ein Zurückschreiben in die `bot.json` des Spielers droht (STR-114, STR-104).
- **Alles, was eine Präzisions-Zusicherung flaky machen würde, gehört hinter den Blunder-Schalter**
  (Seitenblick in der Pause, STR-036).
- **Config-Listen in Tests vollständig wiederherstellen** (Snapshot und dann `clear` + `addAll` im
  `finally`), nicht nur den eigenen Eintrag zurücknehmen (STR-104).
- **Eine Fixture, in der der Bot nicht laufen muss, testet den Lauf nicht** (STR-074).
- **Die Baseline wird vor dem Start des Verhaltens gemessen** (STR-059).
