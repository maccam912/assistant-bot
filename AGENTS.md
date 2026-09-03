# Assistant Bot — Fabric Minecraft Mod

A server-side Fabric mod that provides a personal assistant bot using a visible server-side player.
The bot is driven by a tick-based state machine and supports task interrupts
(e.g., combat automatically saves/restores the previous task).

## Architecture

- **AssistantMod** — Entry point. Registers commands, tick handler, shutdown cleanup.
- **AssistantManager** — Singleton managing one bot per player (owner UUID → AssistantBot).
- **AssistantBot** — Core bot wrapping `BotPlayer`. Owns the state machine: ticks the
  current `BotTask`, detects combat interrupts via health-drop, saves/restores tasks.
- **BotTask** interface — Each task is a mini state machine with `tick()`, `onStart()`,
  `onStop()`, `getStatusString()`. Returns `TickResult` (CONTINUE / COMPLETE / FAILED).
- **Tasks**: IdleTask, FollowTask, MineTask, PlaceTask, DepositTask, CombatTask, PlanTask, BuildTask
- **Utilities**: NavigationHelper (movement), LookHelper (yaw/pitch), InventoryHelper
  (equip/deposit), BlockHelper (break/place)

## Build

```
./gradlew build
```

Output jar: `build/libs/assistant-bot-<version>.jar`

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
| `/assistant plan <description>` | Generate a build plan from LLM, returns plan ID |
| `/assistant execute <id>` | Execute a stored plan at bot's current position |
| `/assistant speed [blocks-per-second]` | Show or set build speed (minimum 0.25, no upper limit; default 20); active builds update immediately |
| `/assistant plans` | List all available build plans |
| `/assistant build <description>` | Plan + auto-execute (convenience shortcut) |
| `/assistant import <url> <description>` | Import a VXB-2 plan from a URL |
| `/assistant status` | Show current task and position |
| `/assistant menu` | Open the click-to-control bot menu (chest GUI) |
| `/assistant remote` | Get a "Bot Remote" item — right-click it to open the menu |

## GUI (server-side, no client mod)

A point-and-click menu (`com.assistantbot.gui`) for non-CLI users. A vanilla compass
marked via `custom_data` ("Bot Remote") opens a `ChestMenu` on
right-click (`UseItemCallback`); clicks are caught in `clicked` and dispatched to
`BotActions` (shared with the commands). Build text is captured through an
`AnvilMenu` rename box. The remote is auto-given on `summon`.

## Design Decisions

- **Tick-driven state machine** (every 5 game ticks / ~250ms) — simple, predictable,
  easy to pause/resume by swapping the task reference.
- **Interrupt-via-boxing** — Combat interrupt saves current task, restores it after.
  No task queue needed.
- **Server-side only** — All logic runs on the logical server. Works in singleplayer
  (which has an embedded server) and dedicated servers.
- **ServerPlayer-style actor** — `BotPlayer` extends Minecraft's `ServerPlayer`
  so it can be driven programmatically while still appearing to nearby clients.
  The "brain" is our task system.

## Tech Stack

- Minecraft 26.2
- Fabric Loader ≥0.19.3
- Fabric API (commands, lifecycle/player events)
- Java 25

## Inspiration

Architecture inspired by [third-principles-bot](../third-principles-bot/) — a Rust/azalea
Minecraft bot with a similar BotMode enum, tick loop, and interrupt-via-boxing pattern.

### Important Notes
- Always use the internet for find examples or docs instead of decompiling or digging into library code locally. It's better to see how it's supposed to be done or how it has been done first. Ask the user before digging in to the source code of dependencies or decompiling stuff.
