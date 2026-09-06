# Camera Module — Design Decisions (archiviert)

> **Diese Datei ist ein Archiv-Stub.** Die Design-Entscheidungen dieses Moduls
> liegen jetzt als Tickets in der Wissensdatenbank
> (`knowledge-manager/Vault`), nicht mehr hier. Der frühere Volltext bleibt in
> der Git-Historie.

Nicht in dieser Datei nachlesen, sondern suchen — vom Repo-Root:

```powershell
.\knowledge-manager\gradlew.bat -p knowledge-manager run --quiet --args="search 'Suchbegriffe' 3"
```

Tickets dieses Moduls: **STR-016 bis STR-023** — zweistufige API
(`aimAt`/`isAimedAt` vs. `updateYaw`/`updatePitch`), hartkodierte
Spring-Konstanten, instanzbasierter Controller, Toleranz als Parameter,
Micro-Saccades, Look-Speed-Multiplier, symmetrisches Spring-Damper-Pitch und
der Yaw-Geschwindigkeits-Cap.
