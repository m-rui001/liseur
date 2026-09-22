package com.chmouel.liseur.reader

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Moves a formula that is wider than the page, under the reader's finger.
 *
 * A box [MathTypesetting] has marked with its pan attribute holds more than
 * the page shows. The browser would scroll such a box by itself, and Readium
 * would turn the page instead: its own touch handling decides a turn from
 * distance and velocity alone, having never asked the document what is under
 * the finger. Neither answer is wrong on its own — a page that slides
 * sideways under a thumb is how a two-page spread works — so this is settled
 * above both of them, in the reader's pointer loop, which sees every touch
 * before either does.
 *
 * Which is also how the two reading modes end up behaving the same. A
 * dragged formula is decided by the same code whether the chapter is turned
 * in columns or run in one long scroll, so a reader who switches modes
 * mid-book has nothing new to learn — and, in a scrolled book, nothing has
 * to be taken back off the vertical scroll that a sideways drag over a
 * formula must not disturb.
 *
 * The document is asked what is under a point and answers with a handle into
 * its own state rather than with anything the app could address by name, as
 * [ImageAtPoint] does. The pan itself is handed back to the document as a
 * delta, which clamps it, since only the page knows how much of a formula is
 * still left to see.
 */
internal object FormulaPan {

    /**
     * A marked box under a point, and the directions it can still be moved.
     *
     * @param id Index into the document's own list of probe results, valid
     *   until the next probe. Nothing outside this document can name it.
     * @param left There is content to show by dragging the finger rightwards.
     * @param right The same, by dragging it leftwards.
     * @param math Whether the box holds nothing but a formula. A paragraph
     *   with a wide formula run into it scrolls too, but it is still the
     *   reader's *text*, so a drag that starts there is not taken off the
     *   page turn until the reader has held still long enough to have meant
     *   it. See [FormulaPanDrag].
     */
    data class Hit(
        val id: Int,
        val left: Boolean,
        val right: Boolean,
        val math: Boolean = true,
    ) {
        /** Whether anything could come of a drag started here. */
        val pannable: Boolean get() = left || right
    }
    /**
     * The longest the document is given to answer.
     *
     * Asked as the finger lands, and the answer has to be in hand before a
     * drag has gone far enough to be a page turn, so this is short by design:
     * a web view that has not answered by then has lost the gesture anyway,
     * and the turn it was in the way of is the older, better-tested
     * behaviour.
     */
    private const val EVAL_TIMEOUT_MS = 800L

    /**
     * How many boxes one touch may be offered. The document stops at twelve
     * stacked elements, so this is that walk's own ceiling — an answer with
     * more candidates in it is a document that stopped being a page.
     */
    private const val MAX_CANDIDATES = 12

    /**
     * How much less than asked a box may move before that counts as having
     * run out.
     *
     * `scrollLeft` is a whole number of CSS pixels, so a one-pixel ask that
     * lands fully is a two-pixel difference from the answer's point of view;
     * anything under this is rounding rather than an edge.
     */
    internal const val EDGE_TOLERANCE_PX = 2.0

    /**
     * Whether this document has anything to pan at all.
     *
     * Asked once per resource, off the touch path, because the answer is no
     * for most pages of most books — and a no means a touch runs no script at
     * all. Only a *yes* is remembered; see the caller, which asks again the
     * next time the page moves.
     *
     * A yes is deliberately the loose question — "is there a box on this
     * page" rather than "is the finger on one". Where the boxes are is
     * something the page would have to be asked about again: a scrolled
     * chapter moves them without moving the reader to a new resource, so any
     * geometry cached here goes stale exactly when it is being used. What a
     * yes costs is one short hold before a page turn on a mathematics page,
     * which the probe answers in milliseconds.
     */
    const val HAS_PANELS_SCRIPT: String = """
        (function () {
          try {
            var st = window.__liseurMath;
            if (!st || !st.attr) return false;
            return !!document.querySelector('[' + st.attr + ']');
          } catch (e) { return false; }
        })();
    """

    /** Whether the resource on screen has a box worth panning. */
    internal suspend fun hasPanels(web: WebView): Boolean =
        eval(web, HAS_PANELS_SCRIPT)?.trim() == "true"

    /**
     * The full range of [scrollLeft] one handle can be moved across, measured
     * once when a drag claims it.
     *
     * The pan that follows writes an *absolute* position every frame rather
     * than nudging by a delta and waiting for the page to say how far it
     * actually went. That removes the round-trip the drag used to pay once per
     * frame — a JavaScript evaluation, its JSON answer parsed back on the main
     * thread, and only then the next piece of the finger's travel read — which
     * is what made a formula trail the finger and snap forward when the drag
     * settled. A write that only needs an ordering does not have to be awaited.
     *
     * For it to clamp itself the app has to know the box's range in the same
     * units it writes, and those units are not one thing: `scrollLeft` runs
     * positive in a left-to-right overflow and negative in a right-to-left one,
     * and some web views implement the older signed conventions rather than the
     * spec's. So the range is *measured*, not computed — the same nudge and
     * restore the probe uses, in one synchronous block nothing paints inside.
     * The box is pushed hard each way and whatever stuck is the reachable span;
     * the current offset is read last, after the nudges have been put back.
     */
    fun rangeScript(id: Int): String = """
        (function () {
          try {
            var st = window.__liseurMath;
            var el = st && st.els && st.els[$id];
            if (!el || !document.contains(el)) return null;
            var o = el.scrollLeft, lo = o, hi = o, k;
            el.scrollLeft = o + 20000; if (el.scrollLeft > hi) hi = el.scrollLeft;
            el.scrollLeft = o - 20000; if (el.scrollLeft < lo) lo = el.scrollLeft;
            el.scrollLeft = o; k = el.scrollLeft;
            var v = window.innerWidth || document.documentElement.clientWidth;
            return { lo: lo, hi: hi, c: k, v: v };
          } catch (e) { return null; }
        })();
    """

    /**
     * Sets a handle's scroll position to [px], an absolute CSS offset already
     * clamped by the caller to the range [rangeScript] reported.
     *
     * Fire-and-forget. The writes reach the document's thread in the order they
     * were issued, so the last one before a frame paints is the one that shows,
     * and the formula keeps up with the finger at the touch's own rate.
     */
    fun scrollToScript(id: Int, px: Double): String = """
        (function () {
          try {
            var st = window.__liseurMath;
            var el = st && st.els && st.els[$id];
            if (!el || !document.contains(el)) return;
            el.scrollLeft = $px;
          } catch (e) {}
        })();
    """

    /**
     * A box's reachable [scrollLeft] span ([low]..[high]), where it sits now
     * ([current]), and the CSS viewport width it is measured in ([viewport]).
     * As [rangeScript] reported them, kept apart from the drag so the
     * arithmetic that turns a finger's travel into a position — and spots the
     * box running out — can be read and tested without a document.
     *
     * [viewport] is what converts the finger's travel, offered to the drag as a
     * fraction of the view, into the same CSS pixels the range is written in:
     * a page drawn to fit scales the two apart, and the fraction the probe read
     * the position with is this same viewport width.
     */
    internal data class Range(
        val low: Double,
        val high: Double,
        val current: Double,
        val viewport: Double,
    ) {
        /** Room to move towards smaller offsets (dragging the content right). */
        val toLow: Double get() = current - low

        /** Room to move towards larger offsets (dragging the content left). */
        val toHigh: Double get() = high - current

        /** The offset the content follows the finger to, given a [delta] in
         * the same CSS pixels the range is measured in, clamped to it. */
        fun offsetFor(delta: Double): Double =
            (current - delta).coerceIn(low, high)
    }

    /**
     * Read [rangeScript]'s answer back, or null when the handle is gone or the
     * page said anything that is not a range. The document is the book's, so
     * what comes back from it is checked, not trusted.
     */
    internal fun parseRange(result: String?): Range? {
        val raw = result?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null" || raw == "undefined" || raw.length > 96) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!json.has("lo") || !json.has("hi") || !json.has("c") || !json.has("v")) return null
        val lo = json.optDouble("lo", Double.NaN)
        val hi = json.optDouble("hi", Double.NaN)
        val c = json.optDouble("c", Double.NaN)
        val v = json.optDouble("v", Double.NaN)
        if (lo.isNaN() || hi.isNaN() || c.isNaN() || v.isNaN() || hi < lo || v <= 0.0) return null
        return Range(low = lo, high = hi, current = c.coerceIn(lo, hi), viewport = v)
    }

    /**
     * The marked boxes under a point, innermost first, with the directions
     * each can still be moved.
     *
     * The point arrives as a fraction of the view, for the reason
     * [ImageAtPoint] gives: a fixed-layout page is drawn at whatever scale
     * fits the screen, so device pixels over the density are not CSS pixels
     * there, while a fraction of the viewport is the same fraction either
     * way.
     *
     * The stack matters as much as the first answer. A page can hold several
     * formulas in one column — a block the book itself made scrollable, and
     * the rendered formula inside it — and the box the reader meant is the
     * innermost one that can actually move. With one candidate the answer is
     * the old `{i, l, r}`; with more it is `{c: [..], m: [..]}`, and the
     * caller walks it from the outside in, because a box that is at its edge
     * is not the box to hand the drag to.
     *
     * Room is read by nudging `scrollLeft` and putting it straight back,
     * inside one synchronous block so nothing paints in between. That is what
     * makes the answer true whichever way the web version numbers the
     * property, and true for a book written right to left — where the same
     * property runs the other way.
     */
    fun probeScript(fx: Float, fy: Float): String = """
        (function () {
          try {
            var st = window.__liseurMath;
            if (!st || !st.attr) return null;
            var VW = window.innerWidth || document.documentElement.clientWidth;
            var VH = window.innerHeight || document.documentElement.clientHeight;
            var px = $fx * VW;
            var py = $fy * VH;
            var els = [];
            try {
              // `closest`, not a counted walk: a rendered formula is thirty
              // spans deep, and a finger on a nested fraction stops six hops
              // short of the box that actually scrolls. The attribute name is
              // this document's own token — letters, digits and hyphens — so
              // it needs no escaping inside the selector.
              var stack = document.elementsFromPoint(px, py) || [];
              for (var s = 0; s < stack.length && s < 12; s++) {
                var node = stack[s];
                if (!node.closest) continue;
                var box = null;
                try { box = node.closest('[' + st.attr + ']'); } catch (e) {}
                if (box && els.indexOf(box) < 0) els.push(box);
              }
            } catch (e) {}
            if (!els.length) return null;
            st.els = els;
            var c = [], m = [];
            for (var q = 0; q < els.length; q++) {
              var el = els[q];
              var orig = el.scrollLeft;
              var left = false, right = false;
              el.scrollLeft = orig + 1;
              right = el.scrollLeft > orig;
              el.scrollLeft = orig;
              el.scrollLeft = orig - 1;
              left = el.scrollLeft < orig;
              el.scrollLeft = orig;
              if (left || right) {
                c.push(q);
                m.push((left ? 1 : 0) | (right ? 2 : 0) |
                      (st.pure && el.getAttribute(st.pure) === st.tok ? 4 : 0));
              }
            }
            if (!c.length) return null;
            if (c.length === 1) {
              return { i: c[0], l: !!(m[0] & 1), r: !!(m[0] & 2), p: !!(m[0] & 4) };
            }
            return { c: c, m: m };
          } catch (e) { return null; }
        })();
    """

    /** What could be panned under a point given as a fraction of [web]. */
    internal suspend fun at(web: WebView, fx: Float, fy: Float): List<Hit>? =
        parseProbe(eval(web, probeScript(fx, fy)))

    /**
     * The page's probe answer, read out of the JSON `evaluateJavascript`
     * hands back.
     *
     * A box that cannot be moved either way is a miss, and so is anything
     * longer than a handful of candidates could be: the document is the
     * book's, so what comes back from it is treated as untrusted.
     *
     * The list comes back innermost first — the box the finger is actually
     * on before whatever encloses it — and stays that way. Which of them a
     * drag belongs to is [FormulaPanDrag]'s question, because only the drag
     * knows which way it is going.
     */
    internal fun parseProbe(result: String?): List<Hit>? {
        val raw = result?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null" || raw == "undefined") return null
        if (raw.length > 256) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.has("i")) {
            val one = Hit(
                id = json.optInt("i", -1),
                left = json.optBoolean("l"),
                right = json.optBoolean("r"),
                math = json.optBoolean("p"),
            )
            return if (one.id >= 0 && one.pannable) listOf(one) else null
        }
        val idx = json.optJSONArray("c") ?: return null
        val masks = json.optJSONArray("m") ?: return null
        if (idx.length() != masks.length() || idx.length() > MAX_CANDIDATES) return null
        val hits = ArrayList<Hit>(idx.length())
        for (q in 0 until idx.length()) {
            val i = idx.optInt(q, -1)
            val mask = masks.optInt(q, -1)
            if (i < 0 || mask <= 0 || mask > 7) return null
            hits += Hit(
                id = i,
                left = mask and 1 != 0,
                right = mask and 2 != 0,
                math = mask and 4 != 0,
            )
        }
        return hits.ifEmpty { null }
    }

    /**
     * The reachable range of the handle [id], as the page measures it — see
     * [rangeScript]. Null once the handle is gone, or if the page answers
     * anything that is not a range.
     */
    internal suspend fun range(web: WebView, id: Int): Range? =
        parseRange(eval(web, rangeScript(id)))

    /**
     * Writes the handle [id] to an absolute [px], fire and forget — see
     * [scrollToScript]. Nothing is awaited: an absolute write only has to
     * arrive after the one before it, and the document's own thread keeps
     * that order, so a drag can issue one per frame without ever parking the
     * touch on a page round-trip.
     */
    internal fun scrollTo(web: WebView, id: Int, px: Double) {
        val js = scrollToScript(id, px)
        try {
            web.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // A view torn down under a live drag simply stops answering; the
            // next [rangeScript] sees the handle is gone and hands the drag up.
        }
    }

    private suspend fun eval(web: WebView, js: String): String? =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    web.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
}

/**
 * One finger's claim on a formula, across a single drag.
 *
 * The arithmetic is kept apart from the touch loop so the cases that decide
 * it can be read and tested without a screen: a drag that sets off sideways
 * over a formula with more to show that way, and one that does not.
 * Everything else — a vertical drag, a formula already at its edge, a second
 * finger, a tap — is left exactly as it was. Turning a page or moving down a
 * chapter by dragging across a formula is what a reader has always done, and
 * goes on working unless the drag is clearly meant for the maths.
 *
 * The claim is decided once, at the moment the direction settles, and held
 * until the fingers leave. Taking a gesture over partway through would cancel
 * a touch the page is already acting on, which reads as half a page turn and
 * a formula that jumps.
 *
 * What is under the finger is a stack rather than a box, for the same reason
 * a click is: a chapter may wrap a formula in a block of its own that also
 * scrolls, and the two want different drags. The pick is by direction — the
 * innermost box that can still show something that way — and it is revisited
 * every time the drag hits an edge, where the outer box takes over from the
 * inner one. A reader who drags a formula to the end of itself and keeps going
 * then keeps seeing more, rather than pressing against a wall.
 */
internal class FormulaPanDrag {

    internal enum class Mode {
        /** Not settled yet; the finger has not travelled far enough to say. */
        UNDECIDED,

        /** Not ours — a turn, a scroll, a tap. Nothing more is asked this drag. */
        PASSED,

        /** Ours: the formula under the finger is being moved. */
        PANNING,
    }

    internal var mode = Mode.UNDECIDED
        private set

    /** The boxes the document reported under the finger, innermost first. */
    private var hits: List<FormulaPan.Hit> = emptyList()

    /** The one being moved, as an index into [hits]; null until claimed. */
    private var chosen: Int? = null

    /**
     * The box's reachable range and where the finger was when it took the drag,
     * both in the currency the pan is written in. The range is measured once
     * for whichever box is [chosen]; a handoff at an edge clears it so the next
     * box's is measured in turn. Null between a claim and its answer — during
     * which a frame writes nothing, because there is nowhere yet to write to.
     */
    private var range: FormulaPan.Range? = null
    private var anchor = 0f

    /**
     * The view's width in device pixels, handed in with [give] so a travel
     * measured in those pixels can be turned into the fraction of the view the
     * box's [FormulaPan.Range] is written against. The touch loop and the page
     * measure the same distance in different units — a page drawn to fit scales
     * them apart — and this is the one place they are reconciled.
     */
    private var scale = 1f

    /** Whether the box being moved still needs its range measured. */
    val needsRange: Boolean get() = mode == Mode.PANNING && range == null

    val panning: Boolean get() = mode == Mode.PANNING

    /** The box being moved, or null while nothing is claimed. */
    val hit: FormulaPan.Hit? get() = chosen?.let { hits.getOrNull(it) }

    /** Records what is under the finger, as soon as the document has said. */
    internal fun take(hits: List<FormulaPan.Hit>?) {
        this.hits = hits.orEmpty()
    }

    /**
     * Records the range of the box just claimed (or handed at an edge), and the
     * view width [scalePx] its finger travel is measured in. Null range means
     * the handle had gone by the time it was asked about, and the pan writes
     * nothing until the next box is measured.
     */
    internal fun give(range: FormulaPan.Range?, scalePx: Float) {
        this.range = range
        if (scalePx > 0f) scale = scalePx
    }

    /**
     * Offers a drag that has travelled [dx] across and [dy] down from where
     * the finger landed, with [pointers] fingers down, a touch slop of
     * [slop] as the platform measures it, and [held] saying whether the
     * finger first sat still long enough to have meant it. Answers whether
     * the drag belongs to the formula, and so whether the caller must consume
     * the touch and hand the movement to [offset].
     *
     * Below the slop a drag has no direction to read, so nothing is decided
     * yet.
     *
     * A box holding nothing but a formula is claimed the moment the drag
     * reads as sideways: there is nothing else a sideways drag over it could
     * be for. A paragraph with a wide formula run into it is the reader's
     * text first and a viewport second, so it is only taken from a drag that
     * began with a hold — otherwise every page turn that happens to start on
     * such a line would stall for the hold, and turning pages is the more
     * common thing by a wide margin.
     */
    fun offer(pointers: Int, dx: Float, dy: Float, slop: Float, held: Boolean): Boolean {
        if (pointers != 1) {
            // A second finger is a pinch, or a thumb coming to rest. A
            // formula held mid-drag is put down rather than fought over.
            if (mode == Mode.PANNING) release()
            mode = Mode.PASSED
            return false
        }
        if (mode == Mode.PANNING) return true
        if (mode == Mode.PASSED) return false
        if (abs(dx) <= slop && abs(dy) <= slop) return false
        if (!sideways(dx, dy)) {
            // Straight down the page, over a formula. Nothing about the
            // maths is being asked for, and saying so settles the drag for
            // the rest of this touch rather than re-reading it every frame.
            mode = Mode.PASSED
            return false
        }
        val pick = pick(dx) ?: return false.also { mode = Mode.PASSED }
        if (!hits[pick].math && !held) return false.also { mode = Mode.PASSED }
        chosen = pick
        mode = Mode.PANNING
        range = null
        // The finger has already travelled; what it showed the formula
        // before the claim landed is a jump, so the pan is measured from here.
        anchor = dx
        return true
    }

    /**
     * The absolute [scrollLeft] the content follows the finger to, given a
     * total travel of [travel] — the same fraction of the view [offer] is fed.
     * Null while the box's range is still being measured, so a frame that
     * arrives early simply writes nothing rather than jumping to a position
     * the page has not been asked about yet.
     *
     * The offset is clamped to the box's measured range, which is how the drag
     * knows a box has run out: an ask that lands on a boundary and is still
     * being pushed past is an edge, and [refused] hands it one level up.
     */
    /**
     * The finger's travel from the anchor, in the same CSS pixels the box's
     * range is measured in. The drag sees device pixels and the page sees CSS
     * pixels of its own viewport width; the two differ by exactly the ratio the
     * page was drawn at, which is [scale] over [FormulaPan.Range.viewport].
     */
    private fun cssDelta(travel: Float, r: FormulaPan.Range): Double =
        (travel - anchor) * (r.viewport / scale)

    fun offset(travel: Float): Double? {
        if (mode != Mode.PANNING) return null
        val r = range ?: return null
        return r.offsetFor(cssDelta(travel, r))
    }

    /**
     * Whether the last [offset] was pinned against a boundary in the direction
     * the finger is still pushing, meaning the box under the finger has shown
     * all it can that way. Hands the drag to whatever encloses it, if that can
     * still go the same way, and says whether it did — clearing the range so
     * the new box's is measured before the next write.
     *
     * Decided off the clamped position and the direction of travel rather than
     * a page answer: with the pan now absolute there is no "asked vs got" to
     * compare, but the range already says exactly where an edge is.
     */
    fun refused(travel: Float): Boolean {
        if (mode != Mode.PANNING) return false
        val r = range ?: return false
        val delta = cssDelta(travel, r)
        if (delta == 0.0) return false
        val pinned = if (delta > 0) r.offsetFor(delta) <= r.low + FormulaPan.EDGE_TOLERANCE_PX
        else r.offsetFor(delta) >= r.high - FormulaPan.EDGE_TOLERANCE_PX
        if (!pinned) return false
        val from = chosen ?: return false
        val next = (from + 1 until hits.size).firstOrNull {
            val h = hits[it]
            if (delta > 0.0) h.left else h.right
        } ?: return false
        chosen = next
        range = null
        // The new box takes the drag from where the finger already is, so the
        // handoff itself does not move the content.
        anchor = travel
        return true
    }

    /** Lets go, and clears the claim. */
    fun release() {
        mode = Mode.UNDECIDED
        anchor = 0f
        chosen = null
        range = null
    }

    /** Drops everything: a fresh touch, or a page that went away mid-drag. */
    fun reset() {
        release()
        hits = emptyList()
    }

    private fun pick(dx: Float): Int? = hits.indexOfFirst {
        // Content follows the finger: dragging rightwards brings back what
        // lies to the left of the box, and the other way round.
        if (dx > 0) it.left else it.right
    }.takeIf { it >= 0 }

    internal companion object {
        /** Whether a drag that moved [dx] across and [dy] down is going sideways. */
        fun sideways(dx: Float, dy: Float): Boolean = abs(dx) > abs(dy)
    }
}

