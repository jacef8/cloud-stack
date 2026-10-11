/* Lenis — smoothed wheel scrolling.
   Attached to the window, the record dialog, and every scrolling panel.

   Lenis measures a scroller's extent from a single content element. The panels
   lay their children out directly (flex column with a gap, or plain block
   flow), so each one gets a content wrapper inserted at runtime and the
   layout is copied onto it; the panel keeps its own padding, overflow and
   scrollbar. Renderers write into ids nested inside these panels, never into
   the panel itself, so the wrapper survives a re-render.

   Touch is left alone deliberately (Lenis does not smooth it by default):
   phones already have momentum scrolling and overriding it feels worse. Since
   this runs for fine pointers only, and the panels drop their own scrolling
   under 700px anyway, nothing here reaches a phone. */
(function () {
  if (!window.Lenis) return;
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  if (!matchMedia('(pointer: fine)').matches) return;   // wheel and trackpad only

  const EASE = t => Math.min(1, 1.001 - Math.pow(2, -10 * t));
  const made = [];

  const page = new Lenis({ duration: 0.9, easing: EASE, smoothWheel: true });
  made.push(page);

  // The dialog scrolls a long record board, and its content is one .dlg child.
  const dlg = document.getElementById('mdlg');
  let modal = null;
  function attachDialog() {
    const content = dlg && dlg.querySelector('.dlg');
    if (!dlg || !content || modal) return;
    modal = new Lenis({ wrapper: dlg, content, duration: 0.8, easing: EASE, smoothWheel: true });
    made.push(modal);
  }
  function detachDialog() {
    if (!modal) return;
    made.splice(made.indexOf(modal), 1);
    modal.destroy();
    modal = null;
  }
  if (dlg) {
    // The page rebuilds .dlg each time it opens, so bind on open and drop on close.
    new MutationObserver(() => (dlg.open ? attachDialog() : detachDialog()))
      .observe(dlg, { attributes: true, attributeFilter: ['open'] });
    dlg.addEventListener('close', detachDialog);
  }

  /* ── scrolling panels ───────────────────────────────────────────────── */

  const PANELS = '.page .scroll, .home-a, .home-b';

  // Give a panel its single content child, carrying the panel's own layout
  // onto it so the children sit exactly where they did before.
  function contentOf(panel) {
    let c = panel.querySelector(':scope > .lsc');
    if (c) return c;
    c = document.createElement('div');
    c.className = 'lsc';
    const cs = getComputedStyle(panel);
    if (cs.display === 'flex' || cs.display === 'grid') {
      c.style.display = cs.display;
      c.style.flexDirection = cs.flexDirection;
      c.style.rowGap = cs.rowGap;
      c.style.columnGap = cs.columnGap;
      c.style.gridTemplateColumns = cs.gridTemplateColumns;
    }
    c.style.flex = '0 0 auto';     // never let the panel compress it
    c.style.minHeight = '0';
    while (panel.firstChild) c.appendChild(panel.firstChild);
    panel.appendChild(c);
    return c;
  }

  const bound = new WeakMap();

  function bind(panel) {
    if (bound.has(panel)) { bound.get(panel).resize(); return; }
    // A panel that cannot scroll needs no instance, and an offscreen one
    // measures zero — both are picked up later, on the next page change.
    if (!panel.clientHeight) return;
    const l = new Lenis({
      wrapper: panel, content: contentOf(panel),
      duration: 0.8, easing: EASE, smoothWheel: true
    });
    bound.set(panel, l);
    made.push(l);
  }

  function sweep() {
    document.querySelectorAll(PANELS).forEach(p => {
      // Below 700px the panels hand scrolling back to the page.
      if (getComputedStyle(p).overflowY === 'visible') {
        const l = bound.get(p);
        if (l) { made.splice(made.indexOf(l), 1); l.destroy(); bound.delete(p); }
        return;
      }
      bind(p);
    });
  }

  sweep();
  // Pages are shown and hidden by [data-on], and their content arrives async.
  new MutationObserver(sweep).observe(document.body,
    { subtree: true, attributes: true, attributeFilter: ['data-on'] });
  addEventListener('resize', sweep);
  addEventListener('load', sweep);
  setTimeout(sweep, 1500);
  setTimeout(sweep, 4000);

  /* ── one raf for every instance ─────────────────────────────────────── */

  let frame = 0;
  function tick(time) {
    for (const l of made) l.raf(time);
    frame = requestAnimationFrame(tick);
  }
  frame = requestAnimationFrame(tick);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { cancelAnimationFrame(frame); frame = 0; }
    else if (!frame) frame = requestAnimationFrame(tick);
  });

  window.__lenis = { page, instances: made, panels: bound, sweep };
})();
