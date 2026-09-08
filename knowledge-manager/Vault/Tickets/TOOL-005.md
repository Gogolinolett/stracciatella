---
id: "TOOL-005"
created: "2026-09-08 20:14:45"
tags:
- "gradle"
- "fabric"
- "accesswidener"
- "build"
---

# Problem

Task modules:pathfinding:splitWideners scheitert mit Cannot invoke String.length() because str is null. Betrifft auch gradlew build und jede Task die completeJar zieht, denn assemble haengt an completeJar. Kein Hinweis auf die betroffene Datei in der Fehlermeldung.

# Lösung

Ursache ist ein Bug im EXTERNEN Plugin net.stracciatella.gradle.plugin:stracciatella:0.3.1, ausgeloest durch Kommentar- und Leerzeilen in einem Modul-AccessWidener. Ablauf: GenerateFabricData schreibt beim Zusammenfuehren der Widener eine Meta-Zeile pro QUELLZEILE, also auch fuer # Kommentare und Leerzeilen. Danach remappt Loom den zusammengefuehrten Widener nach intermediary und STREICHT dabei Kommentare und Leerzeilen. SplitWideners liest anschliessend eine Widener-Zeile pro Meta-Zeile - die Meta-Datei ist laenger als der Widener, readLine liefert null, NPE. Beweisfuehrung ohne Rateraten: im remapped-Jar des Moduls beide erzeugten Dateien vergleichen, unzip -p <modul>-remapped.jar stracciatella-generated.accesswidener.meta gegen stracciatella-generated.accesswidener - bei pathfinding standen 6 Meta-Zeilen gegen Header plus 3 Widener-Zeilen (2 Kommentare + 1 Leerzeile zu viel). WORKAROUND im Repo: Kommentar- und Leerzeilen aus dem Modul-AccessWidener entfernen. Das accessWidener-v2-Format ERLAUBT Kommentare, der Fehler liegt also beim Plugin, nicht an der Datei - der richtige Fix waere GenerateFabricData Kommentar- und Leerzeilen ueberspringen zu lassen, das Plugin liegt aber in einem anderen Repository. FOLGE FUER DIE ZUKUNFT: solange das Plugin nicht gefixt ist, darf KEIN Modul-AccessWidener Kommentare oder Leerzeilen enthalten, sonst bricht der Build wieder - und zwar mit einer Meldung die nicht sagt welche Datei schuld ist. Betroffen war nur pathfinding.accesswidener, die uebrigen vier Widener im Repo sind kommentarfrei. Siehe auch [[STR-072]].
