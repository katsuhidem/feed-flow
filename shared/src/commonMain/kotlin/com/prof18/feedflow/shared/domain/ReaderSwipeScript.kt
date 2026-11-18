package com.prof18.feedflow.shared.domain

/**
 * fork (Phase 4f): moving between articles by swiping on the reader page.
 *
 * The page follows the finger while it moves sideways. When the finger is
 * lifted far enough (or flicked), the page slides out and the app is told
 * through the JS bridge ("articleSwipe" with "next" or "previous"); the next
 * page then slides in from the other side ([readerSwipeEnterHtml]). Otherwise
 * the page springs back. Swiping left (finger moving right to left) means
 * next, like turning a page. Where there is no article in that direction
 * (the app reports it through `setAvailable`), the page only gives a little.
 *
 * Not a swipe: a touch that starts near the left or right edge (the system
 * back gesture), inside an element that scrolls sideways (code blocks,
 * tables), with two fingers, or while text is selected; a movement that is
 * vertical first. See docs/PLAN.md "Phase 4f".
 *
 * No "$" is used in the script, so it can sit in a Kotlin raw string.
 */
internal fun readerSwipeJs(): String = """
window.feedflowSwipe = (function () {
  var EDGE_PX = 24;
  var LOCK_PX = 10;
  var MIN_FRACTION = 0.22;
  var MIN_PX = 70;
  var FLICK_PX_PER_MS = 0.5;
  var FLICK_MIN_PX = 40;
  var ANIM_MS = 200;
  var RESIST = 0.25;
  var available = { previous: false, next: false };
  var s = null;
  var busy = null;   // the direction the page slid out to, until another page replaces this one
  var comeBack = null;

  function setX(x, animate) {
    var b = document.body;
    b.style.transition = animate ? "transform " + ANIM_MS + "ms ease-out" : "none";
    b.style.transform = x ? "translateX(" + x + "px)" : "";
  }

  function scrollsSideways(el) {
    for (var n = el; n && n !== document.body && n.nodeType === 1; n = n.parentNode) {
      if (n.scrollWidth > n.clientWidth + 1) {
        var style = window.getComputedStyle(n);
        if (style.overflowX === "auto" || style.overflowX === "scroll") return true;
      }
      var tag = n.tagName.toUpperCase();
      if (tag === "PRE" || tag === "TABLE" || tag === "IFRAME" || tag === "VIDEO") return true;
    }
    return false;
  }

  function hasSelection() {
    var sel = window.getSelection && window.getSelection();
    return !!sel && !sel.isCollapsed && String(sel).length > 0;
  }

  function cancel() {
    if (s && s.dragging) setX(0, true);
    s = null;
  }

  document.addEventListener("touchstart", function (e) {
    s = null;
    if (busy || e.touches.length !== 1 || hasSelection()) return;
    var t = e.touches[0];
    if (t.clientX < EDGE_PX || t.clientX > window.innerWidth - EDGE_PX) return;
    if (scrollsSideways(e.target)) return;
    var now = Date.now();
    s = { x: t.clientX, y: t.clientY, dragging: false, lastX: t.clientX, lastT: now, vx: 0 };
  }, { passive: true });

  document.addEventListener("touchmove", function (e) {
    if (!s) return;
    if (e.touches.length !== 1) { cancel(); return; }
    var t = e.touches[0];
    var dx = t.clientX - s.x;
    var dy = t.clientY - s.y;
    if (!s.dragging) {
      if (Math.abs(dy) > LOCK_PX && Math.abs(dy) >= Math.abs(dx)) { s = null; return; }
      if (Math.abs(dx) <= LOCK_PX || Math.abs(dx) <= Math.abs(dy) * 1.2) return;
      if (hasSelection()) { s = null; return; }
      s.dragging = true;
    }
    if (e.cancelable) e.preventDefault();
    var now = Date.now();
    if (now > s.lastT) { s.vx = (t.clientX - s.lastX) / (now - s.lastT); s.lastX = t.clientX; s.lastT = now; }
    var allowed = dx < 0 ? available.next : available.previous;
    setX(allowed ? dx : dx * RESIST, false);
  }, { passive: false });

  document.addEventListener("touchend", function (e) {
    var g = s;
    s = null;
    if (!g || !g.dragging) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - g.x;
    var dir = dx < 0 ? "next" : "previous";
    var far = Math.abs(dx) >= Math.max(MIN_PX, window.innerWidth * MIN_FRACTION);
    var flick = Math.abs(g.vx) > FLICK_PX_PER_MS && Math.abs(dx) > FLICK_MIN_PX && (g.vx < 0) === (dx < 0);
    if (!(dx < 0 ? available.next : available.previous) || !(far || flick)) { setX(0, true); return; }
    busy = dir;
    // Tell the app at once, so loading the next article overlaps the slide-out.
    if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
      window.kmpJsBridge.callNative("articleSwipe", dir, {});
    }
    setX(dx < 0 ? -window.innerWidth : window.innerWidth, true);
    // If no other page replaces this one, come back.
    comeBack = setTimeout(back, 2000 + ANIM_MS);
  }, { passive: true });

  document.addEventListener("touchcancel", cancel, { passive: true });

  function back() {
    if (comeBack) clearTimeout(comeBack);
    comeBack = null;
    busy = null;
    setX(0, true);
  }

  // A page opened by a swipe starts outside the screen (readerSwipeEnterHtml) and slides in.
  var root = document.documentElement;
  var from = root.classList.contains("__ff_enter_right") ? "right" : root.classList.contains("__ff_enter_left") ? "left" : null;
  if (from) {
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        document.body.style.transition = "transform " + ANIM_MS + "ms ease-out";
        root.classList.remove("__ff_enter_right", "__ff_enter_left");
        setTimeout(function () { document.body.style.transition = ""; }, ANIM_MS + 50);
      });
    });
  }

  return {
    setAvailable: function (previous, next) {
      available.previous = !!previous;
      available.next = !!next;
      // The app found nothing in that direction after all: come back at once.
      if (busy && !available[busy]) back();
    },
    // The app did not move to another article.
    back: back
  };
})();
"""

/** Styles for the slide-in of a page opened by a swipe. */
internal fun readerSwipeCss(): String = """
      html.__ff_enter_right body { transform: translateX(100vw); }
      html.__ff_enter_left body { transform: translateX(-100vw); }
"""

/**
 * Put at the start of the body of a page opened by a swipe, so it is drawn
 * outside the screen from the first frame and slides in.
 *
 * @param fromRight true when the page comes in from the right (the next article)
 */
fun readerSwipeEnterHtml(fromRight: Boolean): String =
    "<script>document.documentElement.classList.add(\"" +
        (if (fromRight) "__ff_enter_right" else "__ff_enter_left") + "\");</script>"
