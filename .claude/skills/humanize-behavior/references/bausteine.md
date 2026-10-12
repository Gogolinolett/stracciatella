# Bausteinkatalog (Bestand)

Was es schon gibt, mit Verteilung oder Bedingung und Ort im Code. Bevor ein neuer Baustein
entsteht, prüfen, ob sich einer von diesen erweitern lässt. Die Buchstaben verweisen auf die
Kategorien in `SKILL.md`. Klassen ohne Modulangabe liegen im bot-Modul.

| Kat. | Baustein | Verteilung / Bedingung | Ort im Code |
|---|---|---|---|
| A | Zielversatz auf der Fläche | Gauß, geklemmt, Mindestbetrag, nur in der Flächenebene | `HumanBehavior.randomAimOffset`, `BotController.aimPoint` (STR-034, STR-077) |
| A | Blickgeschwindigkeit | gleichverteilt pro Ziel × Sitzungsskill | `HumanBehavior.randomLookSpeedMultiplier` |
| A | Sammel-Pitch | gleichverteilt pro Phase | `HumanBehavior.randomCollectGazePitch` (STR-028) |
| A | Sitzungsskill | Gauß [0,85; 1,15] pro Weltbeitritt | `HumanBehavior.rollSessionSkill` |
| B | Reaktionspause | Gauß 4±2, geklemmt 1–10, an 3 Übergängen, nicht in einer Naht | `HumanBehavior.randomReactionDelayTicks`, `BotController.scheduleAction` (STR-042) |
| B | Zögern vor dem Angriff | gleichverteilt 1–3 Ticks | `HumanBehavior.randomPreAttackHesitation` (STR-041) |
| B | Atempause und Blick in der Pause | 4 %, log-normal 6–60; ab 20 Ticks gelegentlich Seitenblick | `HumanBehavior.randomBreather`, `BotController.pauseBetweenWork`, `pauseGaze` (STR-036) |
| B | Pause vor Weitsprung | Gauß 4–12, nur ab Lücke 5 aus dem Stand | pathfinding: `PathWalker` (STR-012) |
| C | Mikrosakkaden | ±0,5° / ±0,3°, Erneuerung alle ~12 Ticks, zählt nicht für `isAimedAt` | camera: `CameraController` (STR-020) |
| C | Überschwingen | nur die Federbeschleunigung skaliert, Reibung fest | camera: `CameraController.setLookSpeedMultiplier` (STR-021) |
| C | Seitliche Drift | 1–2-Tick-Strafe nur in sicheren Korridoren | pathfinding: `PathWalker` (STR-010) |
| C | Spur und Kurswandern | Spur 0,1–0,35, Random Walk mit Rückzug | pathfinding: `PathWalker`-Cruise (STR-111) |
| D | Nächstes Ziel nach Abstand plus Drehung | +1,5 Blöcke seitlich, +3 dahinter; Drehung vom Auge entlang des Blicks, Abstand von den Füßen | `TaskQueue.distanceWithTurn` / `peekNearest` (STR-116) |
| D | Nächste Kiste ebenso, an jedem Entscheidungspunkt neu gefragt | beim Verlassen, nach dem Teleport, nach dem Schließen | `RestockBehavior.nextStop` |
| D | Verdecktes Ziel wartet auf eines in Sicht | nur in der Sackgasse | `BotController.takeTaskInSightInstead`, `TaskQueue.nearestToView` |
| D | Sichtbarer Zielpunkt | Punkt plus 0,1 Rand frei, sonst nächster freier Punkt | `BotController.visibleAimPoint` (STR-034) |
| D | Anlaufen, bis die Sichtlinie frei ist, sonst Weg herum auf eigenem Boden | Policy `approachOccluded` | `BotController.navigateToStandoff`, `isInSightFrom` (STR-117) |
| D | Blick folgt der Arbeit | Drop, Ziel beim Anlaufen | `BotController.aimCollectGaze` (STR-028) |
| D | Gerade Linie über freie Fläche | Cruise ohne Etappenregeln | pathfinding: `PathWalker.startStraight` (STR-111) |
| D | Abstand zur Wand | Zusatzkosten nur für ebene Schritte | pathfinding: `ChunkMeshBuilder.besideCost` (STR-120) |
| E | Hocke über die ganze Brücke | ab der Kante (1,5) bis wieder Boden da ist und keine Folgeaufgabe vom Rand baut; Taste nur bei Änderung | `BotController.updateBridging`, `atEdgeStepRim`, `buildsOffFloor`, `sneakKeyHeld` (STR-074) |
| E | Rücken zur Lücke, Blick hinten, rückwärts laufen | Kantenschritt ohne vorheriges SCANNING | `BRIDGE_LOOK_BACK`, `walkToward`, `hasFloorToward`, `beginCurrentTask` (STR-074) |
| E | Folge erkennen | Diagonalbrücke über die Ecken; Reihen und Wenden | `lastLaid` / `bridgeSide`, `trackRow` (STR-074, STR-117) |
| E | Ein Winkel pro Reihe, der Körper zielt, W durchgehend | Winkel pro Reihe gewürfelt, gelegentlich verfehlt (Blunder) | `considerRowHold`, `walkAlongRow`, `rowAngleMissChance` (STR-117) |
| E | Durch den aktuellen Block auf den nächsten zielen | Nachbarblock mit gemeinsamer Fläche | `predictiveAim`, `heldAim` (STR-113) |
| E | Während der Server-Bestätigung zum nächsten Block drehen | nur wenn `continueSeam` ihn sicher nimmt | INTERACTING (STR-068) |
| E | Werkzeug während der Kameradrehung wählen | LOOKING Tick 1 | `InventoryHelper.selectBestTool` |
| E | Naht fortsetzen | kein Takt, warme Kamera | `BotController.continueSeam` (STR-042) |
| E | Während des Abbaus laufen und einsammeln | Reichweite und Sicht bleiben erhalten | `stepTowardWork`, opportunistisches Sammeln, Policy `collectWhileMining` (STR-058) |
| E | Standplatz für die nächsten Schritte | 2,0 statt Reichweitengrenze, beim Abbau bis 1,0 hinein | `WORK_DISTANCE`, `CONTACT_DISTANCE`, `closingIn` |
| E | Nebenhandlung an der Grenze | Essen nur zwischen Aufgaben | Policy `autoEat` (STR-084) |
| F | Ausgetabbt | 0,5 %, 3–10 s log-normal, Kamera völlig still | `HumanBehavior.Breather.away` (STR-036, STR-114) |
| F | Umweg, Pause und Seitenblick auf Reisen | pro Etappe bzw. pro Sprint-Sprung | pathfinding: `Journey.Quirks` (STR-114, STR-111) |
| F | Leere Klicks unterwegs | pro Tick, Bursts von 2–6, nie abbauen, nie Entities | `TravelClicks` (STR-115) |
| F | Gegen die Wand laufen | 15 %, 3–10 Ticks, bevor der Weg herum gewählt wird | `HumanBehavior.randomGoRoundPatience` |
| F | Zu weit gegraben, Stufe ausgeschlagen | pro Spalte bzw. Batch, immer korrigiert, nur wo sicher | miner: `ChunkMinerBehavior`, `canDigByMistake` (STR-114) |
| — | Gemeinsamer Blunder-Schalter | Tests unterdrücken, ohne die Config anzufassen | `HumanBehavior.blunder`, `suppressBlunders` |
| G | Swing spiegeln, kein zusätzliches ABORT, Slot-Paket nur bei Wechsel | — | `BlockInteractor`, `MinecraftMixin` (STR-065) |
