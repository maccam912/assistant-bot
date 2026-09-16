# TypeSafe think mode

`/assistant think` gives the bot a reactive brain using TypeSafe's System One API.
It starts with a **follow** goal. Owner chat can change its goal, and it keeps that
goal until instructed otherwise or think mode ends.

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

- `protect me bot!` — escort and intercept immediate threats.
- `follow me bot` — follow without initiating combat.
- `stay here bot` — remember this spot and return if displaced.
- `hunt nearby monsters bot` — clear nearby hostiles.
- `avoid fighting bot` — retreat from threats and follow when clear.

Only owner chat sent **while this mode is active** is captured. Address the bot to
distinguish instructions from conversation. `/assistant think goal protect me`
sends the same kind of instruction without posting it in public chat. Unsupported
instructions retain the current goal; this mode's action set covers movement and
combat. Use the existing build/mine/place/deposit commands for those tasks.

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

Chat is consumed once for goal updates. Old messages can remain as conversational
context, but cannot replay an old instruction. Goals cannot change without new
owner input. Memory stays bounded even during an API outage. Credentials are used
only in the authorization header and are not included in state or status output.

## Fan-out and execution

Every call contains a goal classification and five **speculative action choices**,
one for each possible goal. With nearby monsters it also asks for a target choice
and one independent **Noul** threat judgment per monster. With the default 12
target cap, this is at most 19 questions in one request. All use the same snapshot.

The controller selects the action branch for the accepted goal, including a goal
newly accepted in that same response. The questions do not depend on one another's
answers. Choice confidence gates goal changes, actions, and target selection.
Protect-mode attacks also need sufficient Noul threat probability. Noul is a
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
stale-result, cancellation, retry, configuration, and local HTTP contract tests.
These tests use synthetic responses, not the live TypeSafe service.

For an in-game smoke test, start think mode with a configured key, ask it to
protect you near a zombie, then ask it to hold position. Confirm goal acknowledgments
and status, change the interval, and use `/assistant stop` during a pending request.
Also try losing sight of the target and changing dimension. Live model quality and
navigation behavior require gameplay validation.

API design references: [TypeSafe API](https://docs.typesafe.ai/api),
[speculative fan-out](https://docs.typesafe.ai/patterns/fan-out),
[primitives](https://docs.typesafe.ai/primitives).
