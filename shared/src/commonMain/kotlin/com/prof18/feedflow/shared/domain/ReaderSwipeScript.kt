package com.prof18.feedflow.shared.domain

/**
 * fork (Phase 4i): tells the app, for every finger put on the reader page,
 * whether a sideways drag from there belongs to the page: inside an element
 * that scrolls sideways (code blocks, tables), with more than one finger, or
 * while text is selected. The app moves between articles itself
 * (ReaderSwipePager) and leaves such drags to the page.
 *
 * No "$" is used in the script, so it can sit in a Kotlin raw string.
 */
internal fun readerSwipeJs(): String = """
(function () {
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

  document.addEventListener("touchstart", function (e) {
    var blocked = e.touches.length !== 1 || hasSelection() || scrollsSideways(e.target);
    if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
      window.kmpJsBridge.callNative("swipeTouch", blocked ? "blocked" : "free", {});
    }
  }, { passive: true });
})();
"""
