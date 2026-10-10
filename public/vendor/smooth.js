/* Lenis — smoothed wheel scrolling.
   Lenis needs a scroller with a single content element inside it. The page's
   scrolling panels lay their children out with flex and grid, so wrapping
   those children would collapse the layouts. It is therefore attached to the
   two scrollers that already have one child: the window, and the dialog.
   Touch is left alone deliberately (Lenis does not smooth it by default):
   phones already have momentum scrolling and overriding it feels worse. */
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

  window.__lenis = { page, instances: made };
})();
