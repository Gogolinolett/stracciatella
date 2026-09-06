---
id: "TOOL-003"
created: "2026-09-06 18:07:30"
tags:
- "mixin"
- "fabric"
- "gradle"
- "build"
---

# Problem

Ein neuer Mixin im bot-Modul wurde stillschweigend nie angewendet: die @Inject-Methode lief nie, es gab keinen Fehler und keinen Crash beim Start. Symptom war ein Feature, das ohne jede Meldung nichts tat (Schadens-Erkennung feuerte nie).

# Lösung

Ursache: modules/bot/build.gradle.kts hatte im stracciatella-Block KEIN mixin(bot.mixins.json). Die Datei bot.mixins.json existierte zwar im resources-Ordner, war aber nie in den Mod-Metadaten registriert - sie enthielt bisher leere mixins/client-Arrays, deshalb ist es nie jemandem aufgefallen. Fix: mixin(bot.mixins.json) in den stracciatella-Block, analog zu pathfinding und testing. Wichtig: required:true und injectors.defaultRequire:1 in der json schuetzen NICHT davor - diese Checks laufen erst, wenn die Config ueberhaupt geladen wird. Eine nicht registrierte Config schlaegt komplett lautlos fehl. Diagnose-Weg, der zum Ziel fuehrte: eine temporaere Log-Zeile GANZ OBEN in die Inject-Methode setzen (vor jede Bedingung) und den Test laufen lassen. Erscheint sie nicht, ist der Inject nicht erreicht - dann erst klaeren, ob es an der Registrierung, am Target oder an einem frueheren return liegt. Verworfene Hypothesen, die viel Zeit gekostet haetten: (a) das Paket erreicht den Client nicht - per javap widerlegt, ServerLevel.broadcastDamageEvent nutzt sendToTrackingPlayersAndSelf, der geschaedigte Spieler bekommt sein eigenes Paket; (b) getrennte Klassenlader zwischen Mixin und Modul-Klasse, so dass statische Felder auseinanderlaufen - widerlegt, ChatListenerMixin im testing-Modul greift genauso auf eine Modul-Singleton-Klasse zu und funktioniert. Checkliste bei neuen Mixins in einem Modul: 1. build.gradle.kts hat mixin-Eintrag; 2. Klasse steht im client- oder mixins-Array; 3. package in der json passt zum Java-Package.
