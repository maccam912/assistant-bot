# VXB-2: a drawn format for LLM-authored Minecraft builds

VXB-2 is the format the assistant bot's planner emits and the mod compiles. It
replaces VXB-1.1 outright — there is no primitive layer, no macro layer and no
semantic feature layer, because all three turned out to be the problem rather
than the solution.

The model-facing prompt is [`docs/vxb2-prompt.md`](docs/vxb2-prompt.md), packaged
as `src/main/resources/vxb2-prompt.md` and loaded at runtime. The worked example
in that prompt is compiled by `Vxb2CompilerTest`, so the documentation cannot
drift from the implementation.

## Why the previous format was replaced

VXB-1.1 grew a vocabulary of `box`, `sphere`, `ellipsoid`, `cylinder`, `cone`,
`pyramid`, `triangle`, `staircase`, rotated macros and a semantic `features`
section. It was more expressive on paper and worse in practice, for two reasons
that turn out to be the same reason.

**The output looked assembled from primitives.** That is not a coincidence of
prompting. When the cheapest way to describe a roof is `cone 8 6 8 5 P`, the
roofs are cones. A format's shortest path is the style it produces.

**A model could not see what it had written.** `sphere 4 8 4 3 S` requires
imagining a sphere; nothing in the file shows one. Every mistake — an
intersection, a gap, a wall in the wrong place — stayed invisible until the
blocks were in the world. Layers were the one part of VXB-1.1 that showed their
own result, and they were positioned as the fallback for irregular cases.

## The shape of VXB-2

**Everything is drawn.** The only way to place a block is to write its palette
character in a 2D grid. There are no volume commands and no coordinate commands.
The source text is a picture of the finished build, at 1:1, which means the model
is reasoning about the thing it is producing rather than about a description of
it.

**Slices are taken on any axis.** `plan y=3` is a level from above; `face south
z=6` is that side seen from outside. Elevations matter because walls, gables,
facades and rooflines are shapes people draw head-on, and reconstructing them
from stacked top-down levels is exactly the kind of mental rotation that
produces blocky output. A `face` on an interior plane is a cross-section.

Faces are drawn as seen from outside, so `face north` and `face east` run
backwards along their axis. That is the format's one genuine trap — symmetric
walls hide a reversal and off-centre ones do not — so the prompt tells the model
to reach for `face south` and `face west` for every plane by default, both of
which run in the ascending direction, and to use the reversed views only when it
deliberately wants the exterior elevation.

**Slices are authoritative, not sequential.** A slice fully determines the cells
it covers, so file order is irrelevant and there is no last-write-wins layering
to track. Where two views cover a shared cell they must agree, and a
disagreement is a compile error naming both glyphs and both coordinates. That
check is the mechanical test of whether the author held one consistent 3D shape
in mind — the thing that could not be checked at all when geometry arrived as
opaque primitive calls.

**Row and column counts come from `size`.** A slice reads exactly as many rows as
its plane is deep, so there is no terminator to forget. Rows anchor at the lowest
column coordinate; missing trailing air is padded and extra trailing dots are
ignored with a compiler warning. Extra block glyphs are never discarded.
Optional `z3`/`y5` row labels are verified against the coordinate they claim,
which turns a dropped row from a silently shifted facade into a named error.

**Block states are not writable.** The palette maps one character to one block
ID. `facing`, `axis`, `half`, `hinge`, `type`, `part` and `shape` are derived
from the drawing by `Vxb2Inference`. Directional state was the largest source of
LLM error in VXB-1.1 and the least visible: a model that drew a correct staircase
still got the facings wrong, and nothing in the source revealed it.

## What the compiler infers

| Drawn | Derived |
| --- | --- |
| a run of stair glyphs | ascent direction from the diagonal the run forms, plus Minecraft's own inner/outer corner shapes |
| a stair or slab with air below and mass above | `half=top` / `type=top` |
| a line of logs, basalt, chains | `axis` from the direction of the run |
| two stacked door glyphs | paired halves, `facing` toward whichever side an exterior flood fill reaches, hinge from the neighbours |
| two adjacent bed glyphs | head against the wall, foot away from it |
| a torch with no floor under it | `wall_torch` facing away from its supporting wall |
| a lantern under a ceiling | `hanging=true` |
| ladders, trapdoors | the wall they touch |
| furnaces, chests, lecterns, campfires | facing into the room rather than out of the building |

Where a shape is genuinely ambiguous — a lone decorative stair, say — the palette
symbol takes a hint (`up=`, `axis=`, `half=`, `facing=`, `outside=`, `hanging=`)
rather than a raw block state.

Because inference runs over the assembled glyph grid, a rotated `part` is a
rotated *drawing*: its states are re-derived in the new orientation instead of
being transformed, which removes an entire class of transform bugs that VXB-1.1
macros had to handle by hand.

## Token cost

The concern with a drawn format is size, and the honest answer is that VXB-2
spends more characters on a plain cuboid than `box` did and fewer on anything
worth building. The savings come from devices that do not cost visibility:

- `plan y=1..3` and `plan y=0,4` draw repeated planes once.
- Windows (`plan y=2 x=1..7 z=1..5`) let a detail slice cover only the rectangle
  it changes, so a single torch does not require redrawing a wall.
- `part` / `at` stamps a genuinely repeated module.
- Cells no slice covers are air, so empty space is never drawn.

The cabin in the prompt — nine header lines, four elevations, one floor and five
small windows — is about 1.4 kB. Trimming further, by run-length encoding rows or
by reintroducing volume commands, would buy tokens by taking back the one
property the format exists for.

## Compilation pipeline

1. `Vxb2Parser` — header, palette, parts, and slices into a glyph grid, with
   bounds, row-shape and cross-view agreement checked as cells are written.
2. Palette repair — near-miss block IDs are corrected against the live server
   registry before any state is inferred.
3. `Vxb2Inference` — glyphs to exact block states and atomic placement groups.
4. `VxbStructureValidator` — grounding, gravity, fixture support, doorway
   clearance, interior access, isolated panes.
5. `VxbCompiler` — assembles diagnostics; blockers throw with a report the repair
   loop can act on line by line.

Compilation validates VXB mechanics; it does not accept the build. The LLM tool
loop returns a plan only after an explicit `submit_vxb` call, and submission
rejects an obvious mismatch with a numeric footprint in the user's request.

`VxbPreviewRenderer` closes the loop by redrawing a compiled structure as VXB-2
slices in the author's own palette symbols, so `inspect_vxb` returns something
directly comparable to the source rather than a separate projection notation.

## Header reference

```text
VXB-2
name spruce_cabin
size 9 7 7          // required; also fixes every slice's row and column count
ground true         // default true: the build must connect to y=0
terrain replace     // default replace; 'keep' leaves undrawn cells as world terrain
```

Under `terrain keep`, a cell drawn as `.` is excavated and a cell no slice covers
is left alone — which is what makes cliff houses, tunnels and ruins expressible
without a separate excavation concept.
