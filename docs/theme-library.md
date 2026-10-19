# Theme Library (JS/CSS/HTML)

> **Legacy.** This library styles only the backend Moqui screens under `/apps/darpan`
> (`screen/Settings.xml`, `screen/Reconciliation.xml`, `screen/Migrations.xml`), which are
> super-admin only and are not extended. The product UI is `darpan-ui` and the operator UI is
> `darpan-admin-ui`; neither uses this library. Do not adopt it in new work.

This document describes the theme library the legacy backend screens share.

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

## How the screens include it

The screens link the files by URL under the app mount, not by `component://` location (a browser
cannot resolve `component://`):

```html
<link rel="stylesheet" href="/apps/darpan/theme-library/css/tokens.css" />
<link rel="stylesheet" href="/apps/darpan/theme-library/css/components.css" />
<script src="/apps/darpan/theme-library/js/theme-runtime.js"></script>

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

- Only the three legacy screens above include these assets, and they link only the two CSS files;
  no screen loads `js/theme-runtime.js` today.
- Changes here affect super-admin backend screens only.
