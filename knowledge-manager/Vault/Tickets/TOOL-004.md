---
id: "TOOL-004"
created: "2026-09-07 09:29:33"
tags:
- "gradle"
- "fabric"
- "loom"
- "windows"
---

# Problem

gradlew runClient oeffnete zwoelf Minecraft-Fenster statt einem. Elf davon starben sofort mit Fabric-Loader-Fehlerdialogen: ClassTweakerFormatException Namespace (intermediary) does not match current runtime namespace (named), und NoClassDefFoundError org/openjdk/jol/datamodel/DataModel mit dem Hinweis fabric.classPathGroups may not be set correctly in-dev. Verwirrend: run/logs/latest.log zeigte trotzdem einen voellig sauberen Start bis STARTED.

# Lösung

Ursache: JEDES Projekt, das fabric-loom anwendet, bekommt von Loom eine eigene runClient-Task - Root, loader, loader:test3module und alle neun Module. Ein unqualifizierter Task-Name laesst Gradle die Task in ALLEN Projekten laufen, also zwoelf Clients gleichzeitig. Nur die Root-Task ist ein konfigurierter Client mit gemergtem Loader-Jar, remapptem ClassTweaker und jol-core auf dem Spiel-Classpath; die elf Modul-Tasks haben nichts davon und sterben in der Loader-Init. FIX: immer mit fuehrendem Doppelpunkt aufrufen, also gradlew :runClient. WARUM DAS LOG TAEUSCHT: die elf kaputten JVMs crashen VOR dem Anlegen des Logs, schreiben also nie nach run/logs. Der eine gesunde Root-Client schreibt latest.log ganz normal - wer nur ins Log schaut, sieht einen perfekten Start und haelt die Fehlerdialoge fuer ein anderes Problem. Genau diese Falle hat hier zu einer falschen Diagnose gefuehrt (kein Fehler reproduzierbar, obwohl der Nutzer zehn Crashdialoge sah). BEWEISFUEHRUNG ohne irgendetwas zu starten: gradlew runClient --dry-run und dann nach :runClient filtern - die Liste zeigt sofort alle zwoelf Tasks. Dasselbe Vorgehen fuer jede Task, bei der unklar ist, ob sie faechert. NICHT betroffen: runMinecraftTests existiert nur in :modules:testing, faechert also nicht und startet genau einen Client. Ebenso build oder checkstyleMain sind gewollt projektuebergreifend. Nebenwirkung fuer die Zukunft: jedes neu angelegte Modul erhoeht die Zahl der Fehlerfenster um eins - das gui-Modul machte aus elf zwoelf. Root CLAUDE.md wurde korrigiert: Build und Run nennt jetzt gradlew :runClient plus eine Erklaerung der Faecherung. Naheliegende, aber verworfene Alternative: die runClient-Tasks in den Subprojekten per allprojects deaktivieren - waere ein Eingriff in den Build mit Ripple auf jeden, der bewusst ein einzelnes Modul startet, und die Dokumentation loest das Problem vollstaendig. Siehe auch [[TOOL-002]] und [[TOOL-003]].

## Update 2026-09-07 10:04:03

KORREKTUR der oben genannten verworfenen Alternative: der Nutzer hat entschieden, dass Dokumentation nicht reicht - runClient soll technisch nur EINEN Client starten. Umgesetzt im Root-build.gradle.kts: im bestehenden allprojects-Block wird auf jeder RunGameTask enabled = isRootProject gesetzt (isRootProject wird einmal pro Projekt als this == rootProject bestimmt). Damit sind runClient, runServer und runClientRenderDoc in allen Subprojekten dauerhaft abgeschaltet, waehrend die Root-Runkonfigurationen unveraendert bleiben. Wichtig fuer die Pruefung: --dry-run taugt NICHT als Nachweis, weil dort ohnehin jede Task als SKIPPED erscheint, unabhaengig von enabled. Belastbarer Nachweis ist das Zaehlen der laufenden JVMs waehrend eines echten Laufs, unter Windows per Get-CimInstance Win32_Process gefiltert auf Knot oder devlaunch in der CommandLine: vorher zwoelf Prozesse, nachher genau einer. Nicht betroffen und weiterhin funktionsfaehig: runStracciatella und runStracciatellaLight existieren ausschliesslich im Root-Projekt, faechern also nie, und runMinecraftTests haengt per :runStracciatellaLight an einer Root-Task - die Testlaeufe starten daher ohnehin immer genau einen Client. Root CLAUDE.md wurde entsprechend nachgezogen: der Befehl heisst wieder schlicht gradlew runClient, und der Abschnitt warnt ausdruecklich davor, die Subprojekt-Tasks wieder zu aktivieren, um ein einzelnes Modul zu starten.

