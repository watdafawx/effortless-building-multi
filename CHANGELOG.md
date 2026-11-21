## 5.4.1

New:
- **JSON-extensible Designs**: drop `.json` files into `config/shapewright/designs/` (single design or array) to add or override Designs in the Shape Generator, hot-reloaded whenever the screen opens.
- **JSON-extensible Floor Themes**: customize or add 5-block palettes in `config/shapewright/floor_themes.json`, hot-reloaded whenever the Floor Designer opens.

## 5.4.0

New:
- **Floor Designer** (opened from Floor -> `Floor Designer...` in the Shape Generator): generate multi-block symmetrical floors with 10 styles (**Random Art** procedural composer, Mandala, Floral Mosaic, Sunburst Star, Crescent Emblem, Compass Rose, Ornate Tile, Royal Medallion, Labyrinth, Rose Window, Gradient Fade), 6 outlines (Quatrefoil, Circle, Square, Octagon, Diamond, Scalloped), 5 customizable block slots, 6 curated color themes, and 8-way symmetry touch-up painting. Saves as a multi-block schematic ready to place.
- **Live docked side-panel editor** for anchored shapes: new **Edit Shape** tool in the anchor quick-controls bar (and `Side` / `Full` toggle in Shape Generator) docks settings to the left over the live world so you can fine-tune properties in real time and drag outside the panel to look around.
- 7 new built-in Designs: Gazebo, Cottage Shell, Windmill, Aqueduct Span, Portal Frame, Pagoda Tier, Twisted Spire.

Fixes:
- Editing shape properties while a preview is anchored now updates the anchored preview immediately.
- Switching from a multi-part Design back to a base shape in the Shapes tab now clears the previous design's parts.
- Shape Generator now uses accurate size labels (`Half-width`, `Half-span`, `Radius X`, `Radius`) per shape and hides parameters that have no effect in the current mode.

## 5.3.1

Fixed:
- The shape list in the Shape Generator could not be scrolled to its last rows (they stayed cut off at the bottom).

## 5.3.0

New:
- Two new shapes in the Shape Generator: Frustum (a truncated cone or pyramid with round or 3+ sided base, adjustable top size, wall and caps) and Roof (gable, hip, shed or gambrel roof shell with pitch, overhang, thickness and open or closed gable ends).

## 5.2.0

New:
- The Shape Generator library list has tabs: Shapes, Designs and Saved (your templates). Designs no longer sit at the bottom of a long list. Searching still looks through all three at once.
- The library list scrolls faster with the mouse wheel, and the sidebar is a little wider.

Fixes:
- The "Decimate (%)" modifier label showed "Format error: Decimate (%)".

## 5.1.0

New:
- Optional mc-mcp support: with the mc-mcp mod installed, Export gets "Send to ghost (mc-mcp)", which shows the build as a translucent ghost preview in the world. Nothing changes without mc-mcp.
- Schematic shapes have a "Reload list" button that rescans the schematic folders without restarting.

## 5.0.4

Fixes:
- Crash when opening the Shape Generator or Block Palette preview with Sodium installed ("Not building!"). Flat and textured faces are now drawn in separate passes.

## 5.0.3

Docs:
- README now has screenshots of the radial menu, the modifier settings, a mirrored build preview and the functional (survival) settings.

## 5.0.2

Changes:
- Block Palette screen: the "selected shape" preview now draws real block textures, is zoomed in to fill its panel and no longer shows the axis gizmo.

## 5.0.1

New:
- The Shape Generator's 3D preview now draws the real block textures of your blocks, parts and palette instead of flat colors. Tinted blocks (grass, leaves) keep their tint; with no block chosen it uses the held block.

## 5.0.0

Effortless Building is now **Shapewright** (mod id `shapewright`, by watdafawx), based on Effortless Building by Requios. Because the mod id changed, install it in place of Effortless Building (do not run both). Your saved client config, palettes, shape templates and per-world settings are moved over automatically on first start.

New:
- Shape Generator modifiers: twist, taper, shear, wave, bend, grow/shrink, noise and decimate, with a choice of deform axis and a movable origin (start, center, end plus x/y/z offsets).
- 3D gizmo that shows the anchor and the modifier origin, in the Shape Generator preview and in the world. A "Gizmo" button toggles it. Arms are sized to the shape.
- Up to 128 shape parameters can be synced.

Fixes:
- Shape Generator: clicking a template no longer resets the list scroll position.
