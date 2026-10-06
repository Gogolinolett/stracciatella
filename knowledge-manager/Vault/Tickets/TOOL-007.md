---
id: "TOOL-007"
created: "2026-09-11 10:26:01"
tags:
- "testing"
- "konfiguration"
- "gradle"
- "logs"
---

# Problem

Die In-Game-Testsuite liefert ploetzlich 16 statt 3 Fehlschlaege, ohne dass am Code etwas Passendes geaendert wurde: zehn Tests laufen in einen Timeout von 1200 Ticks, drei weitere melden Left COLLECTING without picking up the drop und Bot never stepped down into the dip. Sieht nach einer schweren Regression in der Sammelphase aus. Nach welchen Variablen muss man suchen, die NICHT im Quellcode stehen?

# Lösung

URSACHE: runMinecraftTests laeuft gegen die LIVE-Konfiguration des Spielers in run/stracciatella/bot.json, nicht gegen Vorgabewerte. Wer im Spiel /bot ignore add minecraft:cobblestone tippt, aendert damit das Ergebnis der Testsuite. Genau das war es: ignoredItems enthielt cobblestone und dirt, und dreizehn Tests versichern, dass der Bot den abgebauten Cobblestone EINSAMMELT - die Konfiguration verbietet exakt das. BEWEIS durch Gegenprobe in einer Sitzung, nur diese eine Zeile geaendert: mit der Liste 74 Tests / 16 Fehler, mit leerer Liste 74 / 4. Danach Liste wiederhergestellt. VORGEHEN BEI UNERKLAERLICHEN SUITE-ERGEBNISSEN: erst die Laufzeit-Konfiguration unter run/stracciatella (bot.json, miner.json, servers.json, pathwalker.json) gegen die Vorgaben vergleichen und notfalls beiseitelegen, DANN den Code verdaechtigen. Ein Komplettlauf ist ausserdem nicht mit einem -Psuites-Teillauf vergleichbar: dieselben Tests, dasselbe Binary, anderes Lastprofil - Chunk miner clears a corridor, Bot collects from a dip und Bot walks until the face it aims at is in sight kippten im Teillauf hin und her und bestanden im Komplettlauf alle drei. Nur der Komplettlauf ist gegen einen frueheren Komplettlauf lesbar. ZWEITER FALLSTRICK derselben Art: die Logdateien rotieren bei JEDEM Start, run/logs/2026-09-11-3.log.gz zeigt nach zwei weiteren Laeufen auf einen anderen Inhalt als vorher. Archive immer ueber den Zeitstempel der ersten Zeile identifizieren, nie ueber die Nummer. NICHT BEHOBEN, bewusst benannt: dass die Suite die Spielerkonfiguration liest, ist die eigentliche Schwachstelle - ein Ergebnis, das davon abhaengt, was zuletzt im Spiel getippt wurde, kann eine Regression nicht von einer Einstellung unterscheiden. Die Tests legen servers.json schon einzeln beiseite und stellen es im finally wieder her; dasselbe fehlt fuer bot.json. Siehe [[STR-100]] und [[STR-082]].

## Update 2026-09-11 16:47:45

NACHTRAG 2026-09-11 - dieselbe Live-Konfiguration ist auch von der anderen Seite gefaehrlich: ein Test, der ignoredItems im Speicher ergaenzt, darf den Eintrag im finally nur entfernen, wenn er vorher NICHT drinstand. Beim Nutzer steht cobblestone auf der Liste, das add von Bot ignores a listed drop war also ein No-op, das remove aber nicht - und Bot ignore commands edit the list SPEICHERT bot.json spaeter im selben Lauf, haette die Kuerzung also festgeschrieben. Fix im Test: alreadyIgnored vorher abfragen und das remove daran haengen. Praktischer Ablauf beim Arbeiten an Bot-Tests: die echte bot.json vor dem Lauf in den Scratchpad kopieren, ignoredItems fuer den Lauf leeren, danach die Kopie zurueckspielen - ein Lauf mit der Nutzerliste meldet 16 statt 4 Fehlschlaege und ist als Regressionsurteil wertlos. Zweiter Nebeneffekt derselben Kopplung, nuetzlich zur Falsifikation: wird isIgnoredDrop auf konstant false gesetzt, verliert die Nutzerliste jede Wirkung und die 13 sonst betroffenen Tests laufen gruen durch - ein Falsifikationslauf braucht die Datei also gar nicht erst zu leeren.

## Update 2026-10-06 13:42:29

WIEDERHOLT 2026-10-06: nach dem Wiederherstellen der Spieler-bot.json (ignoredItems dirt und cobblestone) liefen Chunk miner mines on with drops lying about und clears a corridor in den 1200-Tick-Timeout - der Slab-Sweep (sweepDrops) verliess COLLECTING im ersten Tick, weil ignorierte Cobblestone nicht als nearby zaehlt, und die Tests warten auf Cobblestone im Inventar. Sah zuerst wie eine Regression der gerade gebauten Reihen-Logik aus ([[STR-117]]), belegt per Temp-Log am COLLECTING-Ausgang (items=cobblestone liegen noch, ticks=1). REGEL: vor jedem Testlauf ignoredItems in run/stracciatella/bot.json leeren (Sicherung anlegen), danach zurueckspielen - und bei ploetzlichen Sammel-Timeouts zuerst dort nachsehen.
