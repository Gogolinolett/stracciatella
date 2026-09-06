# Bot Module — Design Decisions (archiviert)

> **Diese Datei ist ein Archiv-Stub.** Die Design-Entscheidungen dieses Moduls
> liegen jetzt als Tickets in der Wissensdatenbank
> (`knowledge-manager/Vault`), nicht mehr hier. Der frühere Volltext bleibt in
> der Git-Historie.

Nicht in dieser Datei nachlesen, sondern suchen — vom Repo-Root:

```powershell
.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"
```

Tickets dieses Moduls: **STR-024 bis STR-046** — Behavior-Layer über der
Task-Queue, COLLECTING (Exit-Gates, Gaze, Item-Auswahl), LOOKING/INTERACTING
(Hit-Result-Gate, Face-Auswahl, Break-Bestätigung), Task-Routing und
Standoff-Wahl, Inventar/Tool-Auswahl sowie das Humanness-Timing
(Reaktions-Delays, Pre-Attack-Hesitation, lange Pausen).
