# TypeSafe think mode

`/assistant think` gives the bot a reactive brain using TypeSafe's System One API.
It starts with a **follow** goal. Owner chat can change its goal, and it keeps that
goal until instructed otherwise, the work is complete/released, or think mode ends.

## Setup

Put your key in the server's environment or `.env` in its working directory
(`run/.env` for local development):

```dotenv
TYPESAFE_API_KEY=your-real-key
TYPESAFE_INTERVAL_MS=1000
```

The key is independent of the OpenRouter build-planning key. No Java SDK or new mod
dependency is required. `.env` is cached by the existing EnvLoader; restart the
server after changing it. Start or restart think mode to load updated settings.

```text
/assistant summon
/assistant think
```

Then try ordinary chat:

- `protect me bot!` or `bot, autoprotect me` — escort, intercept threats, and react locally to recent attacks on you.
- `follow me bot` — follow without initiating combat.
- `stay here bot` — remember this spot and return if displaced.
- `hunt nearby monsters bot` — clear nearby hostiles.
- `avoid fighting bot` — retreat from threats and follow when clear.
- `bot, what can you do?` — get a private chat explanation of available abilities.
- `bot, go collect wood` — find nearby logs and collect their drops.
- `bot, build a wooden arch over that path` — make a freeform project from your description.
- `bot, dig a 3x3 pit two blocks deep here` — choose excavation steps from local terrain.
- `bot, make it taller` — retain the original project and add a refinement.

Look at a block to ground words such as “that”; the request saves that focus. Give
sizes, materials and location when they matter. There is no hut or other structure
template: TypeSafe chooses the next primitive from the current snapshot.

Only owner chat sent **while this mode is active** is captured. Address the bot to
distinguish instructions from conversation. `/assistant think goal protect me`
sends the same kind of instruction without posting it in public chat. Unsupported
instructions retain the current goal. Existing build/mine/place/deposit commands
still work separately; think mode never calls the OpenRouter build planner.

```text
/assistant think interval        # show the interval
/assistant think interval 0.5    # update immediately, in seconds
/assistant status               # goal, action, confidence, latency, request status, outcome
/assistant stop                 # cancel requests and stop moving
```

Starting another task, dismissing the bot, or shutting down also cancels thinking.
`/assistant think` starts a fresh session; goals, hold position, chat, and live
interval overrides are not saved across sessions. The bot sends a short private
acknowledgment when it accepts a goal instruction. Ordinary task combat interrupts
remain unchanged; think mode handles its own combat choices.

## What goes to TypeSafe

Each request contains a server-thread snapshot of:

- Bot/owner coordinates, health, armor, food, held item, fire/water state, and the
  block at their feet; owner distance and bot movement penalty.
- Dimension, world tick, and rain.
- Up to 12 owner chat messages from the last two minutes, each capped at 512
  characters, with unconsumed messages identified separately. Other players'
  chat is excluded.
- Nearby hostile monsters: stable entity UUID, type, position, health, distance to
  bot/owner, line of sight, who they target, recent attacks, and creeper swelling.
- Persistent goal, hold anchor, previous action, and its local execution outcome.
- Up to eight accepted instruction messages (original plus recent refinements),
  with the block focus captured for each. These persist past the two-minute chat window.
- For work goals or new chat: inventory, placeable materials, a moving 9×8×9
  terrain window around the bot, up to 32 recent work outcomes, and temporary failures.
- Bounded candidates: up to 64 placements, 64 digs, 16 tree logs, 16 movement
  destinations and 16 nearby dropped items, plus available log-to-plank conversions.
  All block coordinates are world coordinates; omitted terrain is unknown.

Chat is consumed once for goal updates. Old messages can remain as conversational
context, but cannot replay an old instruction. New goals require owner input;
TypeSafe may complete or release an existing work goal and return to holding position. Memory stays bounded even during an API outage. Credentials are used
only in the authorization header and are not included in state or status output.

## Fan-out and execution

Every call contains a goal classification and seven **speculative action choices**,
one for each possible goal, plus independent reply, wood-work, project-work and
material choices. Nearby monsters add a target choice and one **Noul** threat
judgment per monster. The default target cap gives at most 25 questions in one
request. Position and material are separate choices, avoiding a large combination
of every block with every location. All questions use the same snapshot.

The controller selects the action branch for the accepted goal, including a goal
newly accepted in that same response. The questions do not depend on one another's
answers. Choice confidence gates goal changes, actions, and target selection.
Model-selected protect attacks also need sufficient Noul threat probability.
Protect mode can react on the next task tick to a monster that hurt its owner in
the last five seconds, without waiting for another request. That reaction still
obeys target validation, pending-chat cancellation, API failure/lease checks and
an already-selected retreat. Noul is a
yes-probability, separate from Choice confidence.

Movement/attack execution runs every five game ticks (~250ms at 20 TPS). The
configured interval is a wall-clock minimum between request starts, checked at
that tick granularity. There is at most one request in flight per bot. Existing
actions continue while waiting for fresh decisions, subject to a lease of the
larger of twice the interval or the maximum response age. New owner input stops
the previous action while its instruction is evaluated.

Targets are checked on every task tick: living hostile monster, same dimension,
within scan radius of **both** bot and owner, and visible to the bot. Melee requires
three-block range and has a one-second cooldown. Players, pets, and passive mobs
are not attack candidates. Movement uses the existing A* pathfinder, with no
teleport fallback. Unreachable destinations stop movement and appear in status.
This mode does not travel between dimensions; requests and movement pause while
the owner is offline, dead, spectating, or in another dimension.

Errors hold position. Slow or superseded responses are discarded. Network and
transient HTTP failures back off exponentially to 30 seconds; numeric Retry-After
headers can extend the delay (up to five minutes). Permanent HTTP errors such as
401 or 422 pause requests until `/assistant think` is restarted after fixing the
configuration. Error bodies are not exposed to chat/logs. HTTP work is asynchronous;
workers never access Minecraft objects and late results cannot restart a stopped task.

## Freeform work and resource recovery

The project goal keeps the owner's actual description. Each response selects one
primitive: place a block from inventory, dig a visible/reachable block, pick up a
nearby item, move to inspect more terrain, or convert one vanilla overworld log/wood
block into four matching planks. Gathering does not replace the project: TypeSafe
can collect/craft missing material, then return to construction in the next decisions.
Other crafting recipes, remote exploration, furnaces and container access are not
implemented in think mode. Supply tools and materials that it cannot obtain locally.

The executor rechecks loaded terrain, reach, line of sight, world border, interaction
permission, inventory and the current block before edits. It doesn't replace occupied
blocks during placement, mine block entities/unbreakable blocks, or dig directly
under the bot/owner. Mining requires a suitable tool when one is needed for drops.
A successful edit or conversion is used at most once per accepted decision.
Failures temporarily exclude the candidate and remain in recent outcomes. Wood-only
collection uses nearby leaves as a tree heuristic; this isn't a provenance guarantee.

TypeSafe can choose to finish when the requested result is satisfied, clearing the
saved instructions and reporting completion. It can release an obsolete goal or a reasonable partial stopping point based on
the request and current state, reporting release rather than success. When giving
up would conflict with the request or is uncertain, it should ask for help. A lack
of resources should first lead to recovery; if no useful step is available, it can
ask for clarification/materials while retaining the goal. Help requests don't replace
a project, and repeated requests for assistance don't spam chat.

These are reactive model decisions, not a guaranteed architectural plan or completion
proof. Larger structures depend on navigation, the local window and TypeSafe's spatial
choices. Work edits are not registered as a plan in `/assistant undo`; that command
continues to cover the separate plan-based build system.

## Configuration

All values support OS environment variables and `.env`; the OS takes precedence.

| Variable | Default | Meaning |
|---|---|---|
| `TYPESAFE_API_KEY` | required | API key |
| `TYPESAFE_URL` | `https://api.typesafe.ai/v1/systemone` | Full HTTPS evaluation endpoint |
| `TYPESAFE_MODEL` | `jev-latest` | Model identifier |
| `TYPESAFE_INTERVAL_MS` | `1000` | Request cadence, 250–60000ms |
| `TYPESAFE_TIMEOUT_MS` | `3000` | HTTP timeout, 250–30000ms |
| `TYPESAFE_MAX_AGE_MS` | `3000` | Oldest acceptable snapshot response, 250–30000ms |
| `TYPESAFE_MIN_CONFIDENCE` | `0.55` | Choice confidence threshold, 0–1 |
| `TYPESAFE_THREAT_THRESHOLD` | `0.65` | Protect-mode threat Noul threshold, 0–1 |
| `TYPESAFE_SCAN_RADIUS` | `20` | Local scan and combat leash, 4–32 blocks |
| `TYPESAFE_MAX_TARGETS` | `12` | Nearest hostile candidates, 1–32 |

Confidence thresholds are initial tuning values, not guarantees of accuracy. Use
status and actual gameplay to tune them. A low-confidence choice holds position;
it never asks a slower model to reason about the situation.

## Verification

`./gradlew build` runs protocol, decision gating, chat retention, scheduling,
stale-result, cancellation, retry, configuration, freeform selection, resource-recovery,
instruction-retention, goal-completion and local HTTP contract tests.
These tests use synthetic responses, not the live TypeSafe service.

For an in-game smoke test, start think mode with a configured key, ask it to
protect you near a zombie, then ask it to hold position. Confirm goal acknowledgments
and status, change the interval, and use `/assistant stop` during a pending request.
Also try losing sight of the target and changing dimension. For freeform work:

1. Ask what it can do and confirm one help reply without losing the current goal.
2. Give it a few planks, place trees nearby, and request a wooden structure requiring
   more planks. Check that it chops, picks up, converts logs, and resumes the project.
3. Point at terrain and request a pit with explicit dimensions; move away from the
   intended dig cells so it doesn't need to dig directly beneath you.
4. Change the instruction during a pending request and confirm the old action stops.
5. Remove needed tools/resources and test its help/release behavior, then give a new goal.
6. Verify completion clears the goal and results in holding position rather than more edits.

Live model quality, placement orientation, survival resource use and navigation
behavior require gameplay validation. Tests don't contact the live TypeSafe service.

API design references: [TypeSafe API](https://docs.typesafe.ai/api),
[speculative fan-out](https://docs.typesafe.ai/patterns/fan-out),
[primitives](https://docs.typesafe.ai/primitives).
