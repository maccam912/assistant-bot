# Assistant Bot — Fabric Minecraft Mod

A server-side Fabric mod that provides a personal assistant bot using FakePlayer.
The bot is driven by a tick-based state machine and supports task interrupts
(e.g., combat automatically saves/restores the previous task).

## Architecture

- **AssistantMod** — Entry point. Registers commands, tick handler, shutdown cleanup.
- **AssistantManager** — Singleton managing one bot per player (owner UUID → AssistantBot).
- **AssistantBot** — Core bot wrapping `FakePlayer`. Owns the state machine: ticks the
  current `BotTask`, detects combat interrupts via health-drop, saves/restores tasks.
- **BotTask** interface — Each task is a mini state machine with `tick()`, `onStart()`,
  `onStop()`, `getStatusString()`. Returns `TickResult` (CONTINUE / COMPLETE / FAILED).
- **Tasks**: IdleTask, FollowTask, MineTask, PlaceTask, DepositTask, CombatTask
- **Utilities**: NavigationHelper (movement), LookHelper (yaw/pitch), InventoryHelper
  (equip/deposit), BlockHelper (break/place)

## Build

```
./gradlew build
```

Output jar: `build/libs/assistant-bot-0.1.0.jar`

## Commands

| Command | Description |
|---------|-------------|
| `/assistant summon` | Spawn your personal bot |
| `/assistant dismiss` | Remove your bot |
| `/assistant follow` / `come` | Bot follows you |
| `/assistant stop` | Bot goes idle |
| `/assistant mine <x> <y> <z>` | Mine block at position |
| `/assistant place <block> <x> <y> <z>` | Place a block |
| `/assistant deposit` | Deposit inventory into nearest container |
| `/assistant status` | Show current task and position |

## Design Decisions

- **Tick-driven state machine** (every 5 game ticks / ~250ms) — simple, predictable,
  easy to pause/resume by swapping the task reference.
- **Interrupt-via-boxing** — Combat interrupt saves current task, restores it after.
  No task queue needed.
- **Server-side only** — All logic runs on the logical server. Works in singleplayer
  (which has an embedded server) and dedicated servers.
- **FakePlayer as actor** — Fabric API's FakePlayer provides a server-side
  ServerPlayerEntity we can drive programmatically. The "brain" is our task system.

## Tech Stack

- Minecraft 1.20.1
- Fabric Loader ≥0.15
- Fabric API (FakePlayer, commands, events)
- Java 17 target (compiles with 17–22)

## Inspiration

Architecture inspired by [third-principles-bot](../third-principles-bot/) — a Rust/azalea
Minecraft bot with a similar BotMode enum, tick loop, and interrupt-via-boxing pattern.
