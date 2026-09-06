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
| `resyncAfterPickup` | `true` | Resend the main-inventory slots a pickup changed, so the item shows up immediately. **Server-side.** |

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
packet only when the client's `openContainer.windowId` matches - and, on window 0, only when the
client is not sitting on a non-inventory creative tab, which is a client-only state the server
cannot see. `EntityPlayerMP.sendSlotContents` drops slot packets entirely while
`isChangingQuantityOnly` is set, which vanilla does around every accepted click and which several
pack mods toggle around their own click handling, and `detectAndSendChanges` only ever runs on the
open container.

Vanilla mostly gets away with that because an empty hotbar slot wins every pickup - the one path with
a hard guarantee. Steering pickups into slots 9-35 puts them on the fragile path, and a stale client
shows the item as missing until something rewrites the slots (pressing a sort button, for instance).

So the server repairs those slots itself. Which slots a pickup changed is worked out by snapshotting
main inventory indices 9-35 around `addItemStackToInventory` and comparing afterwards, not by asking
the redirect: a pickup that *merges* into a partial stack goes through `storeItemStack` and never
calls `getFirstEmptyStack`, so it would otherwise leave no trace to resend. Each changed slot is sent
as a single `S2FPacketSetSlot` addressed through the container the player actually has open, whose
window id the client is guaranteed to match; where `handleSetSlot` would drop it - a creative player,
who may be on a tab that gates window-0 updates, or an open container that does not show the player's
inventory - the fallback is a window-0 `S30PacketWindowItems` truncated after the last slot that
needs it, which `handleWindowItems` always applies. A prefix cannot start anywhere but zero, so that
one also rewrites the crafting slots and the armour; a prediction overwritten there takes a click to
make, and a click is what makes the confirmation below send the prefix again. The cursor is never
sent, so a drag in progress is safe.

## Why the repair is confirmed afterwards

A repair packet describes the server's state at the moment it was built, and the client may have
moved on: it predicts every click locally and only later learns whether the server agreed. Writing a
slot the client has already emptied by prediction leaves a **ghost stack** - shown on the client,
empty on the server - and nothing repairs it, because a click the server *accepts* is acknowledged
with `isChangingQuantityOnly` set, which suppresses the corrective slot packets and advances the
container's cached contents so no later tick sees a difference either. Sending only the changed
slots removes that race for unrelated slots but not for the repaired ones, and the server cannot
rule it out when it builds the packet: the conflicting click may still be in transit.

So the repair is checked after the fact. Immediately behind it goes an `S32PacketConfirmTransaction`
with `accepted=false`, which the 1.7.10 client answers with a `C0FPacketConfirmTransaction` and
otherwise ignores. Ids are shorts that both sides count up, so one of ours can name a transaction
vanilla rejected itself, and vanilla stops accepting clicks until such a rejection is answered —
answering it early would let through exactly the clicks it means to drop. Ids already outstanding
are skipped when one is picked, which leaves only the reverse order, and there our packet went out
first: the first echo of the id is ours and is taken out of the stream, the second is vanilla's and
passes through. Both directions of the connection are ordered and neither packet is handled off the
server
thread, so any click sent before the client applied the repair has already been counted when that
answer arrives. If nothing was counted, the repaired slots are known good and the marks are dropped;
otherwise they stay, and the repair goes out again on the next tick the player is not clicking.
Clicks aimed at a slot that really was stale are rejected by vanilla anyway - `processClickWindow`
compares the client's pre-click view of the slot against the server's - and a rejection resends the
whole container, so the two mechanisms cover each other. A round that is never answered is abandoned
after five seconds rather than pinning the marks forever.
