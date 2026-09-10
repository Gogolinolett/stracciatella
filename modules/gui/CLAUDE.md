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
│   ├── GuiRootScreen.java      # Menu listing every registered page
│   ├── IdListScreen.java       # Shared editor for a list of registry ids
│   └── SettingsScreen.java     # Shared page of click-to-cycle settings
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

## Shared screens

`IdListScreen` is the one screen this module offers to pages: a paged list
of registry ids with a remove button per row, a text box with validation,
and one context button that adds whatever the game already has in front of
the player. It exists because two feature modules edit the same kind of
list — block ids the chunk miner leaves standing, item ids the bot does not
walk to — and their screens were the same code apart from the wording and
two lookups. A page hands it an `IdListScreen.Spec`: the live list (edited
in place, so the owning config sees every change), a normaliser that turns
typed text into a canonical id or null, the wording (title, what a rejected
entry "is not", the empty-list hint, the input placeholder), a save
callback, and the context button's label, id supplier and missing-message.
The module still learns nothing about the features: the spec is data, the
lookups are the page's.

`SettingsScreen` is the companion for everything that is not a list: a page of
rows, each one a label, a tooltip, something that reads the current value and
something that moves it on. There is **one** row type and no generics, which is
the whole design — a boolean is two values, an enum is however many it has, and
a numeric knob is a handful of sensible steps, so the screen needs no case for
any of them. The obvious alternative (`Toggle`, `Choice<E>`, `Slider` row types)
was rejected: it would put this module in the business of knowing what a value
*is*, which is the owning module's knowledge, and every new kind of setting
would add a case here. The owner therefore supplies the cycling — one lambda per
row — and `advance` runs it, calls the page's `save`, writes a status line and
**rebuilds the widgets**. That last step is not optional: a button's message is
built with the value baked in, so without it the setting changes and the row
goes on showing the old value, which reads as a broken menu. Rows past seven go
onto further pages; page controls only appear when there is more than one.

Like the root menu neither of them is a pause screen — a list is edited and a
setting is flipped while a bot is working.

## Dependencies

- `loader` (compileOnly) — Module interface
- `testing` (compileOnly) — TestRunner, test annotations

No dependency on any feature module; the arrow always points the other way.

## Testing

`./gradlew runMinecraftTests -Psuites=gui`. Two smoke tests driving the
real command → deferred-open path because that is the part that fails
silently if it regresses, and one test per shared screen:

- **Gui command opens root menu** — `/gui` leaves a `GuiRootScreen` on screen.
- **Gui page reachable by id** — a probe page registered at runtime is found
  by `GuiRegistry` and `/gui gui_probe` opens its screen.
- **Id list screen edits its list** — `IdListScreen` on a stand-in `Spec`,
  driven through its widgets (`EditBox.setValue`, `Button.onPress` with a
  `MouseButtonInfo`): an accepted id lands in the list and is saved at once, a
  rejected one and a duplicate leave list and save count alone, the context
  button and a row's Remove go through the same path, and Done returns to the
  parent. The stand-in normalize has to be idempotent — the context button's
  id goes through it like typed text does.
- **Settings screen cycles and pages** — `SettingsScreen` on nine probe rows, so
  the paging controls exist: a click advances that row's value, saves once and
  **relabels the button** (the next click has to find the label the last one
  wrote, which is what fails if the rebuild goes), page two binds its buttons to
  the rows it shows rather than to the first seven, and Done hands back to the
  parent screen rather than to the game.
