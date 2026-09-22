package com.chmouel.liseur.reader

import android.os.SystemClock
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Typesets the mathematics a book wrote in TeX, and makes the parts of it
 * wider than the page move instead of being cut off.
 *
 * EPUB has no one way to say "formula". A book converted from LaTeX ships
 * `$$…$$` and `\(…\)` as literal text — which is why a mathematics book in
 * a reader that knows nothing of it shows a page of backslashes — or a
 * MathJax payload whose own script has to be fetched from a network the
 * reader may not have, or MathML, which the web view draws by itself. This
 * handles the first two of those with KaTeX, bundled in `assets/katex`, and
 * leaves MathML alone apart from making it pannable.
 *
 * Injected at runtime, like [WideContentFit], for the same reason: Readium
 * decides whether to link its own stylesheet by looking for `<style` in the
 * resource, so anything shipped ahead of it would cost an unstyled book its
 * typography. It is also the only way to reach a book that is not on disk
 * but streamed from a server.
 *
 * KaTeX is loaded from the app's own assets through Readium's asset server —
 * `https://readium_assets/…`, which is what `servedAssets` makes reachable
 * and which answers with `Access-Control-Allow-Origin: *`. A `<link>` and
 * two `<script>` tags are enough; nothing is evaluated from a string, so a
 * strict `script-src` costs nothing beyond the maths not appearing.
 *
 * A formula that is too wide is the point of the second half of the work.
 * Paginated reflow *does* break a long line across pages, so a reader who
 * turns to the next page finds the tail of an equation there, with nothing
 * to say where it went. Making the box scroll keeps one formula on one
 * page, which is what a formula is — and gives the reader a way to see all
 * of it, which is [FormulaPan].
 *
 * Ownership is a token minted per document, exactly as in
 * [WideContentFit]: a publisher is free to ship `data-liseur-pan`, and only
 * attributes carrying this document's token are ever read or written.
 */
internal object MathTypesetting {
    internal enum class Result {
        /** Something was rendered or marked: the page may have moved. */
        CHANGED,

        /** Nothing here to typeset, and nothing marked; the layout is untouched. */
        EMPTY,

        /** KaTeX is there and everything is already rendered and marked. */
        DONE,

        /** The assets were asked for and have not answered yet: ask again. */
        LOADING,

        /** The book's Content-Security-Policy refused what was needed. */
        BLOCKED,

        /** No reflowable page to talk to, or the script did not answer. */
        FAILED,
    }

    /**
     * How long to keep asking before giving up on a page that answers
     * `loading`.
     *
     * Bounded because this runs inside a reflow scope, where the reader's
     * place is held: waiting forever on a web view that has stopped
     * answering would hold a page that never settles. Reading from the app's
     * own storage, the files arrive in a few tens of milliseconds.
     *
     * The render is sliced now, and a slice chain answers `loading` until
     * the last formula is down — a dense chapter on a slow phone is seconds
     * of it. Giving up part-way through those seconds would be the one
     * outcome worse than waiting: the reflow scope closes, the chain goes on
     * moving the page underneath it, and the navigator reads that as a page
     * turn the reader never made, so their place is never put back. The
     * budget is therefore sized for a whole render as well as for the load,
     * and a page that stops answering altogether still costs only this wait.
     */
    const val LOAD_BUDGET_MS = 20_000L

    /** How often to ask while the answer is still pending. */
    const val POLL_MS = 80L

    /**
     * How long a render chain may go without running a slice before it is
     * taken to be dead rather than merely slow, and a fresh one is armed.
     *
     * A slice yields the thread every [SLICE_MS] at most, so a chain that is
     * alive refreshes its stamp on that cadence; a stall several times longer
     * can only mean the chain was abandoned between slices. Set well above
     * [POLL_MS] so the common case — a slow device grinding through a dense
     * chapter one slice at a time — is never mistaken for a dead one and
     * restarted into a second chain racing the first.
     */
    const val RENDER_STALL_MS = 400L

    /** The render hands the thread back after this much work per slice. */
    const val SLICE_MS = 24L

    /**
     * `$` cannot be written plainly inside the raw script below, so the
     * TeX delimiters are assembled around this.
     */
    private const val DOLLARS = "$$"

    /**
     * Runs in the book's own document, repeatedly, so it must end where it
     * started: a second pass over an already-typeset chapter has to answer
     * `done`, or every layout pass would move the reader's place.
     */
    val SCRIPT: String = """
        (function () {
          try {
            var st = window.__liseurMath || (window.__liseurMath = {});
            if (!st.tok) {
              st.tok = 'm' + Math.random().toString(36).slice(2, 10);
              st.attr = 'data-liseur-pan-' + st.tok;
              st.pure = 'data-liseur-pan-pure-' + st.tok;
              st.hide = 'data-liseur-mx-' + st.tok;
              st.wide = 'data-liseur-pan-wide-' + st.tok;
              st.state = 'scan';
            }
            var ATTR = st.attr, PURE = st.pure, HIDE = st.hide, TOK = st.tok;
            var WIDE = st.wide;

            // A cheap fingerprint of what is actually in the document right
            // now. An `empty` answer means "there is no mathematics in *this*
            // content", and this is what ties it to that content: when the
            // body's structure changes underneath a latched `empty` — a mode
            // switch or chapter swap leaving the same `window` holding a body
            // that was still being filled when we looked — the latch has to
            // let go and look again. It is a child count, not the text: O(1),
            // and a settled page's is constant, so a genuinely maths-free
            // chapter keeps its answer instead of re-scanning every pass.
            function sig() {
              return document.body ? document.body.childElementCount : -1;
            }

            // Which boxes are wider than the space they have.
            //
            // The box asked about is the *block* the maths sits in, not the
            // formula itself. A display formula renders to a block of its own
            // anyway, so that case is unchanged; what it adds is the line of
            // prose with a formula run into it, which cannot wrap and so
            // makes its paragraph wider than the page. Box the formula alone
            // there and the paragraph goes on pushing the whole document
            // sideways — the page sliding under a thumb that was only trying
            // to read one equation. Box the paragraph and the overflow has
            // nowhere left to escape to.
            function blockOf(el) {
              var n = el;
              while (n && n !== document.body && n !== document.documentElement) {
                var d = '';
                try { d = getComputedStyle(n).display || ''; } catch (e) {}
                // KaTeX's own stylesheet sets `display: block` on the
                // `.katex` span inside a `.katex-display`, which would
                // otherwise stop this walk at the formula itself. A box
                // that tight is the wrong thing to hand a reader: the
                // painted formula overhangs it — the rule over a radical,
                // the head of a superscript — and a scroll box clips what
                // sticks out of it, which reads as glyphs with their tops
                // missing. Climb over that one span to the display wrapper
                // (or the paragraph), which carries the same ink inside
                // margins of its own; only the exact `.katex` span is
                // skipped, so every other block still stops the walk where
                // it always did.
                var cls = '';
                if (typeof n.className === 'string') cls = n.className;
                var tight = /(^|\s)katex($|\s)/.test(cls);
                if (!tight && (d.indexOf('block') === 0 || d === 'flex' || d === 'grid' ||
                    d === 'table' || d === 'flow' || d === 'flow-root' ||
                    n.tagName.toLowerCase() === 'math')) {
                  return n;
                }
                n = n.parentElement;
              }
              return null;
            }

            // The width one line of this page has to work with.
            //
            // The page's own content box, not the viewport: Readium pads the
            // body by the reader's page margin, and a formula that fits the
            // viewport but not the margin is still a formula that does not
            // fit. `clientWidth` counts the padding in, so it is taken back
            // out here. The column case comes out the same way: a column is a
            // fragment of the body, so the body's content box is the column.
            function pageWidth() {
              var b = document.body;
              if (!b) return 0;
              var s = getComputedStyle(b);
              var w = b.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight);
              if (!(w > 0)) w = document.documentElement.clientWidth;
              return w;
            }

            // Whether a box holds nothing but mathematics.
            //
            // This is the difference between a display formula and a line of
            // prose with a formula run into it, and it decides what may be
            // done to the box. A formula's box can be forced left-to-right and
            // asked not to break across a page column, because it is one
            // thing. A paragraph cannot: an Arabic book's prose would come out
            // the wrong way round, and a paragraph too tall for a column that
            // refuses to break leaves a page blank.
            function pureMath(box) {
              var kids = box.childNodes, seen = 0;
              for (var c = 0; c < kids.length; c++) {
                var k = kids[c];
                if (k.nodeType === 3) { if ((k.data || '').trim()) return false; continue; }
                if (k.nodeType !== 1) continue;
                var cls = (k.getAttribute && k.getAttribute('class')) || '';
                var tag = k.tagName.toLowerCase();
                // The parts KaTeX itself writes — the painted glyphs and the
                // MathML copy beside them — are as much the formula as the
                // `.katex` box that holds them, and the box being asked about
                // is often that `.katex` rather than the display wrapper.
                var maths = tag === 'math' || tag === 'mjx-container' ||
                  /(^| )katex(-[\w]+)?($| )/.test(cls) ||
                  !!(k.querySelector && k.querySelector('math, .katex, mjx-container'));
                if (maths) { seen++; continue; }
                if ((k.textContent || '').trim()) return false;
              }
              return seen > 0;
            }

            function mark() {
              // `.katex` is in the list because an inline formula is the case
              // that escapes a display-only sweep: it cannot wrap, so it makes
              // its *paragraph* too wide for the page, and a page like that
              // scrolls sideways as a whole. The hidden `<math>` copy KaTeX
              // writes beside it is dropped by the check below, so this asks
              // about each rendered formula exactly once.
              var els = document.querySelectorAll('.katex, math, mjx-container');
              var found = [], i, el;
              var page = pageWidth();
              for (i = 0; i < els.length; i++) {
                el = els[i];
                // KaTeX writes the formula twice: the glyphs it paints, and a
                // `<math>` copy clipped to a pixel that a screen reader reads
                // out of. The copy is inside the rendered box, which is the
                // box a reader can see and drag; marking the copy as well
                // would hand out a scroll box one pixel wide. Anything
                // *inside* a rendered formula is therefore left alone — the
                // ancestor is asked about rather than the element itself, so
                // the rendered formula, which is the one being asked about,
                // is not its own ancestor. A book's own MathML, which has no
                // copy beside it, survives this.
                if (el.parentElement && el.parentElement.closest &&
                    el.parentElement.closest('.katex')) continue;
                var box = blockOf(el);
                // No block of its own to box, or the document itself: either
                // way there is nothing here to hand a drag to. The second is
                // the important one — boxing the root would make the whole
                // page the pannable thing.
                if (!box) continue;
                // Wider than the page, not wider than whatever box it landed
                // in. `scrollWidth - clientWidth` is blind to the case that
                // matters most here: a formula inside something the browser
                // sized to fit its content — a table cell, a flex item, an
                // `inline-block` — never overflows *that*, because that grew
                // to hold it. What grew is the page: the column the text is
                // laid into is widened by its widest nowrap line, and then
                // every other line on that page is cut at both edges, which
                // is a whole page of unreadable prose caused by one formula.
                // `scrollWidth` is the honest measure of what has to fit, so
                // it is the one compared against the page.
                if ((box.scrollWidth > page + 1 || box.scrollWidth - box.clientWidth > 1) &&
                    found.indexOf(box) < 0) {
                  found.push(box);
                }
              }
              // Of those, only the innermost ones. A chapter that wrapped its
              // formulas in a block of its own — a div sized to the widest
              // thing in it — reports that wrapper as overflowing too, and a
              // reader dragging one formula would find the page around it
              // moving as well. The formula is what was dragged; the wrapper
              // is what merely contains it, and it goes back to being an
              // ordinary part of the page.
              var changed = 0;
              // The cap's width, as a custom property on the root — the one
              // place a value reaches every box below it by inheritance alone.
              // Written only when it has actually changed, so a settled page
              // does no style work and, more to the point, keeps its answer:
              // Readium sets its own mode on the root's style attribute and
              // reads it back, and there is no reason to touch that on every
              // pass of a page that has stopped moving.
              var rootEl = document.documentElement;
              if (rootEl.style.getPropertyValue('--liseur-pan-w') !== page + 'px') {
                rootEl.style.setProperty('--liseur-pan-w', page + 'px');
                changed++;
              }
              for (i = 0; i < found.length; i++) {
                el = found[i];
                var outer = false;
                for (var j = 0; j < found.length; j++) {
                  if (i !== j && el.contains(found[j])) { outer = true; break; }
                }
                if (outer) {
                  if (el.getAttribute(ATTR) === TOK) el.removeAttribute(ATTR);
                  if (el.getAttribute(PURE) === TOK) el.removeAttribute(PURE);
                  if (el.getAttribute(WIDE) === TOK) el.removeAttribute(WIDE);
                  continue;
                }
                if (el.getAttribute(ATTR) !== TOK) { el.setAttribute(ATTR, TOK); changed++; }
                // Whether the box may be treated as a formula rather than as
                // text: re-read every pass, because a paragraph that gained a
                // second formula, or a font size that changed what fits in it,
                // changes the answer.
                var isPure = pureMath(el);
                if ((el.getAttribute(PURE) === TOK) !== isPure) {
                  if (isPure) el.setAttribute(PURE, TOK); else el.removeAttribute(PURE);
                  changed++;
                }
                // A box the page had to widen for: capped to the page width so
                // the column can come back. The cap is taken off before the
                // measurement and put back after, because a capped box reports
                // the width we imposed on it as its own — measure with it on
                // and every following pass would decide the opposite and the
                // page would breathe. Same reason [WideContentFit] clears its
                // marker before measuring.
                var hadCap = el.getAttribute(WIDE) === TOK;
                if (hadCap) el.removeAttribute(WIDE);
                var needsCap = el.clientWidth > page + 1;
                if (needsCap) el.setAttribute(WIDE, TOK);
                if (needsCap !== hadCap) changed++;
              }
              // A box marked by an earlier pass that no page now wants — the
              // font size changed and the formula fits, say — is unmarked, or
              // it would stay pannable for a formula that no longer needs it.
              var stale = document.querySelectorAll('[' + ATTR + ']');
              for (i = 0; i < stale.length; i++) {
                if (found.indexOf(stale[i]) < 0) {
                  stale[i].removeAttribute(ATTR);
                  stale[i].removeAttribute(PURE);
                  stale[i].removeAttribute(WIDE);
                  changed++;
                }
              }
              // A cap whose box this pass no longer wants: the width changed
              // and the box fits now. Separately from the loop above, because a
              // box can be left marked while no longer needing the cap — a
              // paragraph that fits its own formula, say.
              var capped = document.querySelectorAll('[' + WIDE + ']');
              for (i = 0; i < capped.length; i++) {
                if (found.indexOf(capped[i]) < 0) {
                  capped[i].removeAttribute(WIDE);
                  changed++;
                }
              }
              return changed ? 'changed' : 'done';
            }

            // The rules the marked boxes need, and which nothing else here
            // supplies. The scrollbar goes: it is 6px of a page a formula did
            // not ask for, and the reader is dragging the formula itself, not
            // a bar. Everything else is split by what the box holds.
            function ownCss() {
              if (st.styleEl && st.styleEl.isConnected) return true;
              var css = document.createElement('style');
              css.textContent =
                // Any pannable box: it scrolls sideways and nothing else, and
                // it stops the movement bubbling out to the page once it has
                // run out — which is what keeps a sideways drag over a formula
                // from taking the whole document with it.
                '[' + ATTR + ']{overflow-x:auto!important;overflow-y:hidden!important;' +
                'scrollbar-width:none;-webkit-overflow-scrolling:touch;' +
                'overscroll-behavior-x:contain}' +
                '[' + ATTR + ']::-webkit-scrollbar{width:0;height:0}' +
                // A box holding nothing but mathematics is further given the
                // rules a *formula* wants and a paragraph must not have.
                //
                // Left-aligned, because centring an overflowing box parks half
                // of it past the left edge, where a reader scrolling rightwards
                // can never reach it.
                //
                // Forced left-to-right, because it is a viewport onto
                // left-to-right material whatever the prose around it runs: an
                // inherited `direction: rtl` makes a centred nowrap overflow
                // sit to the left with the scroll offset negative, and a reader
                // dragging rightwards would find the whole formula already out
                // of reach. A paragraph with prose in it is never forced this
                // way — an Arabic book's text would come out reversed.
                //
                // Told not to break across a page column, because one formula
                // is one thing; a paragraph too tall for a column that refused
                // to break would leave a page blank.
                '[' + PURE + ']{text-align:left!important;direction:ltr!important;' +
                'break-inside:avoid;-webkit-column-break-inside:avoid}' +
                '[' + PURE + '] .katex,[' + PURE + '] .katex-display{text-align:left!important}' +
                // Room for what a formula paints outside its own box. Even
                // boxed at the display wrapper, the radical's rule and the
                // head of a superscript are painted a few pixels above and
                // below it, and `overflow-y: hidden` — which a sideways
                // scroller always carries, `clip` computing to it beside a
                // scrolling axis, and `overflow-clip-margin` needing a clip
                // axis it never gets here — cuts that ink off, which is what
                // reads as glyphs with their tops missing.
                //
                // Padding grows the clip rect, and the pair of offsets keeps
                // everything else exactly where it was: the box is painted
                // half a line back up (`position: relative`, which moves
                // paint and clip together but no one's layout), and the
                // padding's full height is paid back by a negative bottom
                // margin — the one margin a collapse cannot silently absorb,
                // since negative margins always count. Measured against the
                // unmarked page: no glyph moves, no following block moves,
                // the drag still reaches the whole formula.
                '[' + PURE + ']{padding-block:0.6em!important;position:relative!important;' +
                'top:-0.6em;margin-bottom:-1.2em!important}' +
                // A box the page had to be widened for.
                //
                // Marking alone is not enough when the box was never narrower
                // than its content: a formula inside a table cell, a flex
                // item, or an `inline-block` sits in a box the browser grew to
                // hold it, so the box has nothing to scroll — but the column
                // the text is laid into has been widened to the same measure,
                // and every other line on the page is now cut at both ends.
                // Capping the box at the page's own width is what gives the
                // widest line somewhere to scroll and lets the column come
                // back. The width is a custom property on the root, set from
                // the measured page on every pass, so the box is never given a
                // width in a unit that stops being true when the reader
                // changes the font size or turns the device.
                ':root{--liseur-pan-w:0px}' +
                '[' + WIDE + ']{width:var(--liseur-pan-w)!important;' +
                'max-width:var(--liseur-pan-w)!important;box-sizing:border-box!important}' +
                // The source a book left beside a rendered formula, hidden
                // through a token-owned attribute rather than by writing a
                // style onto the author's own element.
                '[' + HIDE + ']{display:none!important}';
              document.head.appendChild(css);
              if (!css.sheet) return false;
              st.styleEl = css;
              return true;
            }

            // `empty` is a claim about the content that was scanned, not a
            // life sentence for the window. A mode switch re-lays a chapter
            // inside the SAME web view, and the first pass can land while the
            // body is `complete` but not yet filled — latching the window
            // against content that was never there. When the body's structure
            // no longer matches what was latched, this is not the chapter that
            // was called empty: drop back and scan it for real, right here in
            // this pass.
            if (st.state === 'empty' && st.sig !== sig()) st.state = 'scan';

            if (st.state === 'scan') {
              var mathy = false, texy = false;
              if (document.querySelector('math, .katex, mjx-container')) mathy = true;
              if (document.querySelector(
                    '.MathJax, .MathJax_Display, .MathJax_Preview, script[type^="math/"]')) {
                texy = true;
              }
              if (!texy) {
                var txt = (document.body && document.body.textContent) || '';
                if (txt.indexOf('@DOLLARS@') >= 0 || txt.indexOf('\\(') >= 0 ||
                    txt.indexOf('\\[') >= 0 || txt.indexOf('\\begin{') >= 0) texy = true;
              }
              if (!mathy && !texy) {
                // Latched only once the document is finished. Before that a
                // no is a page still loading, and the reader's place is only
                // asked about once per resource — so a no remembered too
                // early costs this chapter its maths until it is come to
                // again. 'loading' is the answer that makes it asked once
                // more, and apply() is what bounds the wait.
                if (document.readyState !== 'complete') return 'loading';
                st.state = 'empty';
                st.sig = sig();
                return 'empty';
              }
              // MathML the web view can already draw needs no library, only
              // a way to be got at.
              if (!texy) {
                st.state = 'marked';
                return ownCss() ? mark() : 'blocked';
              }
              var base = 'https://readium_assets/katex/';
              // Whether each of them arrived is not worth tracking
              // separately: if the library itself came through, the maths
              // can be rendered, and if it did not there is nothing to do
              // but say so.
              var left = 3;
              function settled() {
                left--;
                if (left > 0 || st.state !== 'loading') return;
                st.state = window.katex ? 'ready' : 'blocked';
              }
              function wire(el) {
                el.onload = settled;
                el.onerror = settled;
              }
              var link = document.createElement('link');
              link.rel = 'stylesheet';
              link.href = base + 'katex.min.css';
              var lib = document.createElement('script');
              lib.src = base + 'katex.min.js';
              var scan = document.createElement('script');
              scan.src = base + 'auto-render.min.js';
              // A script made with createElement is async by default, which
              // means the two of them run in whichever order they arrive —
              // and the scanner reads the library off the window *when it
              // itself runs*, so arriving first leaves it holding nothing and
              // every formula then fails on an undefined ParseError. Off here
              // is the whole fix: they run in the order written.
              lib.async = false;
              scan.async = false;
              wire(link); wire(lib); wire(scan);
              st.state = 'loading';
              document.head.appendChild(link);
              document.head.appendChild(lib);
              document.head.appendChild(scan);
              return 'loading';
            }

            // (An `empty` whose content has since changed was already handed
            // back to the scan branch above; only a matching signature
            // reaches here.)
            if (st.state === 'empty') return 'empty';
            if (st.state === 'loading') return 'loading';
            if (st.state === 'blocked') return 'blocked';
            // The render is a chain of timer slices that outlives the pass
            // that armed it. It is also, unlike everything else here, driven
            // from inside the page rather than from a caller — so a pass that
            // meets it mid-flight must neither abandon it nor start a second
            // copy racing the first. `st.rendering` says a chain was armed;
            // `st.alive` is when that chain last ran a slice. A chain whose
            // slices are still landing updates `alive` every few milliseconds;
            // one that died — abandoned by a pass that gave up on it, or lost
            // to a re-entry that landed in the gap between two slices — goes
            // quiet. Reaching this line with a quiet chain means the render is
            // unfinished but nothing is driving it any more, so this pass arms
            // a fresh one rather than reporting a page half of whose maths is
            // still literal TeX. A chain that is merely slow keeps its
            // `alive` fresh and is left running untouched.
            if (st.rendering && st.state === 'ready') {
              if (Date.now() - st.alive < @STALL@) return 'loading';
              st.rendering = false;
            }
            if (st.state === 'fonts') {
              if (document.fonts.status !== 'loaded') return 'loading';
              st.state = 'marked';
            }
            if (st.state === 'marked') {
              var mk = ownCss() ? mark() : 'blocked';
              // A chapter whose literal TeX just became formulas moved the
              // page even where every one of them fits: the mark pass would
              // answer `done` and the reader's place would never be put
              // back. The chain below records that a render happened; the
              // first `done` after it is paid out as `changed`, once.
              if (st.pendingChanged) {
                st.pendingChanged = false;
                if (mk === 'done') return 'changed';
              }
              return mk;
            }

            if (st.state === 'ready') {
              if (!ownCss()) { st.state = 'blocked'; return 'blocked'; }
              if (!st.rendering) {
                // One long task here — scan the chapter, render every
                // formula, measure — costs a slow device a couple of
                // seconds of frozen page, and a reader who turned into
                // the chapter lands inside it. The same work as a chain
                // of short timer tasks costs the page turn nothing it
                // cannot interleave with, and it lets the browser do the
                // layout the render invalidated on its own frame instead
                // of the measuring pass forcing a whole document's
                // re-layout inside a single task — which was most of the
                // old one-shot cost.
                st.rendering = true;
                st.alive = Date.now();
                var before = document.querySelectorAll('.katex').length;
                var opts = {
                  // `@DOLLAR@` on its own is left alone: a book that
                  // prices anything in dollars would otherwise have its
                  // prose turned into broken maths.
                  delimiters: [
                    {left: '@DOLLARS@', right: '@DOLLARS@', display: true},
                    {left: '\\[', right: '\\]', display: true},
                    {left: '\\(', right: '\\)', display: false},
                    {left: '\\begin{equation}', right: '\\end{equation}', display: true},
                    {left: '\\begin{align}', right: '\\end{align}', display: true},
                    {left: '\\begin{alignat}', right: '\\end{alignat}', display: true},
                    {left: '\\begin{gather}', right: '\\end{gather}', display: true},
                    {left: '\\begin{CD}', right: '\\end{CD}', display: true}
                  ],
                  ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code'],
                  // A formula that cannot be parsed is printed as the text
                  // it is rather than as red error markup, which a reader
                  // would read as part of the book.
                  throwOnError: false,
                  // KaTeX's own default, and kept on purpose: the MathML
                  // beside the painted glyphs is clipped to 1px rather than
                  // hidden, which is what a screen reader reads a formula
                  // out of. Rendering HTML only would make the maths
                  // invisible to anyone not looking at it.
                  output: 'htmlAndMathml'
                };
                // The units rendered one at a time are the blocks under
                // the body. A chapter that keeps everything in one
                // wrapper is stepped through it — the point is a list
                // long enough to cut up — and a formula never spans two
                // of these blocks anyway: auto-render only ever finds a
                // `\(…\)` pair inside one text run within one element.
                var list = document.body;
                if (list) {
                  var solo = list;
                  while (solo.children.length === 1) {
                    solo = solo.children[0];
                    if (solo.children && solo.children.length > 1) list = solo;
                  }
                }
                var blocks = [], bi;
                if (list) for (bi = 0; bi < list.children.length; bi++) blocks.push(list.children[bi]);
                // Render where the reader is looking, not where the chapter
                // begins. The chain walks one block per slice, so a flat
                // document-order walk leaves a reader who has just flung the
                // page down staring at raw TeX at their feet while the engine
                // typesets the screens they scrolled past on the way. So the
                // queue is ordered by distance from the viewport, and while
                // it runs it is re-ordered whenever the reader moves a full
                // viewport since the last sort — a fling or a jump pulls the
                // un-rendered blocks toward wherever they have landed.
                //
                // Distance is measured off the browser's own settled layout
                // (`getBoundingClientRect`) and needs no idea which way the
                // page scrolls: it is how far a block sits outside the
                // viewport box, and a block the reader can see is at zero.
                // The rest fall away by how far they are off it, on whichever
                // axis they were left. Nothing here forces a re-flow the
                // slice would trigger anyway.
                function dist(el) {
                  var r;
                  try { r = el.getBoundingClientRect(); } catch (e) { return 1e12; }
                  if (r.width === 0 && r.height === 0) return 1e9;
                  var W = window.innerWidth || document.documentElement.clientWidth;
                  var H = window.innerHeight || document.documentElement.clientHeight;
                  // Gap from the block to the viewport box on each axis, zero
                  // where they overlap; the block's squared distance off the
                  // box. A block fully on screen is 0 and sorts first.
                  var hg = Math.max(0, r.left - W, -r.right);
                  var vg = Math.max(0, r.top - H, -r.bottom);
                  return hg * hg + vg * vg;
                }
                function sortNear(from) {
                  // Only the not-yet-rendered tail is reordered; the blocks
                  // already painted stay put and their slot in the walk is
                  // spent. A block that failed to measure sorts last, which is
                  // right — it is not the one to render before the reader's own
                  // eyes.
                  var rest = blocks.slice(from);
                  rest.sort(function (a, b) { return dist(a) - dist(b); });
                  for (var s = 0; s < rest.length; s++) blocks[from + s] = rest[s];
                }
                sortNear(0);
                var i = 0, srcs = null, q = 0, swept = false;
                var lastSort = (window.scrollY || 0) + (window.scrollX || 0);

                function finish() {
                  // Whole-body rendering, which is what this replaced, also
                  // saw any TeX written loose between the chapter's blocks.
                  // The per-block chain does not — auto-render only walks
                  // inside the element it is given — so the odd text node
                  // under the body gets one last pass, looked for by name
                  // first so the clean case costs a walk rather than a
                  // render over the whole document again.
                  if (!swept && document.body) {
                    swept = true;
                    var kids = document.body.childNodes, k, stray = false;
                    for (k = 0; k < kids.length; k++) {
                      if (kids[k].nodeType !== 3) continue;
                      var dt = kids[k].data || '';
                      if (dt.indexOf('@DOLLARS@') >= 0 || dt.indexOf('\\(') >= 0 ||
                          dt.indexOf('\\[') >= 0 || dt.indexOf('\\begin{') >= 0) stray = true;
                    }
                    if (stray && window.renderMathInElement) {
                      try { window.renderMathInElement(document.body, opts); } catch (e5) {}
                    }
                  }
                  var after = document.querySelectorAll('.katex').length;
                  st.pendingChanged = after !== before;
                  st.rendering = false;
                  // The glyphs a formula is drawn with arrive after the
                  // markup that asks for them, and a box measured before
                  // they land is measured with a substitute face — for
                  // maths, narrower than the real one. Such a box reads
                  // as fitting, and the reader is left with a formula cut
                  // off and nothing to drag. So the boxes are marked once
                  // the faces are in, one pass later.
                  st.state = (document.fonts && 'status' in document.fonts &&
                              document.fonts.status !== 'loaded') ? 'fonts' : 'marked';
                }
                function step() {
                  var t0 = Date.now();
                  // A slice that runs is proof the chain is alive; the stamp
                  // is what tells a later pass this one is still landing
                  // slices rather than having died between them.
                  st.alive = Date.now();
                  // Between slices the reader may have flung or jumped the
                  // page a screen since the queue was last ordered. Pull the
                  // still-unrendered blocks toward wherever they have landed
                  // so the render follows the eye, not the chapter's start.
                  var sy = (window.scrollY || 0) + (window.scrollX || 0);
                  var vh = Math.max(
                    window.innerHeight || document.documentElement.clientHeight || 0,
                    window.innerWidth || document.documentElement.clientWidth || 0);
                  if (i < blocks.length && vh > 0 && Math.abs(sy - lastSort) >= vh) {
                    sortNear(i);
                    lastSort = sy;
                  }
                  try {
                    while (i < blocks.length) {
                      // One formula that throws must not stop the chapter:
                      // auto-render is called per block, and an error it
                      // raises out of one block would otherwise escape to the
                      // outer catch, skip `finish()`'s successors, and leave
                      // every block after it — and this one — unrendered
                      // forever. Swallow it here and move on; a formula that
                      // cannot be parsed is left as the text it is.
                      try {
                        if (window.renderMathInElement)
                          window.renderMathInElement(blocks[i], opts);
                      } catch (eb) {}
                      i++;
                      if (Date.now() - t0 > @SLICE@) return setTimeout(step, 0);
                    }
                    // What MathJax left as a script tag, which is the
                    // other way a LaTeX-to-EPUB converter says "formula".
                    if (!srcs) srcs = document.querySelectorAll(
                      'script[type="math/tex"], script[type="math/tex; mode=Display"]');
                    while (q < srcs.length) {
                      var sc = srcs[q++];
                      if (sc.getAttribute(HIDE) === TOK) continue;
                      var disp = /mode=Display/.test(sc.getAttribute('type') || '');
                      var host = document.createElement(disp ? 'div' : 'span');
                      try {
                        window.katex.render(sc.textContent || '', host,
                                            {displayMode: disp, throwOnError: false});
                      } catch (e2) { continue; }
                      if (!host.firstChild) continue;
                      sc.parentNode.insertBefore(host, sc);
                      sc.setAttribute(HIDE, TOK);
                      if (Date.now() - t0 > @SLICE@) return setTimeout(step, 0);
                    }
                  } catch (e3) {}
                  finish();
                }
                // The first slice runs on the next timer turn, so the
                // pass that started the chain answers at once — and the
                // one-frame gap before it is also when the browser lays
                // out the stylesheet the scan injected. If even the
                // timer cannot be armed, mark what is already rendered
                // rather than leave the state machine at `ready` with
                // nothing driving it.
                try { setTimeout(step, 0); }
                catch (e4) { st.rendering = false; st.state = 'marked'; }
              }
              return 'loading';
            }

            return 'done';
          } catch (e) {
            return 'failed';
          }
        })();
    """.replace("@DOLLARS@", DOLLARS).replace("@DOLLAR@", "\$")
        .replace("@STALL@", RENDER_STALL_MS.toString())
        .replace("@SLICE@", SLICE_MS.toString())

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so a
     * plain string arrives wearing quotes.
     */
    internal fun parse(result: String?): Result {
        val value = result?.trim()?.removeSurrounding("\"")?.trim()
        return when (value) {
            "changed" -> Result.CHANGED
            "done" -> Result.DONE
            "empty" -> Result.EMPTY
            "loading" -> Result.LOADING
            "blocked" -> Result.BLOCKED
            else -> Result.FAILED
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun once(navigator: EpubNavigatorFragment): Result =
        parse(runCatching { navigator.evaluateJavascript(SCRIPT) }.getOrNull())

    /**
     * Typesets what is on screen and says whether the page moved.
     *
     * The bundled files are asked for once per document, and the answer
     * comes on its own time — so a pass that finds them still in flight
     * waits and asks again rather than reporting a page that had not moved
     * yet. That wait is bounded: see [LOAD_BUDGET_MS].
     *
     * A caller that only wants to know whether to re-anchor the reader can
     * treat [Result.CHANGED] as the one answer that means yes.
     */
    internal suspend fun apply(navigator: EpubNavigatorFragment): Result {
        var result = once(navigator)
        val deadline = SystemClock.elapsedRealtime() + LOAD_BUDGET_MS
        while (result == Result.LOADING) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return Result.FAILED
            delay(POLL_MS.coerceAtMost(left))
            result = once(navigator)
        }
        return result
    }
}
