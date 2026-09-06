# Pathfinding Module — Design Decisions (archiviert)

> **Diese Datei ist ein Archiv-Stub.** Die Design-Entscheidungen dieses Moduls
> liegen jetzt als Tickets in der Wissensdatenbank
> (`knowledge-manager/Vault`), nicht mehr hier. Der frühere Volltext bleibt in
> der Git-Historie.

Nicht in dieser Datei nachlesen, sondern suchen — vom Repo-Root:

```powershell
.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"
```

Tickets dieses Moduls: **STR-002 bis STR-015** — A* (Heuristik-Skalierung,
Open-Set, MeshNode-Equality, Diagonal-Limits), Mesh-Invalidierung,
Ankunftslogik und Landing-Brake am Zielknoten, Yaw-Stabilisierung im Nahfeld,
die Humanness-Effekte beim Gehen (Micro-Strafing, Pitch-Varianz,
Pre-Jump-Hesitation) sowie EnderPearl-Cooldown und Wurf-Fenster.
