# Darpan Theme Library

Reusable UI foundation for symmetry across Darpan screens.

## Scope

- Shared CSS design tokens and component classes.
- Reusable JavaScript for keyboard-first step flow behavior.
- Reusable HTML block templates for consistent composition.

## Structure

- `css/tokens.css` - color, spacing, typography, radius, shadow tokens.
- `css/components.css` - reusable shell, step flow, card, field, button, status classes.
- `js/theme-runtime.js` - lightweight step-flow controller (`window.DarpanTheme`).
- `html/blocks.html` - reusable markup templates/snippets.

## Integration Pattern

1. Load `tokens.css` then `components.css` in your screen/template.
2. Mark step containers with `data-step-flow`.
3. Add `data-step-panel="N"` for each step panel and `data-step-indicator="N"` for progress items.
4. Use `data-step-next` and `data-step-back` buttons for navigation.
5. Optionally set `data-step-validator="yourValidatorName"` for per-step validation gate.

## Example

```html
<section data-step-flow data-initial-step="1" data-step-validator="validateMappingStep">
  <ol class="dt-flow">
    <li class="dt-flow-step" data-step-indicator="1"><span class="dt-flow-index">01</span> identity</li>
    <li class="dt-flow-step" data-step-indicator="2"><span class="dt-flow-index">02</span> source</li>
    <li class="dt-flow-step" data-step-indicator="3"><span class="dt-flow-index">03</span> review</li>
  </ol>

  <div class="dt-card" data-step-panel="1">...</div>
  <div class="dt-card dt-hidden" data-step-panel="2">...</div>
  <div class="dt-card dt-hidden" data-step-panel="3">...</div>
</section>
```

## Operational Notes

- Library is additive; no runtime behavior changes until assets are explicitly loaded by screens.
- Keep naming prefix `dt-` for all reusable classes to avoid collisions.
