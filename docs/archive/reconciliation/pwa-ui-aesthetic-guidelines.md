# PWA UI Aesthetic Skill (Dark Monochrome Only)

This guide defines the required PWA visual skill for reconciliation screens.

## Source of Truth

Use only these active mockups as implementation references:

- `docs/assets/ui-mockups/ticket-9-mapping-step-card-flow.svg`
- `docs/assets/ui-mockups/ticket-10-reconciliation-step-card-flow.svg`
- `docs/assets/ui-mockups/settings-page-dark-minimal-technical.svg`

Do not use minimal-light or older concept mockups for implementation.

## 1. Required Visual Direction

All new PWA UI output must use this style only:

- Black and charcoal monochrome surfaces.
- White and gray lettering with strong contrast hierarchy.
- Whitespace-heavy composition with calm density.
- Console-like monospaced typography throughout.
- Technical metadata and step flow language retained.

## 2. Typography (Console Style Only)

Use monospaced-first stacks for all interface text:

- `"IBM Plex Mono", "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace`

Typography scale:

- Display/title: 34 to 56, weight 700.
- Section/card title: 32 to 38, weight 700.
- Labels/metadata: 12 to 13.
- Values/buttons/body: 14 to 18.

Do not use sans-serif-first UI typography for these screens.

## 3. Color Tokens (Monochrome Only)

- `--bg-0: #090909`
- `--bg-1: #121212`
- `--grid-line: #171717`
- `--surface-0: #101010`
- `--surface-1: #161616`
- `--border-subtle: #2a2a2a`
- `--border-strong: #303030`
- `--text-primary: #f2f2f2`
- `--text-secondary: #c8c8c8`
- `--text-muted: #9a9a9a`
- `--text-dim: #848484`
- `--action-primary-bg: #f2f2f2`
- `--action-primary-fg: #111111`
- `--action-secondary-bg: #1a1a1a`
- `--action-secondary-border: #8e8e8e`

No accent palettes, no colorful hero gradients, and no status-color-heavy dashboard styling.

## 4. Whitespace and Composition Rules

- Use an 8px spacing system with generous gaps (`16, 24, 32, 48, 56`).
- Keep one dominant card/panel per active action block.
- Maintain large negative space around titles and between functional groups.
- Avoid dense table-like compression in form flows.
- Keep top-zone breathing room for technical header + title + stepper.

## 5. Layout Patterns

- Top zone: technical key, large title, execution line.
- Stepper zone: 4-step sequence with one active step.
- Main zone: focused active-step card.
- Settings zone: split panel allowed (left selector, right form).
- Shadow:
  - `0 10px 14px rgba(0,0,0,0.45)`

## 6. Component Rules

### Stepper

- Keep all 4 steps visible.
- Active step is high-contrast; inactive steps are muted.
- Step numbers must remain explicit (`01` to `04`).

### Form Controls

- Label above input, technical tone.
- Input height around 56 to 58 on desktop.
- Filled dark control surfaces with subtle border separation.

### Action Buttons

- Secondary first, primary second.
- Primary uses high-contrast fill (`#f2f2f2` on dark).
- Keep short action verbs: `back`, `validate`, `continue`, `run`, `save`.

### Metadata

- Keep compact helper lines like `active_step`, `runtime_states`, `fallback`.
- Do not replace technical metadata with marketing text.

## 7. PWA Behavior Rules

- Breakpoints:
  - `>=1280`: desktop composition with full step header.
  - `768-1279`: stacked sections, step header retained.
  - `<768`: single column, sticky bottom action row.
- Minimum touch target `44x44`.
- Preserve step context on mobile.
- Respect `prefers-reduced-motion`.

## 8. Hard Prohibitions

Do not use these patterns unless explicitly requested in a new approved mockup:

- Light-theme UI for primary reconciliation/settings flows.
- Colorful gradients, bright accents, or hero banners.
- Sans-serif-first typography.
- Dense, low-whitespace form packing.
- Legacy concept-screen visual language.

## 9. Accessibility Baseline

- Body text contrast must meet WCAG AA (4.5:1).
- Keep visible keyboard focus for all interactive controls.
- Never communicate state with color alone.
- Keep explicit text labels on actions.

## 10. Implementation Checklist

- Confirm target screen maps to active dark mockups.
- Confirm monospaced stack is applied globally for the screen.
- Confirm monochrome tokens only.
- Confirm whitespace density is preserved.
- Confirm stepper and action hierarchy are intact.
- Confirm mobile behavior and focus/contrast requirements.

## Operational Notes

- This document defines visual skill only; it does not alter contracts or services.
- If active dark mockups change, update this file and `docs/reconciliation/ui-mockups.md` in the same change.
