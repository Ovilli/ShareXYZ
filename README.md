# ShareXYZ

ShareXYZ is a cooperative Fabric challenge for Minecraft 26.3. The group controls one shared
avatar: one player supplies its X movement, one supplies Y movement, and one supplies Z movement.

## Setup

Install Fabric Loader and Fabric API for Minecraft 26.3. Put ShareXYZ and Fabric API on the
server and every client. The first three eligible players to join are assigned X, Y, then Z.
Players beyond those three still see and share the avatar, but do not control an axis until an
operator assigns them.

## Controls

The movement keys are locked to **world axes**, not to the camera. Turning the camera never
changes what a key does, so no key can ever silently stop working.

| Axis | Keys | Effect |
| --- | --- | --- |
| X | `D` / `A` | move east (+X) / west (-X) |
| Z | `W` / `S` | move south (+Z) / north (-Z) |
| Y | `Space` | jump; gravity handles falling |

- Horizontal movement is always at full walking speed, or at sprint speed while anybody in the
  group holds the sprint key. It accelerates smoothly and coasts to a stop.
- The Y controller uses `Space` to jump from the ground. The shared avatar cannot fly or descend
  on command; gravity handles every fall.
- `Shift` keeps its normal sneak behavior and cannot dismount participants from the shared avatar.
- The axes are independent: the group can walk east while the Y controller jumps.

## How the synchronisation works

Modern Minecraft is client authoritative for the position of your own player: the server cannot
simply write a new position into your player entity, because your client owns it and would
overwrite the value on the next tick. That is the classic source of rubber-banding.

ShareXYZ avoids the conflict instead of fighting it:

- The shared body is a dedicated, invisible server-side entity that every participant rides.
- The server owns exactly one position - the position of that carrier entity - and moves it by
  integrating the three axis intents.
- Vanilla derives a passenger's position from its vehicle on both the client and the server,
  and the server ignores the position a passenger reports. The client therefore cannot
  contradict the server, and there is nothing left to rubber-band against.
- The server reads the raw, unfiltered key state of the three controllers
  (`ServerboundPlayerInputPacket`), so the camera rotation of a controller is irrelevant.

## Rules

- Every active non-spectator participant is kept at one position in one dimension. Entering
  another dimension as an axis controller moves the shared avatar and active group there.
- Health, hunger, saturation, air, and fire are shared among survival and adventure players.
  Creative players can control an axis and move with the group, but their needs and damage do
  not affect the shared survival state.
- Other player models are hidden, player targeting and player-on-player interactions are
  disabled, and overlapping player hitboxes do not push or block building.
- **If a survival or adventure participant dies, the whole group goes down and the challenge
  resets.** Other survival and adventure participants are taken down with the same cause. Creative
  participants are not taken down as part of the group death and return to spawn with the reset;
  spectators stay at their own positions. The active group resets carried items, experience,
  health, hunger, and effects, then waits until fallen participants respawn or disconnect.
- Restarting does not roll back blocks or other changes to the world. Use a disposable challenge
  world if each death should reset the map too.
- ShareXYZ participants can build in the vanilla spawn-protection area. Other server protections
  and permissions still apply.

## Falling

The Y controller can jump with `Space`, but cannot fly or control descent. Gravity acts normally
after the jump. On landing, each survival and adventure participant uses Minecraft's normal player
fall-damage handling, including the landing block's multiplier and that player's protections, so a
long fall can end the run. The server logs when damage is applied:

```
The shared avatar landed; vanilla fall damage was applied to the group.
```

## Technical notes

- The carrier entity is deliberately marked as not serialisable, so it is never written to the
  world save and never restored on a server restart. Because vanilla refuses to mount a
  non-serialisable entity, `EntityStartRidingMixin` bypasses that check for the carrier only.
- The carrier's chunk is held with a server region ticket that follows the carrier. Without it the
  server would unload the chunk - and the carrier with it - as soon as no participant is close
  enough to keep that chunk loaded, for example while the whole group waits for its respawns.
- The group death hook lives in `PlayerDeathRestartMixin`, which injects into `ServerPlayer.die`.
  That method replaces the whole superclass chain and never calls `LivingEntity.die`, so a mixin
  on the living entity would never see a player death.
- Mixins that only exist on the server (`PlayerDeathRestartMixin`, `SpawnProtectionMixin`) live in
  the `server` section of `sharexyz.mixins.json`; a client would crash while loading them.
- Gravity is applied to the carrier on every tick after its jump impulse. The vertical velocity is
  dropped after the move once the carrier rests on the ground. That keeps the ground
  contact stable and makes the fall detection exact.
- Spectators are never mounted onto the carrier, do not affect its dimension or movement, and are
  not teleported with the group.
- If the carrier is lost (it fell into the void, a command removed it), it is recreated where the
  participants are, so the group never stands still forever without a body.
- The group restarts at the world spawn, but on solid ground: the stored world spawn can be a few
  blocks above the terrain, and a carrier that is dropped there would fall and take the whole
  group down with it.
- While the group waits for its fallen members, the carrier may still settle on the ground, but
  that never causes fall damage - the fall was not the players' doing.

## Commands

- `/sharedxyz status` shows each axis controller and whether a restart is waiting for respawns.
- `/sharedxyz assign <x|y|z> <player>` lets an operator reassign an axis.

The compact HUD in the upper-left shows the three axis controllers, their keys, and the run status.
Your axis is highlighted; the accent switches to amber while the group is paused.
