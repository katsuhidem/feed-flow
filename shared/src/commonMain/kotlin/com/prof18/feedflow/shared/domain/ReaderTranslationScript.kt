package com.prof18.feedflow.shared.domain

/**
 * fork (Phase 3): JavaScript and CSS for the side-by-side translation in
 * reader mode. The page splits itself into blocks and inserts translations;
 * Kotlin never parses the article HTML.
 *
 * Rules of the split (docs/PLAN.md "Phase 3"):
 * - every text node outside excluded elements belongs to exactly one block
 * - an element without block-level descendants is one block
 * - in an element with block-level descendants, each run of inline content
 *   between them is its own block
 * - two or more <br> in a row end a block
 * - <pre> and inserted translations are never collected
 *
 * Messages carry a session id (one per shown article) and a run id (one
 * per translation run); results for another session or run are dropped.
 * Translations are inserted with textContent, never as HTML.
 *
 * No "$" is used in the script, so it can sit in a Kotlin raw string.
 */
internal fun readerTranslationJs(): String = """
window.feedflowTranslation = (function () {
  var BLOCK = {
    ADDRESS:1, ARTICLE:1, ASIDE:1, BLOCKQUOTE:1, CAPTION:1, DD:1, DETAILS:1, DIV:1, DL:1, DT:1,
    FIELDSET:1, FIGCAPTION:1, FIGURE:1, FOOTER:1, FORM:1, H1:1, H2:1, H3:1, H4:1, H5:1, H6:1,
    HEADER:1, HR:1, LI:1, MAIN:1, NAV:1, OL:1, P:1, PRE:1, SECTION:1, SUMMARY:1, TABLE:1,
    TBODY:1, TD:1, TFOOT:1, TH:1, THEAD:1, TR:1, UL:1
  };
  var EXCLUDED = {
    PRE:1, SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, SVG:1, MATH:1, IFRAME:1, VIDEO:1, AUDIO:1,
    BUTTON:1, SELECT:1, TEXTAREA:1, INPUT:1, CANVAS:1, OBJECT:1
  };
  var CODE = { CODE:1, KBD:1, SAMP:1, VAR:1, TT:1 };
  // Translations go inside these (a sibling would break the list or table), after everything else.
  var INSIDE = { LI:1, DT:1, DD:1, TD:1, TH:1, CAPTION:1, SUMMARY:1, FIGCAPTION:1, BLOCKQUOTE:1 };
  var TR_CLASS = "__ff_tr";

  // New for every load of the document. The app compares it to tell a real
  // reload from a repeated "page finished" notification.
  var pageToken = String(Date.now()) + "-" + String(Math.floor(Math.random() * 1e9));
  var session = null;
  var run = null;
  var anchors = {};   // block id -> { el, inside } or { last }

  function tagOf(node) { return node.nodeType === 1 ? node.tagName.toUpperCase() : ""; }

  function isExcluded(el) {
    var tag = tagOf(el);
    if (EXCLUDED[tag]) return true;
    if (el.classList && el.classList.contains(TR_CLASS)) return true;
    if (el.getAttribute && el.getAttribute("translate") === "no") return true;
    if (el.classList && el.classList.contains("notranslate")) return true;
    if (el.classList && el.classList.contains("__audio_banner")) return true;
    return false;
  }

  function hasBlockDescendant(el) {
    var all = el.getElementsByTagName("*");
    for (var i = 0; i < all.length; i++) {
      if (BLOCK[all[i].tagName.toUpperCase()]) return true;
    }
    return false;
  }

  // Appends the text of node to acc: whitespace collapsed, code spans recorded.
  function appendText(node, acc) {
    if (node.nodeType === 3) {
      var data = node.data;
      for (var i = 0; i < data.length; i++) {
        var ch = data.charAt(i);
        if (/\s/.test(ch)) { acc.space = true; } else {
          if (acc.space && acc.text.length > 0) acc.text += " ";
          acc.space = false;
          acc.text += ch;
        }
      }
      return;
    }
    if (node.nodeType !== 1 || isExcluded(node)) return;
    var tag = tagOf(node);
    if (tag === "BR") { acc.space = true; return; }
    if (CODE[tag] && !node.getElementsByTagName("*").length) {
      var code = node.textContent.replace(/\s+/g, " ").trim();
      if (code.length > 0) {
        if (acc.space && acc.text.length > 0) acc.text += " ";
        acc.space = false;
        acc.codes.push({ start: acc.text.length, text: code });
        acc.text += code;
      }
      return;
    }
    for (var c = node.firstChild; c; c = c.nextSibling) appendText(c, acc);
  }

  function isBlankText(node) { return node.nodeType === 3 && !/\S/.test(node.data); }

  function emit(blocks, nodes, anchor) {
    var acc = { text: "", codes: [], space: false };
    for (var i = 0; i < nodes.length; i++) appendText(nodes[i], acc);
    if (acc.text.length === 0) return;
    var id = blocks.length + 1;
    anchors[id] = anchor;
    blocks.push({ id: id, text: acc.text, codes: acc.codes });
  }

  // Splits a list of inline nodes at runs of two or more <br> and emits each part.
  function emitRuns(blocks, nodes, wholeElement) {
    var parts = [];
    var current = [];
    var i = 0;
    while (i < nodes.length) {
      if (tagOf(nodes[i]) === "BR") {
        var j = i, count = 0;
        while (j < nodes.length && (tagOf(nodes[j]) === "BR" || isBlankText(nodes[j]))) {
          if (tagOf(nodes[j]) === "BR") count++;
          j++;
        }
        if (count >= 2) {
          if (current.length) parts.push(current);
          current = [];
          i = j;
          continue;
        }
      }
      current.push(nodes[i]);
      i++;
    }
    if (current.length) parts.push(current);
    if (wholeElement && parts.length === 1) {
      emit(blocks, parts[0], { el: wholeElement, inside: !!INSIDE[tagOf(wholeElement)] });
      return;
    }
    for (var p = 0; p < parts.length; p++) {
      var part = parts[p];
      var last = part[part.length - 1];
      // Keep trailing blank text out of the anchor so the translation sits right under the text.
      for (var k = part.length - 1; k >= 0; k--) { if (!isBlankText(part[k])) { last = part[k]; break; } }
      emit(blocks, part, { last: last });
    }
  }

  function walk(el, blocks) {
    if (isExcluded(el)) return;
    if (!hasBlockDescendant(el)) {
      emitRuns(blocks, Array.prototype.slice.call(el.childNodes), el);
      return;
    }
    var runNodes = [];
    function flush() {
      if (runNodes.length) emitRuns(blocks, runNodes, null);
      runNodes = [];
    }
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 1 && (BLOCK[tagOf(c)] || hasBlockDescendant(c))) {
        flush();
        walk(c, blocks);
      } else if (c.nodeType === 1 || c.nodeType === 3) {
        runNodes.push(c);
      }
    }
    flush();
  }

  function translationElement(id, create) {
    var existing = document.querySelector("." + TR_CLASS + "[data-ff-block='" + id + "']");
    if (existing || !create) return existing;
    var anchor = anchors[id];
    if (!anchor) return null;
    var el = document.createElement("div");
    el.className = TR_CLASS;
    el.setAttribute("data-ff-block", String(id));
    el.setAttribute("lang", "ja");
    if (anchor.el && anchor.inside) {
      anchor.el.appendChild(el);
    } else {
      var after = anchor.el || anchor.last;
      if (!after || !after.parentNode) return null;
      after.parentNode.insertBefore(el, after.nextSibling);
    }
    return el;
  }

  return {
    // Reports which document this is.
    ping: function () {
      if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
        window.kmpJsBridge.callNative("translationPage", pageToken, {});
      }
      return pageToken;
    },
    // Collects the blocks of the article and reports them to the app.
    collect: function (sessionId) {
      session = sessionId;
      run = null;
      anchors = {};
      var old = document.querySelectorAll("." + TR_CLASS);
      for (var i = 0; i < old.length; i++) old[i].parentNode.removeChild(old[i]);
      var blocks = [];
      var title = document.querySelector("body > h1");
      if (title && !isExcluded(title)) emitRuns(blocks, Array.prototype.slice.call(title.childNodes), title);
      var content = document.getElementById("__content");
      if (content) {
        var siteName = content.querySelector(":scope > h4:first-child");
        if (siteName) siteName.setAttribute("translate", "no");
        walk(content, blocks);
      }
      var message = JSON.stringify({ session: sessionId, blocks: blocks });
      if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
        window.kmpJsBridge.callNative("translationBlocks", message, {});
      }
      return message;
    },
    beginRun: function (sessionId, runId) {
      if (sessionId !== session) return false;
      run = runId;
      return true;
    },
    // Shows text under block id. partial marks text that is still being generated.
    apply: function (sessionId, runId, id, text, partial) {
      if (sessionId !== session || runId !== run) return false;
      var el = translationElement(id, true);
      if (!el) return false;
      el.textContent = text;
      if (partial) el.classList.add(TR_CLASS + "_partial"); else el.classList.remove(TR_CLASS + "_partial");
      return true;
    },
    // Removes the translation of one block (a partial text of a run that failed or was cancelled).
    remove: function (sessionId, runId, id) {
      if (sessionId !== session || runId !== run) return false;
      var el = translationElement(id, false);
      if (el) el.parentNode.removeChild(el);
      return true;
    },
    clear: function (sessionId) {
      if (sessionId !== session) return false;
      var all = document.querySelectorAll("." + TR_CLASS);
      for (var i = 0; i < all.length; i++) all[i].parentNode.removeChild(all[i]);
      return true;
    }
  };
})();
"""

/** Translation blocks: a quieter colour and a rule on the left, so they read as an aside to the original. */
internal fun readerTranslationCss(): String = """
      .__ff_tr {
        display: block;
        margin: 0.35em 0 1em 0;
        padding-left: 0.7em;
        border-left: 3px solid rgba(128, 128, 128, 0.55);
        opacity: 0.88;
        font-weight: normal;
        font-style: normal;
        text-align: start;
      }
      li > .__ff_tr, td > .__ff_tr, th > .__ff_tr, dd > .__ff_tr, dt > .__ff_tr {
        margin: 0.25em 0 0.35em 0;
      }
      h1 + .__ff_tr, h2 + .__ff_tr, h3 + .__ff_tr, h4 + .__ff_tr, h5 + .__ff_tr, h6 + .__ff_tr {
        font-weight: bold;
      }
      .__ff_tr.__ff_tr_partial {
        opacity: 0.55;
      }
"""
