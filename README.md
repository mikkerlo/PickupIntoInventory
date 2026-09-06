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
| `resyncAfterPickup` | `true` | Resend the inventory to the client after a redirected pickup, so the item shows up immediately. **Server-side.** |

`allowHotbarWhenInventoryFull=false` is a *pickup* policy: it only applies where refusing the slot
leaves the item somewhere it can still be picked up. It is not applied where the caller throws the
`addItemStackToInventory` result away — the number-key swap in a container GUI (`Container.slotClick`
mode 2), which has already overwritten the hotbar slot with the container item before handing over
the stack that was displaced from it, and `ItemPotion.onEaten` returning the empty bottle. Refusing
there would delete the stack outright, so those two calls keep the main-inventory preference but
always fall back to the hotbar slot vanilla had counted on. Every other vanilla caller checks the
result and keeps the item.

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

Requires JDK 17 and the five dependency jars. By default they are read out of a local GTNH
instance (`GTNH` = the `.minecraft` dir, `LIBS` = the launcher's `libraries` dir). If you do not
have one, `ci/fetch-deps.sh` downloads all five from their public homes — Mojang, Forge's Maven,
Maven Central, and the UniMixins/FalsePatternLib GitHub releases — and verifies each by SHA-1:

```sh
ci/fetch-deps.sh && source build/deps/env.sh && ./build.sh
```

The pinned hashes are the jars a GTNH `daily-2026-09-01` instance ships, so a CI build compiles
against byte-identical inputs to a local one. Remapping Minecraft and Forge is the slow part and is
cached in `build/tools`; pass `FORCE_REMAP=1` after changing `tools/Remap.java`.

## Continuous integration

GitHub Actions runs the build on every push and pull request. It checks that the mod compiles
against SRG-mapped Minecraft — which is what catches a mistyped `func_*` or a field that moved
between versions — but it cannot check that the mixin actually applies at load time, or that the
behaviour is right. Those still need the game.

## Releasing

`resources/mcmod.info` holds the version, and it is the only place it is written down: `build.sh`
names the jar from it and CI releases from it.

To ship a release, bump that version in an ordinary pull request. When the PR merges, CI sees a
version with no matching tag, creates `v<version>` on the merge commit, and publishes a release with
the jar attached. Merging a PR that did not touch the version does nothing, so most merges are
silent.

Nothing has to be tagged by hand: the tag is a consequence of the version rather than a second place
to keep in sync with it. Re-running a release is harmless, because the tag it created is exactly
what tells the next run the version is already out.

## Why the inventory needs resending

1.7.10 only guarantees that the *hotbar* reaches the client.
`NetHandlerPlayClient.handleSetSlot` applies a window-0 `S2FPacketSetSlot` to the player's own
container unconditionally for container slots 36-44 (the hotbar); for any other slot it applies the
packet only when the client's `openContainer.windowId` happens to match. `EntityPlayerMP.sendSlotContents`
drops slot packets entirely while `isChangingQuantityOnly` is set, which several pack mods toggle
around their own click handling, and `detectAndSendChanges` only ever runs on the open container.

Vanilla mostly gets away with that because an empty hotbar slot wins every pickup - the one path with
a hard guarantee. Steering pickups into slots 9-35 puts them on the fragile path, and a stale client
shows the item as missing until something rewrites the slots (pressing a sort button, for instance).

`handleWindowItems` has no such condition: a window-0 `S30PacketWindowItems` is always applied to
`inventoryContainer`. So after a redirected pickup the server marks the player dirty and resends that
packet at the end of the tick - at most one per player per tick, only on ticks where a pickup was
actually redirected. It is built by hand rather than via `EntityPlayerMP.sendContainerToPlayer`,
which would also push the cursor stack and could stomp on a drag in progress.
