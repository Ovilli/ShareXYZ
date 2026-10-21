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
| Y | `Space` / `Shift` | rise (+Y) / descend (-Y) |

- Horizontal movement is always at full walking speed, or at sprint speed while anybody in the
  group holds the sprint key. It accelerates smoothly and coasts to a stop.
- Releasing `Space` and `Shift` hands the vertical axis back to gravity: the shared avatar
  falls, and a long fall hurts the whole group.
- `Shift` is the descend key, so participants can never dismount from the shared avatar.
- The axes are independent: the group can walk east while it sinks into the ground.

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

- Every living player is kept at one position in one dimension. Entering another dimension as
  an axis controller moves the shared avatar and group there.
- Health, hunger, and breath are shared: damage, food use, or being underwater affects the whole
  group.
- Other player models are hidden, player targeting and player-on-player interactions are
  disabled, and overlapping player hitboxes do not push or block building.
- A death restarts the group at world spawn. Carried items, experience, health, hunger, and
  effects reset. The group waits there until fallen players respawn or disconnect.
- Restarting does not roll back blocks or other changes to the world. Use a disposable challenge
  world if each death should reset the map too.
- ShareXYZ participants can build in the vanilla spawn-protection area. Other server protections
  and permissions still apply.

## Commands

- `/sharedxyz status` shows each axis controller and whether a restart is waiting for respawns.
- `/sharedxyz assign <x|y|z> <player>` lets an operator reassign an axis.

The action bar also shows the three role assignments during play; `*` marks your axis.
