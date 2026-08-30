# VXB-1.1: an LLM-friendly, mechanically compiled format for Minecraft voxel builds

VXB-1 remains accepted for imports. VXB-1.1 adds local explicit layers, semantic
multi-block features, atomic placement groups, support/collision validation, and
the opt-in `allow_floating` header. It now also includes deterministic geometry
primitives, reusable rotated macros, centered placement, terrain snapshots, and
terrain-preserving excavation. The canonical model-facing grammar and example
live in [`docs/vxb1-prompt.md`](docs/vxb1-prompt.md); the same prompt is packaged as
`src/main/resources/vxb1-prompt.md` and loaded by the mod at runtime.

## Centered, terrain-aware placement

The bot's block at execute time is the horizontal center of the declared `size`,
not the structure's northwest corner. Local `(floor(sizeX/2), 0,
floor(sizeZ/2))` maps to the bot marker. Even dimensions necessarily have no
single geometric center, so the volume extends one extra block west/north.

Planning captures a 21×21 site survey before starting the background LLM call.
It includes relative surface heights, surface materials, the blocks immediately
under local y=0, and solid/fluid occupancy slices through y=24. The snapshot is
immutable text, so no world access occurs from the request worker thread.

`terrain_mode replace` preserves the original behavior: clear the plan bounds,
then place the structure. `terrain_mode preserve` retains unused world cells and
clears only coordinates explicitly written with a palette symbol mapped to air.
Layer `.` remains a plan-level eraser rather than an excavation instruction. This
distinction lets a model merge a cliff house into rock while still carving its
rooms and doorway deterministically.

## Geometry primitives

VXB-1.1 expands these commands to ordinary last-write-wins voxel operations
before parsing and validation:

```text
sphere CX CY CZ R S [hollow=true]
ellipsoid CX CY CZ RX RY RZ S [hollow=true]
cylinder CX Y CZ R H S [hollow=true] [caps=true|false]
cone CX Y CZ R H S [hollow=true] [cap=true|false]
pyramid CX Y CZ R H S [hollow=true] [cap=true|false]
triangle X Y Z BASE HEIGHT DEPTH S [axis=x|z] [hollow=true] [caps=true|false]
staircase X Y Z WIDTH STEPS MATERIAL up=DIRECTION fill=S [support=solid|none] [half=bottom|top]
```

The curved primitives use integer-center voxel membership and one-block shells.
The triangle is an extruded isosceles cross-section, which makes it useful for
gable ends and pitched roofs rather than an ambiguous flat polygon. Staircases
generate exact stair facing states; solid support fills the wedge beneath the
treads so it is grounded and survival-buildable. Primitive output receives the
same bounds, registry, grounding, gravity, access, and placement-order checks as
handwritten boxes and sets.

## Reusable macros and villages

Macros define a bounded local module once and stamp transformed instances:

```text
macro cottage size 7 6 7
box 0 0 0 6 0 6 C
layer y 1-3 x 0 z 0 w 7 d 7
PPPPPPP
P.....P
P.....P
P.....P
P.....P
P.....P
PPPPPPP
endlayer
triangle 0 4 0 7 2 7 P axis=x hollow=true caps=false
features
door 3 1 6 spruce outside=south
endfeatures
endmacro

use cottage at 1 0 1
use cottage at 12 0 1 rotate=90
use cottage at 1 0 12 rotate=180 mirror=x
```

`rotate` accepts 0/90/180/270 degrees clockwise from above, followed by optional
X or Z mirroring. Expansion transforms coordinates, facing states, horizontal
axes, directional connection properties, hinges, stair handedness, support
cells, and atomic feature groups. Each macro is compiled in isolation against
its declared size and the global palette. Instances then pass through outer-plan
collision and bounds validation. Limits of 256 instances and 250,000 expanded
cells prevent accidental runaway generations.

## VXB-1.1 additions

```text
layer y 2-3 x 1 z 0 w 7 d 4
PPGGGPP
P.....P
P.....P
PPPPPPP
endlayer

features
door 4 1 6 spruce outside=south
bed 2 1 2 red head=east
stair 4 1 3 spruce up=north
wall_torch 2 2 1 wall=north
endfeatures
```

The compiler expands each feature into exact Minecraft states and retains it as
one placement group. Multi-cell groups reserve their full footprint and are
installed before neighbor updates, preventing partially placed doors and beds.
Semantic directions deliberately avoid exposing the inconsistent raw meanings of
Minecraft `facing` properties to the model.

The planning API offers `compile_vxb`, `apply_vxb_patch`, `inspect_vxb`, and
`submit_vxb` function tools. A draft is compiled locally against the active server
registry; repairs use VXP-1 numbered-line edits instead of rewriting the full file.
With `OPENROUTER_VISION_REVIEW=true`, `inspect_vxb` also sends a locally rendered
PNG projection back to vision-capable models; otherwise it remains text-only.

## Why this shape of format works better than raw 3D dumps

LLMs are bad at three things that naive voxel formats demand:

1. **Keeping a whole 3D grid in working memory.**
2. **Counting long spans of repeated blocks exactly.**
3. **Tracking directional block states while also doing geometry.**

So the format should optimize for the opposite:

- **One local origin** with fixed axes.
- **A tiny palette** so block states become short symbols.
- **Big primitives first** (`box`, `set`) for regular structure.
- **Whole-layer edits** for irregular details.
- **Last-write-wins semantics**, so a model can lay down a simple shell and then refine it.

That combination is far easier for a model to emit correctly than a full `(x,y,z)->block` listing, and much more token-efficient for any build with walls, floors, roofs, columns, or symmetry.

---

## Core format

```text
VXB-1
name cabin_9x7x7
origin 0 0 0
size 9 7 7
axes x=east y=up z=south

palette
. = minecraft:air
C = minecraft:cobblestone
P = minecraft:spruce_planks
L = minecraft:spruce_log[axis=y]
G = minecraft:glass_pane
endpalette

box 0 0 0 8 0 6 C
set 4 1 6 D

layer y 1 z 0
LPPG.GPPL
L.......L
L.......L
L.......L
L.......L
L.......L
LPPPDPPPL
endlayer
```

### Semantics

- `origin ox oy oz`: world-space anchor for the build.
- `size sx sy sz`: local bounding box. Local coordinates run from `0` to `size-1` on each axis.
- `palette`: maps **one-character symbols** to full Minecraft block IDs or block states.
- `box x1 y1 z1 x2 y2 z2 S`: inclusive cuboid fill using symbol `S`.
- `set x y z S`: place one block.
- `layer y Y z Z0`: defines a full X-by-Z slice at fixed `y=Y`, starting at row `z=Z0`.
- Rows inside a `layer` are ordered by **increasing z**.
- Characters inside each row are ordered by **increasing x**.
- **Later commands overwrite earlier ones.**

---

## Why this is token-efficient

### Bad: explicit block listing
A 9×7 foundation is 63 coordinates.

### Better: one primitive
```text
box 0 0 0 8 0 6 C
```

That is the main rule: use primitives for regular mass, and layers only where shape gets irregular.

---

## Why this is easier for an LLM to get right

### 1. Geometry is mostly local and 2D
A model can reason about one `y` slice at a time instead of the full 3D object.

### 2. Directional blocks are hidden behind palette symbols
Instead of repeatedly writing something like:

```text
minecraft:spruce_door[half=upper,facing=south]
```

You write just:

```text
U
```

That removes a major source of model mistakes.

### 3. Refinement is safe
The model can do this in stages:

1. place a solid floor
2. define a wall shell
3. carve doors and windows
4. add roof
5. add details

Since later writes overwrite earlier ones, the model does not need perfect first-pass precision.

---

## Authoring rules worth enforcing in prompts

If you want better model output, tell the model these rules explicitly:

1. **Use `box` for any rectangle or prism larger than 2×2×2.**
2. **Use `layer` for irregular walls or roofs.**
3. **Never emit coordinates outside `size`.**
4. **Use palette symbols consistently; do not invent new symbols mid-file.**
5. **Avoid directional blocks unless necessary; when necessary, hide them in the palette.**
6. **Prefer bilateral symmetry when possible.**
7. **Build from large masses to details.**

Those rules matter more than fancy syntax.

---

## Example: small cabin

This example uses exactly the strategy above:

- one `box` for the foundation
- three wall layers
- three roof layers
- one explicit detail block

The complete file is in `cabin_example.vxb`.

### What it builds

- footprint: 9×7
- height: 7
- cobblestone foundation
- log-framed plank walls
- front windows and centered door
- stepped plank roof
- interior torch

### Why the example is useful

It shows the format can handle:

- **solid volumes** efficiently
- **wall shells** without coordinate spam
- **decorative details** without switching to a huge block list
- **block states** through compact palette entries

---

## When this format is better than alternatives

### Better than a raw coordinate list
Because most Minecraft builds contain lots of rectangular structure.

### Better than a pure 3D ASCII dump
Because full voxel dumps waste tokens on air and encourage counting mistakes.

### Better than natural-language instructions
Because it is deterministic and parseable.

---

## Where it still breaks down

This format is not magic. LLMs still struggle when:

- the build has many rotated stairs/slabs/trapdoors
- there are lots of tiny asymmetrical details
- the build must obey survival-placement constraints step-by-step
- the model must invent a large, aesthetically coherent structure from scratch

For those cases, the right move is usually:

- generate **coarse structure** in VXB-1
- run a validator/repair step
- optionally let a second model pass add ornamentation

---

## Practical pipeline

A good pipeline is:

1. Prompt the model to emit `VXB-1` only.
2. Parse it.
3. Validate bounds, unknown palette symbols, and row lengths.
4. Expand to explicit block placements.
5. Feed placements into your Minecraft automation layer.

I included a tiny reference parser:

- `vxb_to_blocklist.py`

It expands the example build into:

- `cabin_example_blocklist.csv`

That CSV is easy to translate into `/setblock`, WorldEdit operations, a bot plan, or a schematic pipeline.

---

## Bottom line

If your goal is **LLM-emittable**, **compact**, and **easy to validate**, a **hybrid primitive + layer format** is the right compromise.

Not because it is theoretically elegant, but because it matches the failure modes of current models.
