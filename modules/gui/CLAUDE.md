# Gui Module

Shared in-game UI framework. It owns the key binding, the `/gui` command and a
root menu; every actual page comes from another module, which registers it at
init.

## Architecture

```
net.stracciatella.gui
├── GuiModule.java              # Entry point (trivial, see the class doc)
├── GuiSetup.java               # Wiring: key binding, tick hook, deferred open
├── GuiPage.java                # The interface a feature module implements
├── GuiRegistry.java            # Static, insertion-ordered page registry
├── GuiCommands.java            # /gui and /gui <page>
├── screen/
│   └── GuiRootScreen.java      # Menu listing every registered page
└── test/
    └── GuiTests.java           # In-game smoke tests
```

The module knows nothing about the features it displays. A feature module
implements `GuiPage` (id, title, `createScreen(parent)`) and calls
`GuiRegistry.register(page)` from **its own setup class** — never from its
module main class, which is verified before other modules' class loaders are
guaranteed to be present.

## Opening the GUI

| Way in | Notes |
|--------|-------|
| Right shift | `key.stracciatella.gui`, category `stracciatella.main`, rebindable in Controls. Right shift is unbound in vanilla. |
| `/gui` | Root menu. |
| `/gui <page>` | Opens that page directly, with tab completion over registered ids. Closing returns to the game, not to the root menu — the player never passed through it. |

A command runs while the chat screen is still open, and the chat screen closes
itself with `setScreen(null)` right afterwards, so a screen opened inside the
command would be discarded. `GuiSetup.requestOpen` therefore parks the screen
and the existing `END_CLIENT_TICK` hook opens it on the next tick.

`GuiRootScreen.isPauseScreen()` returns **false**: in singleplayer a pausing
screen freezes the world, and this menu exists to watch and steer a bot that
is supposed to keep working while it is open. Pages should do the same.

## Widgets

Plain vanilla `Screen`/`Button`, no config-screen library. The mod ships no
third-party UI dependency and every page so far is a handful of buttons and
lists that vanilla widgets cover outright. `GuiPage` hands back a `Screen`
rather than describing its contents in a widget-description language — a
description language would need a new case for every control a future page
wants, while a `Screen` can already do anything vanilla can.

There is no automatic binder from config fields to widgets in this version.

## Dependencies

- `loader` (compileOnly) — Module interface
- `testing` (compileOnly) — TestRunner, test annotations

No dependency on any feature module; the arrow always points the other way.

## Testing

`./gradlew runMinecraftTests -Psuites=gui`. Two smoke tests, both driving the
real command → deferred-open path because that is the part that fails
silently if it regresses:

- **Gui command opens root menu** — `/gui` leaves a `GuiRootScreen` on screen.
- **Gui page reachable by id** — a probe page registered at runtime is found
  by `GuiRegistry` and `/gui gui_probe` opens its screen.
