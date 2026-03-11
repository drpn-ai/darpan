# Theme Library (JS/CSS/HTML)

This document defines the shared theme library for symmetric Darpan UI implementation.

## Purpose

Provide reusable building blocks so screens do not re-implement styling and step-flow logic.

## Library Location

`runtime/component/darpan/theme-library/`

- `css/tokens.css`
- `css/components.css`
- `js/theme-runtime.js`
- `html/blocks.html`
- `README.md`

## What is Reusable

- **CSS tokens**: typography, spacing, color, radius, shadow.
- **CSS components**: shell, progress flow, cards, fields, actions, status blocks.
- **JS behavior**: keyboard-first step progression (`Enter` next, `Shift+Enter` back), active-step sync between panels and indicators.
- **HTML blocks**: canonical shell, 4-step flow, and config card templates.

## Example Adoption

```html
<link rel="stylesheet" href="component://darpan/theme-library/css/tokens.css" />
<link rel="stylesheet" href="component://darpan/theme-library/css/components.css" />
<script src="component://darpan/theme-library/js/theme-runtime.js"></script>

<section class="dt-shell" data-step-flow data-initial-step="1">
  <ol class="dt-flow">
    <li class="dt-flow-step" data-step-indicator="1"><span class="dt-flow-index">01</span> identity</li>
    <li class="dt-flow-step" data-step-indicator="2"><span class="dt-flow-index">02</span> source</li>
    <li class="dt-flow-step" data-step-indicator="3"><span class="dt-flow-index">03</span> review</li>
  </ol>

  <article class="dt-card" data-step-panel="1">Step one content</article>
  <article class="dt-card dt-hidden" data-step-panel="2">Step two content</article>
  <article class="dt-card dt-hidden" data-step-panel="3">Step three content</article>
</section>
```

## Operational Impact

- No existing screen behavior changes until screens opt in and include these assets.
- Enables consistency across Mapping, Reconciliation, and Settings pages.
- Reduces duplicated CSS/JS/HTML patterns and improves maintenance.
