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
