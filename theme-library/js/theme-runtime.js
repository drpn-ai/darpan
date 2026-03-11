(function (global) {
  "use strict";

  function clampStep(value, max) {
    var parsed = parseInt(value, 10);
    if (Number.isNaN(parsed) || parsed < 1) return 1;
    if (parsed > max) return max;
    return parsed;
  }

  function getPanels(container) {
    return Array.prototype.slice.call(container.querySelectorAll("[data-step-panel]"));
  }

  function getIndicators(container) {
    return Array.prototype.slice.call(container.querySelectorAll("[data-step-indicator]"));
  }

  function setActive(container, step) {
    var panels = getPanels(container);
    panels.forEach(function (panel) {
      var panelStep = clampStep(panel.getAttribute("data-step-panel"), panels.length);
      panel.classList.toggle("dt-hidden", panelStep !== step);
    });

    var indicators = getIndicators(container);
    indicators.forEach(function (item) {
      var indicatorStep = clampStep(item.getAttribute("data-step-indicator"), indicators.length);
      item.classList.toggle("is-active", indicatorStep === step);
    });

    container.setAttribute("data-current-step", String(step));
  }

  function runValidator(container, nextStep) {
    var validatorName = container.getAttribute("data-step-validator");
    if (!validatorName) return true;
    var validatorFn = global[validatorName];
    if (typeof validatorFn !== "function") return true;
    return validatorFn({ container: container, nextStep: nextStep }) !== false;
  }

  function bindButtons(container, maxStep) {
    container.addEventListener("click", function (event) {
      var target = event.target;
      if (!(target instanceof Element)) return;

      var nextBtn = target.closest("[data-step-next]");
      if (nextBtn) {
        event.preventDefault();
        var currentStep = clampStep(container.getAttribute("data-current-step"), maxStep);
        var nextStep = clampStep(currentStep + 1, maxStep);
        if (!runValidator(container, nextStep)) return;
        setActive(container, nextStep);
        return;
      }

      var backBtn = target.closest("[data-step-back]");
      if (backBtn) {
        event.preventDefault();
        var current = clampStep(container.getAttribute("data-current-step"), maxStep);
        var previous = clampStep(current - 1, maxStep);
        setActive(container, previous);
      }
    });

    container.addEventListener("keydown", function (event) {
      if (event.key !== "Enter") return;
      if (event.target && event.target.tagName === "TEXTAREA") return;

      var current = clampStep(container.getAttribute("data-current-step"), maxStep);
      if (event.shiftKey) {
        event.preventDefault();
        setActive(container, clampStep(current - 1, maxStep));
        return;
      }

      var next = clampStep(current + 1, maxStep);
      if (next === current) return;
      if (!runValidator(container, next)) return;
      event.preventDefault();
      setActive(container, next);
    });
  }

  function initStepFlow(container) {
    var panels = getPanels(container);
    if (!panels.length) return;

    var maxStep = panels.length;
    var initial = clampStep(container.getAttribute("data-initial-step") || "1", maxStep);
    setActive(container, initial);
    bindButtons(container, maxStep);
  }

  function initAllStepFlows(root) {
    var scope = root || document;
    var flows = scope.querySelectorAll("[data-step-flow]");
    flows.forEach(initStepFlow);
  }

  global.DarpanTheme = {
    initStepFlow: initStepFlow,
    initAllStepFlows: initAllStepFlows,
    setActiveStep: function (container, step) {
      var panels = getPanels(container);
      setActive(container, clampStep(step, panels.length || 1));
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      initAllStepFlows(document);
    });
  } else {
    initAllStepFlows(document);
  }
})(window);
