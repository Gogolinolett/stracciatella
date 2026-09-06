---
id: "TOOL-002"
created: "2026-09-06 17:19:36"
tags:
- "testing"
- "gradle"
- "flaky"
- "minecraft"
---

# Problem

A bot mining test failed after a code change at -PtickSpeed=20 - is that a regression?

# Lösung

Not by itself. The bot mining tests are marginal at 20x and flake independently of any code change. Evidence from three runs on 2026-09-06: WITH the placement change, Bot chop tree failed (Block break timeout, 0 of 4 logs); in a baseline run with the SAME changes git-stashed away, Bot mine ore vein [2/5] failed instead - same failure class (bot mining test timing out), different test. At -PtickSpeed=10, the documented default, all 21 Bot Tests passed including both. Every historical green run in run/logs was also 10x, recognisable by roughly 5m35s total suite time against roughly 3m02s at 20x - so 20x had effectively never been exercised before. Method to use before debugging a suspected regression: (1) git stash push -u, (2) re-run at the SAME tickSpeed to get a baseline, (3) cross-check at 10x. Two traps while measuring. First, do not start a second test run while the first is still going: the second gradle invocation exits 1 without ever launching a client, and its build load skews the run that is still in flight - wait for the actual completion, not just for the log line you were watching. Second, do not use exit code alone as the pass signal; a run with failures can still report gradle exit 0, so read the TEST RESULTS block in run/logs/latest.log. Also known-flaky and NOT caused by changes: PathWalker Tests > Corner 2 to 1, failing as Player fell at BlockPos{x=619, y=27, z=497} - the identical coordinates appear in archived runs predating the change. This matches the STR-044 lesson: raising timing constants masks load-sensitivity instead of fixing it, so treat a lone 20x failure as a measurement to repeat, not a bug to chase. See also [[STR-044]].

## Update 2026-09-06 18:09:19

NACHTRAG - Testlauf-Umfang waehrend der Entwicklung. Vom Nutzer vorgegeben und in CLAUDE.md verankert: waehrend der Arbeit NUR die Suites der geaenderten Module laufen lassen, den vollstaendigen Lauf erst einmal am Ende zur Regressionspruefung. Dafuer gibt es jetzt einen Suite-Filter: ./gradlew runMinecraftTests -Psuites=bot,miner - kommaseparierte, gross-klein-egale Teilstrings des Suite-Namens, leer bedeutet alles. Durchgereicht als -Dstracciatella.testing.suites und angewendet in TestRunner.runAll. Teilstrings statt exakter Namen, damit -Psuites=bot die Suite Bot Tests findet, ohne dass jemand den genauen Titel im Kopf haben muss. Gemessener Effekt: Bot-Suite allein rund 1 Minute gegen rund 6 Minuten fuer den Komplettlauf - das macht die Evidenz-Schleife (Debug-Log einbauen, laufen lassen, Log lesen) ueberhaupt erst praktikabel. Zweiter Grund neben der Zeit: der Komplettlauf mischt Suites bei, die mit der Aenderung nichts zu tun haben, und deren bekannte Flakiness sieht dann wie eine Regression aus. Konkretes Beispiel aus diesem Lauf: Bot mine ore vein [1/5] lief einmal in ein Timeout von 14058 ms und war im naechsten Lauf ohne jede Codeaenderung bei 3 Sekunden gruen - Ursache war der COLLECTING-Stall aus [[STR-026]], nicht die Aenderung.
