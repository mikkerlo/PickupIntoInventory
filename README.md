# Pickup Into Inventory (Minecraft 1.7.10 / GTNH)

Picked-up items fill the **main inventory** before they fill empty **hotbar** slots.

## What it changes

Vanilla puts a picked-up item in the first empty slot of `InventoryPlayer.mainInventory`, and the
hotbar *is* slots 0-8 of that array — so an empty hotbar slot always wins. This mod redirects the
three `getFirstEmptyStack()` call sites on the pickup path so slots 9-35 are searched first.

Affected:

* items picked up off the ground
* mod code that calls `addItemStackToInventory` (magnets, quest rewards, machine output to player)

**Not** affected, by design:

* items stacking onto a *matching partial stack* — those still merge wherever the stack already is,
  hotbar included. This is the one case that can look like the mod isn't working.
* shift-clicking out of a chest or crafting GUI (that is `Container.mergeItemStack`, a different path)
* creative pick-block

## Config

`Mods` → `Pickup Into Inventory` → `Config`, or `config/pickupintoinventory.cfg`.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Your preference. Sent to the server as your personal setting; on the server itself, the default for players who have not chosen. |
| `allowHotbarWhenInventoryFull` | `true` | When the main inventory is full, still use an empty hotbar slot. `false` leaves the item on the ground. **Server-side.** |
| `allowPlayerOverride` | `true` | Let players choose for themselves. `false` forces `enabled` on everyone. **Server-side.** |

GUI edits apply immediately — no restart.

## Per-player settings on a server

The decision is made server-side, so the server needs the jar. But it is made **per player**, and
there are two ways to set it:

* **Keybind** — `Options` → `Controls`, category *Pickup Into Inventory*. Unbound by default
  (GTNH has hundreds of binds; any default would collide). This is the only in-game toggle: the
  Mods config screen is reachable from the title screen only.
* **Config GUI** — title screen → `Mods (…)` → `Pickup Into Inventory` → `Config`. Sends your
  choice to the server, on connect and on every change.
* **`/pickupinv [on|off|server]`** — works from any client, including one without the mod, and
  needs no op status. `server` clears your choice and follows the server default.

Choices are keyed by player UUID (profile-derived, so stable) and persisted in
`config/pickupintoinventory_players.properties` on the server. An admin can set
`allowPlayerOverride=false` to take the choice away and pin everyone to the server's `enabled`.

The client-to-server message rides a `PickupIntoInv` channel; if the server does not have the mod
the payload is simply dropped there, so a client-only install stays harmless.

## Sides

The routing runs on the **logical server**. Singleplayer works with a client-only install (the
integrated server is the same JVM). On a dedicated server the jar must be installed **server-side**.
`acceptableRemoteVersions="*"` so it never blocks a connection.

## Building

`build.sh` compiles against SRG-named copies of Minecraft and Forge, remapped on the fly from the
notch→SRG CSVs that FalsePatternLib ships, so no MCP/Gradle toolchain is needed. The mixin's
`@Redirect` targets are written as SRG names directly, so no refmap is generated or required
(annotation processing is off — the Mixin AP would otherwise demand a mapping file).

Requires: JDK 17, a GTNH instance (for `falsepatternlib` and `unimixins`), and the Prism/Forge
`libraries` dir.
