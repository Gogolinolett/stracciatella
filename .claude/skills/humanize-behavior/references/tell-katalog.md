# Tell-Katalog

Klassen von Tells, die schon aufgetreten sind, jeweils mit Beispielen und der Gegenmaßnahme, die
gewirkt hat. Die Buchstaben verweisen auf die Kategorien in `SKILL.md`.

| Tell-Klasse | Merkmal | Beispiele | Typische Gegenmaßnahme |
|---|---|---|---|
| **Rücksetzen zwischen Einheiten** | zwischen gleichartigen Schritten aufstehen, umdrehen, neu zielen oder anhalten | Hocke hoch und rund 315° Drehung pro Brückenblock (STR-074), Neuzielen pro Spalte (STR-113, STR-117), Reaktionspause im Korridor (STR-042) | Haltung halten, Folge erkennen, Naht fortsetzen (E) |
| **Liste statt Blick** | dreht sich für ein gleich nahes Ziel um, folgt einer festen Tour, zielt auf einen verdeckten Punkt | STR-116, Kisten-Tour im Restock (*Restocking* in `modules/bot/CLAUDE.md`), STR-034 | Abstand plus Drehkosten im Moment der Entscheidung, sichtbarer Zielpunkt (D) |
| **Nacheinander statt gleichzeitig** | erst fertig, dann drehen, dann Werkzeug, dann zielen | LOOKING 3,5 statt 1,5 Ticks pro Block ohne Vorzielen (STR-068) | vorziehen und überlappen (E) |
| **Zu früh oder zu spät** | Haltung beginnt, sobald sie geschuldet ist, statt wenn sie gebraucht wird, oder endet an der Aufgabengrenze statt an der Lage | Hocke 4 Blöcke vor der Kante, Hocke nach jedem Block hoch (STR-074) | „geschuldet“ und „jetzt“ trennen, Ende an einer Zustandsbedingung (E) |
| **Metronom** | gleiche Dauer, gleicher Abstand, Pause bei jeder Wiederholung | gleichverteilte Pausen von 12–25 Ticks (STR-036), Sprünge im Takt (STR-111) | log-normale Längen, seltene Ereignisse, 0–3 Ticks Versatz (B) |
| **Perfektion** | exakte Linie, Blockmitte, kürzester Weg, nie ein Fehler | STR-010, STR-111, STR-120, STR-114 | Drift oder Spur, Abstand zur Wand, Blunder (C, D, F) |
| **Erstarrung** | eingefrorener Blick in Pausen, fester Pitch | STR-025, STR-036, STR-011 | Kamera läuft weiter auf das letzte Ziel, Mikrosakkaden, Seitenblick (B, C) |
| **Unmotivierte Bewegung** | Umdrehen zum Bremsen, zufälliger Yaw beim Graben nach unten | STR-008, STR-077 | Bewegung weglassen, Sonderfall für entartete Geometrie (C) |
| **Übereifer** | Reaktion, Sprung oder Klick sofort | STR-042, STR-012, STR-041 | kurzer Takt an *sichtbaren* Entscheidungspunkten (B) |
| **Falsche Menschlichkeit** | ein „Fehler“, den niemand macht, oder eine Pause mitten in einer Handlung | „eins zu tief“ graben (STR-114, entfernt), Pausen im Korridor (STR-042) | Geschichte prüfen, entfernen |
| **Leitung** | Pakete, die ein Spieler nicht sendet | kein Swing, ein ABORT pro Block, ein Slot-Paket pro Block (STR-065) | über Vanilla-Pfade treiben, Pakete inventarisieren (G) |
