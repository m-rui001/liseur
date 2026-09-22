package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mathematics pass has to be tested as a *question* rather than as a
 * rendered page: what it does to a book's DOM is the web view's business,
 * while what it says back, and what the reader's finger does next, are here.
 */
class MathTypesettingTest {

    @Test
    fun `the javascript answer is read through its json quoting`() {
        assertEquals(MathTypesetting.Result.CHANGED, MathTypesetting.parse("\"changed\""))
        assertEquals(MathTypesetting.Result.DONE, MathTypesetting.parse(" done "))
        assertEquals(MathTypesetting.Result.EMPTY, MathTypesetting.parse("\"empty\""))
        assertEquals(MathTypesetting.Result.LOADING, MathTypesetting.parse("\"loading\""))
        assertEquals(MathTypesetting.Result.BLOCKED, MathTypesetting.parse("\"blocked\""))
    }

    @Test
    fun `no answer is a failure, never a silent success`() {
        assertEquals(MathTypesetting.Result.FAILED, MathTypesetting.parse(null))
        assertEquals(MathTypesetting.Result.FAILED, MathTypesetting.parse(""))
        assertEquals(MathTypesetting.Result.FAILED, MathTypesetting.parse("null"))
        assertEquals(MathTypesetting.Result.FAILED, MathTypesetting.parse("undefined"))
        assertEquals(MathTypesetting.Result.FAILED, MathTypesetting.parse("\"whatever\""))
    }

    @Test
    fun `the script is one expression, so evaluateJavascript returns its value`() {
        val script = MathTypesetting.SCRIPT.trim()
        assertTrue(script.startsWith("(function"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun `a chapter is only scanned once, so a settled page says done and never moved`() {
        // Every layout pass asks again. A pass that answered "changed" over a
        // chapter it had already typeset would move the reader's place on
        // every page turn, for nothing.
        assertTrue(MathTypesetting.SCRIPT.contains("st.state === 'marked'"))
        assertTrue(MathTypesetting.SCRIPT.contains("return 'done'"))
    }

    @Test
    fun `an untouched chapter is latched only once the document has finished loading`() {
        // The reader's place is asked about once per resource, so a "no" read
        // from a document still in flight would cost that chapter its
        // mathematics until it is come back to. A page that is not finished
        // answers `loading` instead, which is what makes it asked again.
        val script = MathTypesetting.SCRIPT
        val scan = script.substringAfter("if (st.state === 'scan')")
            .substringAfter("if (!mathy && !texy)")
            .substringBefore("if (st.state === 'empty')")
        assertTrue(scan, scan.contains("return 'loading'"))
        assertTrue(scan, scan.contains("st.state = 'empty'"))
    }

    @Test
    fun `an empty latch lets go when the chapter under it changes`() {
        // A mode switch re-lays a chapter inside the SAME web view, and the
        // first pass can land while the body is complete but not yet filled —
        // latching `empty` against content that was never there. `empty` has
        // to be a claim about the scanned content, not a life sentence for the
        // window, or every formula stays raw forever after that one bad look
        // (which is exactly the "switch mode and maths never renders again,
        // but reopening the book fixes it" bug). So the scan branch is entered
        // again once the body's structure no longer matches what was latched.
        val script = MathTypesetting.SCRIPT
        val guard = script.substringBefore("if (st.state === 'scan')")
        assertTrue(guard, guard.contains("function sig()"))
        assertTrue(guard, script.contains("st.state = 'empty'"))
        assertTrue(script, script.contains("st.sig = sig()"))
        assertTrue(script, script.contains("st.state === 'empty' && st.sig !== sig()"))
    }

    @Test
    fun `the cold render is sliced, so no one task holds a page turn hostage`() {
        // A chapter of eighty formulas rendered in one task measured two
        // seconds of frozen page on a throttled device — the reader's page
        // turn lands inside it. The chain renders block by block and hands
        // the thread back every slice, so the pass that started it answers
        // `loading` at once and the page stays responsive between slices.
        val ready = MathTypesetting.SCRIPT.substringAfter("if (st.state === 'ready')")
        assertTrue(ready, ready.contains("return setTimeout(step, 0);"))
        assertTrue(ready, ready.contains("Date.now() - t0 > 24"))
        // It is driven by the same `loading` poll the assets' load already
        // uses, so nothing about the caller changes.
        assertTrue(ready, ready.contains("return 'loading';"))
    }

    @Test
    fun `the render follows where the reader is, not where the chapter begins`() {
        // A flat document-order walk makes a reader who flings a scrolled
        // chapter down watch raw TeX at their feet while the engine typesets
        // every screen they passed. So the queue is sorted by distance from
        // the viewport, and re-sorted from inside the slice loop when the
        // reader moves a full viewport, so the render chases the eye.
        val ready = MathTypesetting.SCRIPT.substringAfter("if (st.state === 'ready')")
            .substringBefore("if (st.state === 'fonts')")
        // Blocks are ordered by how far their box sits off the viewport.
        assertTrue(ready, ready.contains("getBoundingClientRect"))
        assertTrue(ready, ready.contains("rest.sort("))
        // The first sort happens before the chain runs, and the walk starts at
        // the block nearest the reader rather than at document position zero.
        assertTrue(ready, ready.contains("sortNear(0);"))
        // Between slices, a reader a screen on re-orders the not-yet-rendered
        // tail from where the walk currently is.
        val step = MathTypesetting.SCRIPT.substringAfter("function step()")
            .substringBefore("finish();")
        assertTrue(step, step.contains("Math.abs(sy - lastSort) >= vh"))
        assertTrue(step, step.contains("sortNear(i);"))
    }

    @Test
    fun `a render that died between slices is restarted, not left stranded`() {
        // The cold render is a chain of timer slices that outlives the pass
        // that armed it, and it is driven from inside the page. A pass that
        // meets it mid-flight must neither abandon it nor start a second copy
        // racing the first, so the chain stamps when it last ran a slice: a
        // live chain is left running (`loading`), a quiet one is taken to be
        // dead and re-armed. The stall is well above the poll interval so a
        // merely slow device is never mistaken for a dead chain.
        val script = MathTypesetting.SCRIPT
        val guard = script.substringAfter("if (st.state === 'blocked')")
            .substringBefore("if (st.state === 'fonts')")
        assertTrue(guard, guard.contains("if (st.rendering && st.state === 'ready')"))
        assertTrue(guard, guard.contains("if (Date.now() - st.alive < ${MathTypesetting.RENDER_STALL_MS}) return 'loading';"))
        assertTrue(guard, guard.contains("st.rendering = false;"))
        assertTrue(
            "the stall must outlast a poll so a slow chain is not restarted",
            MathTypesetting.RENDER_STALL_MS > MathTypesetting.POLL_MS,
        )
    }

    @Test
    fun `one formula that throws does not orphan the rest of the chapter`() {
        // auto-render is called per block; an error escaping one block would
        // otherwise reach the outer catch, skip every later block, and leave
        // the page half-typeset with nothing left to finish it. Each block is
        // rendered inside its own try, so a bad formula is left as text and
        // the chain carries on.
        val step = MathTypesetting.SCRIPT.substringAfter("function step()")
            .substringBefore("function finish")
        // The per-block call is guarded, and it is the guarded one that runs.
        assertTrue(step, step.contains("try {"))
        assertTrue(step, step.contains("window.renderMathInElement(blocks[i], opts);"))
    }

    @Test
    fun `a finished render is announced as changed exactly once`() {
        // A chapter whose literal TeX just became formulas moved the page
        // even where every formula fits, and the reader's place has to be
        // put back for that. But an announcement on every pass afterwards
        // would move them on every page turn forever, so the flag is spent
        // on the first `done` it meets.
        val marked = MathTypesetting.SCRIPT.substringAfter("if (st.state === 'marked') {")
            .substringBefore("if (st.state === 'ready')")
        assertTrue(marked, marked.contains("st.pendingChanged = false;"))
        assertTrue(marked, marked.contains("if (mk === 'done') return 'changed';"))
        assertTrue(marked, marked.contains("return mk;"))
    }

    @Test
    fun `the library is asked for from the app's own assets, not off a network`() {
        // F-Droid ships an app that reads a book with no network at all, and a
        // mathematics book whose formulae only render on a train journey is
        // worse than one that never renders them.
        assertTrue(MathTypesetting.SCRIPT.contains("https://readium_assets/katex/"))
        assertFalse(MathTypesetting.SCRIPT.contains("cdn."))
        assertFalse(MathTypesetting.SCRIPT.contains("https://cdn"))
        assertFalse(MathTypesetting.SCRIPT.contains("jsdelivr"))
    }

    @Test
    fun `nothing is evaluated from a string, so a strict script-src costs only the maths`() {
        assertFalse(MathTypesetting.SCRIPT.contains("eval("))
        assertFalse(MathTypesetting.SCRIPT.contains("new Function"))
    }

    @Test
    fun `a scrolled box is left aligned, because a centred one hides half of itself`() {
        val rule = MathTypesetting.SCRIPT.substringAfter("function ownCss")
            .substringBefore("document.head.appendChild")
        assertTrue(rule, rule.contains("overflow-x:auto"))
        assertTrue(rule, rule.contains("text-align:left"))
    }

    @Test
    fun `the marked box is forced left-to-right so the drag reads the same both ways`() {
        // An inherited `direction: rtl` sits the overflow of a centred nowrap
        // box on the other side and numbers the scroll offset from it, so a
        // reader dragging rightwards would start with the whole formula out of
        // reach. The pan itself is computed by nudging scrollLeft, which is
        // true for either direction, and the drag is always the finger's.
        val css = MathTypesetting.SCRIPT
        val start = css.indexOf("function ownCss")
        val end = css.indexOf("if (st.state === 'scan')")
        assertTrue(css.substring(start, end), css.substring(start, end).contains("direction:ltr"))
    }

    @Test
    fun `a box is measured against the page, not against what happens to contain it`() {
        // The bug this guards: a formula inside something the browser sized to
        // fit its content — a table cell, a flex item, an `inline-block` —
        // never overflows *that*, because that grew to hold it. Asking only
        // `scrollWidth - clientWidth` therefore misses it, and the page it
        // widened stays wide: every line on that page cut at both edges. The
        // test for "too wide" has to be against the page's own measure.
        val mark = MathTypesetting.SCRIPT.substringAfter("function mark()")
            .substringBefore("function ownCss")
        assertTrue(mark, mark.contains("box.scrollWidth > page + 1"))
        assertTrue(mark, mark.contains("var page = pageWidth();"))
        assertTrue(MathTypesetting.SCRIPT, MathTypesetting.SCRIPT.contains("function pageWidth()"))
    }

    @Test
    fun `a page widened by a formula is given its own width back`() {
        // Marking alone is not enough there: the box has nothing to scroll, so
        // the only way the column comes back is to cap the box at the page's
        // width. The width is a custom property set from the measured page on
        // every pass, so it stays right across a font-size change.
        val rules = MathTypesetting.SCRIPT.substringAfter("function ownCss")
            .substringBefore("document.head.appendChild")
        assertTrue(rules, rules.contains("--liseur-pan-w"))
        assertTrue(rules, rules.contains("max-width:var(--liseur-pan-w)!important"))
        val mark = MathTypesetting.SCRIPT.substringAfter("function mark()")
            .substringBefore("function ownCss")
        assertTrue(mark, mark.contains("setProperty('--liseur-pan-w'"))
    }

    @Test
    fun `the width cap is taken off before it is measured, so the page cannot breathe`() {
        // A capped box reports the width we imposed on it as its own, so
        // measuring with the cap on would decide the opposite next pass and
        // the page would grow and shrink forever. Cleared, measured, then put
        // back — the same order WideContentFit uses for its own marker.
        val mark = MathTypesetting.SCRIPT.substringAfter("function mark()")
            .substringBefore("function ownCss")
        val clear = mark.indexOf("if (hadCap) el.removeAttribute(WIDE);")
        val measure = mark.indexOf("var needsCap = el.clientWidth > page + 1;")
        val restore = mark.indexOf("if (needsCap) el.setAttribute(WIDE, TOK);")
        assertTrue(mark, clear in 0 until measure && measure in 0 until restore)
    }

    @Test
    fun `a box that was never too wide is not capped`() {
        // The cap is paid for by the content box: applying it to a box that
        // already fits would silently shrink a paragraph that carries padding
        // of its own. The ask is the box's own client width, not its scroll
        // width — only a box *wider than the page* needs to come back.
        val mark = MathTypesetting.SCRIPT.substringAfter("function mark()")
            .substringBefore("function ownCss")
        assertTrue(mark, mark.contains("var needsCap = el.clientWidth > page + 1;"))
        assertFalse(mark, mark.contains("var needsCap = el.scrollWidth"))
    }

    @Test
    fun `a removed mark takes its width cap with it`() {
        // Both attributes are the app's, and both have to go together: a cap
        // left behind on a stale box would hold a width the page no longer
        // asked for.
        val mark = MathTypesetting.SCRIPT.substringAfter("function mark()")
            .substringBefore("function ownCss")
        val stale = mark.substringAfter("var stale = document.querySelectorAll")
        assertTrue(stale, stale.contains("removeAttribute(WIDE)"))
    }

    @Test
    fun `the marker is owned by a per-document token, not a guessable name`() {
        assertTrue(MathTypesetting.SCRIPT.contains("st.tok"))
        assertFalse(MathTypesetting.SCRIPT.contains("\"liseur-pan\""))
    }

    @Test
    fun `a dollar on its own is not a formula`() {
        // Single `$…$` is left out of the delimiters: a book that prices
        // anything in dollars would have its prose eaten.
        val delimiters = MathTypesetting.SCRIPT.substringAfter("delimiters: [")
            .substringBefore("ignoredTags")
        assertFalse(delimiters.contains("'$', right: '$'"))
        assertTrue(delimiters.contains("'$$', right: '$$'"))
        assertTrue(delimiters.contains("\\\\("))
        assertTrue(delimiters.contains("\\\\["))
    }
}

class FormulaPanTest {

    @Test
    fun `a box with room to move comes back as a hit`() {
        val hits = FormulaPan.parseProbe("""{"i":0,"l":true,"r":false,"p":true}""")
        assertNotNull(hits)
        assertEquals(1, hits!!.size)
        val hit = hits[0]
        assertEquals(0, hit.id)
        assertTrue(hit.left)
        assertFalse(hit.right)
        assertTrue(hit.math)
        assertTrue(hit.pannable)
    }

    @Test
    fun `a paragraph with prose in it comes back pannable but not as maths`() {
        // It scrolls, so a drag can pan it — but it is the reader's text, and
        // the caller must not take a page turn with it unless the reader
        // held first.
        val hit = FormulaPan.parseProbe("""{"i":2,"l":false,"r":true,"p":false}""")!![0]
        assertTrue(hit.pannable)
        assertFalse(hit.math)
    }

    @Test
    fun `a box already at one edge is still pannable, and in the one direction`() {
        val hit = FormulaPan.parseProbe("""{"i":0,"l":false,"r":true}""")!![0]
        assertFalse(hit.left)
        assertTrue(hit.right)
    }

    @Test
    fun `a stack of boxes comes back innermost first`() {
        // A formula inside a block the book made scrollable answers with
        // both, and the drag has to be able to start on the inner one and
        // fall through to the outer when it runs out.
        val hits = FormulaPan.parseProbe("""{"c":[1,0],"m":[5,3]}""")
        assertEquals(2, hits!!.size)
        assertEquals(1, hits[0].id)
        assertTrue(hits[0].left)
        assertFalse(hits[0].right)
        assertTrue(hits[0].math)
        assertEquals(0, hits[1].id)
        assertTrue(hits[1].left && hits[1].right)
        assertFalse(hits[1].math)
    }

    @Test
    fun `a box with nothing left to show is a miss, not a gesture that does nothing`() {
        assertNull(FormulaPan.parseProbe("""{"i":0,"l":false,"r":false}"""))
    }

    @Test
    fun `a miss from the page is a miss`() {
        assertNull(FormulaPan.parseProbe(null))
        assertNull(FormulaPan.parseProbe(""))
        assertNull(FormulaPan.parseProbe("null"))
        assertNull(FormulaPan.parseProbe("undefined"))
    }

    @Test
    fun `an answer the document invented is refused`() {
        // The document is the book's, so what comes back from it is untrusted.
        assertNull(FormulaPan.parseProbe("not json at all"))
        assertNull(FormulaPan.parseProbe("""{"l":true,"r":true}"""))
        assertNull(FormulaPan.parseProbe("""{"i":-1,"l":true}"""))
        assertNull(FormulaPan.parseProbe("[" + "\"".repeat(80) + "x".repeat(300) + "]"))
        // A stack longer than the walk that builds it is not a page.
        assertNull(FormulaPan.parseProbe("""{"c":[${(0..12).joinToString(",")}],""" +
            """"m":[${(1..12).joinToString(",")}]}"""))
        // An answer with neither shape is not one either.
        assertNull(FormulaPan.parseProbe("""{"l":true,"r":true,"p":true}"""))
        // And a mask that claims no direction at all, or one beyond the three.
        assertNull(FormulaPan.parseProbe("""{"c":[0],"m":[8]}"""))
    }

    @Test
    fun `the probe is one expression, so evaluateJavascript returns its value`() {
        val script = FormulaPan.probeScript(0.5f, 0.25f).trim()
        assertTrue(script.startsWith("(function"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun `the point is asked as a fraction of the view, not in pixels`() {
        // A fixed-layout page is drawn at whatever scale fits the screen, so
        // device pixels over the density are not CSS pixels there.
        val script = FormulaPan.probeScript(0.5f, 0.25f)
        assertTrue(script.contains("window.innerWidth"))
        assertTrue(script.contains("0.5 * VW"))
    }

    @Test
    fun `room is read by nudging the offset rather than by a formula about widths`() {
        // scrollWidth - clientWidth says nothing about which way a right-to-left
        // book can still go, and the two conventions for the offset have both
        // shipped in Chromium. The nudge is the page's own answer.
        val script = FormulaPan.probeScript(0.5f, 0.25f)
        assertTrue(script.contains("el.scrollLeft = orig + 1"))
        assertTrue(script.contains("el.scrollLeft = orig - 1"))
        assertTrue(script.contains("el.scrollLeft = orig;"))
        assertFalse(script.contains("scrollWidth"))
    }

    @Test
    fun `the range only names a handle the document is still holding`() {
        // The reader may have turned the page between claiming a box and asking
        // for its range; a box that has gone is not measured, and says so.
        val script = FormulaPan.rangeScript(2)
        assertTrue(script.contains("st.els[2]"))
        assertTrue(script.contains("document.contains(el)"))
        assertTrue(script.contains("return null"))
    }

    @Test
    fun `the range is measured by pushing the box hard each way`() {
        // scrollLeft runs positive in a left-to-right overflow and negative in
        // a right-to-left one, and some web views keep the older signed
        // conventions; only the page's own answer says the true span, so the
        // box is shoved past either end and whatever stuck is the range.
        val script = FormulaPan.rangeScript(0)
        assertTrue(script.contains("el.scrollLeft = o + 20000"))
        assertTrue(script.contains("el.scrollLeft = o - 20000"))
        // The current offset is read last, after the nudges have gone back, so
        // the position written is the one the finger is actually moving from.
        assertTrue(script.contains("el.scrollLeft = o; k = el.scrollLeft"))
        assertTrue(script.contains("return { lo: lo, hi: hi, c: k, v: v }"))
    }

    @Test
    fun `a scroll write sets an absolute offset and awaits nothing`() {
        // The pan writes an absolute position, so a frame does not park the
        // touch on a page round-trip — that was what made a formula trail the
        // finger and snap. A gone handle simply writes nothing.
        val script = FormulaPan.scrollToScript(3, 42.0)
        assertTrue(script.contains("st.els[3]"))
        assertTrue(script.contains("document.contains(el)"))
        assertTrue(script.contains("el.scrollLeft = 42.0;"))
        assertFalse(script.contains("return {"))
    }
}

class FormulaPanRangeTest {

    @Test
    fun `a left-to-right box reports its reachable span and where it sits`() {
        val r = FormulaPan.parseRange("""{"lo":0,"hi":120,"c":30,"v":400}""")!!
        assertEquals(0.0, r.low, 0.001)
        assertEquals(120.0, r.high, 0.001)
        assertEquals(30.0, r.current, 0.001)
        assertEquals(400.0, r.viewport, 0.001)
        // Content comes back for a rightward drag by lowering the offset, and
        // the room each way is read straight off the span.
        assertEquals(30.0, r.toLow, 0.001)
        assertEquals(90.0, r.toHigh, 0.001)
    }

    @Test
    fun `a right-to-left box is measured in the offsets it actually uses`() {
        // scrollLeft runs negative here; the range is what the page stuck to,
        // so the arithmetic needs no idea which convention it is.
        val r = FormulaPan.parseRange("""{"lo":-120,"hi":0,"c":-40,"v":400}""")!!
        assertEquals(-40.0, r.offsetFor(0.0), 0.001)
        assertEquals(-120.0, r.offsetFor(80.0), 0.001)
    }

    @Test
    fun `an offset the finger asks for is clamped to the box, not overshot`() {
        val r = FormulaPan.parseRange("""{"lo":0,"hi":100,"c":50,"v":500}""")!!
        // A hard pull one way or the other lands on the boundary, not past it.
        assertEquals(0.0, r.offsetFor(500.0), 0.001)
        assertEquals(25.0, r.offsetFor(25.0), 0.001)
        assertEquals(75.0, r.offsetFor(-25.0), 0.001)
        assertEquals(100.0, r.offsetFor(-5000.0), 0.001)
    }

    @Test
    fun `a current offset the page reported outside its own span is pulled in`() {
        // A box could be scrolled by the browser between the two pushes and the
        // read; a current outside [lo, hi] would clamp every write to it.
        val r = FormulaPan.parseRange("""{"lo":0,"hi":100,"c":250,"v":400}""")!!
        assertEquals(100.0, r.current, 0.001)
    }

    @Test
    fun `an answer that is not a range is refused`() {
        assertNull(FormulaPan.parseRange("null"))
        assertNull(FormulaPan.parseRange(""))
        assertNull(FormulaPan.parseRange("undefined"))
        assertNull(FormulaPan.parseRange("not json"))
        // A span where the high end is below the low end is not a range.
        assertNull(FormulaPan.parseRange("""{"lo":100,"hi":0,"c":50,"v":400}"""))
        // A zero viewport would divide the finger's travel by nothing.
        assertNull(FormulaPan.parseRange("""{"lo":0,"hi":100,"c":50,"v":0}"""))
        // A missing field is not a range either.
        assertNull(FormulaPan.parseRange("""{"lo":0,"hi":100,"c":50}"""))
    }
}

class FormulaPanDragTest {

    private val slop = 12f

    private fun hit(left: Boolean = true, right: Boolean = true, id: Int = 0, math: Boolean = true) =
        FormulaPan.Hit(id = id, left = left, right = right, math = math)

    private fun hits(vararg hits: FormulaPan.Hit) = hits.toList()

    @Test
    fun `nothing is claimed until the finger has passed the slop`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertFalse(drag.offer(pointers = 1, dx = 5f, dy = 1f, slop = slop, held = true))
        assertEquals(FormulaPanDrag.Mode.UNDECIDED, drag.mode)
    }

    @Test
    fun `a sideways drag over a formula with room that way is the formula's`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 3f, slop = slop, held = true))
        assertTrue(drag.panning)
    }

    @Test
    fun `a vertical drag is never the formula's`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertFalse(drag.offer(pointers = 1, dx = 3f, dy = 60f, slop = slop, held = true))
        assertEquals(FormulaPanDrag.Mode.PASSED, drag.mode)
    }

    @Test
    fun `dragging towards an edge the formula has already reached turns the page instead`() {
        // Room on the left only: the content comes back under a rightward
        // drag, and a leftward one has nothing left to show.
        val drag = FormulaPanDrag()
        drag.take(hits(hit(left = true, right = false)))
        assertTrue(drag.offer(pointers = 1, dx = 40f, dy = 2f, slop = slop, held = true))

        val other = FormulaPanDrag()
        other.take(hits(hit(left = true, right = false)))
        assertFalse(other.offer(pointers = 1, dx = -40f, dy = 2f, slop = slop, held = true))
        assertEquals(FormulaPanDrag.Mode.PASSED, other.mode)
    }

    @Test
    fun `the drag goes to the innermost box that can still go that way`() {
        // A formula inside a scrollable block of the book's own: the reader
        // is on the formula, so the formula moves, and the block only takes
        // over once the formula has run out.
        val drag = FormulaPanDrag()
        drag.take(hits(
            hit(left = false, right = true, id = 1),
            hit(left = true, right = true, id = 0),
        ))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
        assertEquals(1, drag.hit?.id)
        // The same finger going the other way has nothing in the inner box,
        // and finds it in the outer one.
        val back = FormulaPanDrag()
        back.take(hits(
            hit(left = false, right = true, id = 1),
            hit(left = true, right = true, id = 0),
        ))
        assertTrue(back.offer(pointers = 1, dx = 40f, dy = 0f, slop = slop, held = true))
        assertEquals(0, back.hit?.id)
    }

    private fun range(low: Double, high: Double, current: Double, viewport: Double = 1.0) =
        FormulaPan.Range(low = low, high = high, current = current, viewport = viewport)

    @Test
    fun `a box that runs out hands the drag to the one around it`() {
        val drag = FormulaPanDrag()
        drag.take(hits(
            hit(left = false, right = true, id = 1),
            hit(left = true, right = true, id = 0),
        ))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
        assertEquals(1, drag.hit?.id)
        // The inner box was measured with no room towards the low end, and the
        // finger is still pushing that way: that is an edge, and the outer box
        // — which can go that way — takes the drag from where the finger is.
        drag.give(range(low = 0.0, high = 100.0, current = 0.0), scalePx = 1f)
        assertTrue(drag.refused(-30f))
        assertEquals(0, drag.hit?.id)
        // The handoff cleared the range, so the new box is measured next.
        assertTrue(drag.needsRange)
        // Now pinned at the outer box's own edge with nobody left to hand to.
        drag.give(range(low = 0.0, high = 100.0, current = 0.0), scalePx = 1f)
        assertFalse(drag.refused(-20f))
        assertTrue(drag.panning)
    }

    @Test
    fun `a refusal the drag cannot use leaves it where it was`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit(left = false, right = true, id = 1)))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
        // The box has room the way the finger is going, so nothing has run out.
        drag.give(range(low = 0.0, high = 100.0, current = 50.0), scalePx = 1f)
        assertFalse(drag.refused(-30f))
        assertFalse(drag.refused(0f))
        assertEquals(1, drag.hit?.id)
        // And a drag that was never the formula's is not started by one.
        val passed = FormulaPanDrag()
        passed.take(hits(hit()))
        passed.offer(pointers = 1, dx = 3f, dy = 60f, slop = slop, held = true)
        assertFalse(passed.refused(-0.2f))
    }

    @Test
    fun `nothing under the finger leaves the gesture to the page`() {
        val drag = FormulaPanDrag()
        drag.take(null)
        assertFalse(drag.offer(pointers = 1, dx = 60f, dy = 0f, slop = slop, held = true))
    }

    @Test
    fun `the claim is kept to the end of the drag, whatever the finger does after`() {
        // A drag that curves downwards halfway through is the formula's still:
        // taking it back would cancel a touch the page is already acting on.
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
        assertTrue(drag.offer(pointers = 1, dx = -30f, dy = 90f, slop = slop, held = true))
    }

    @Test
    fun `a decision is not asked for again once it has been made the other way`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertFalse(drag.offer(pointers = 1, dx = 2f, dy = 60f, slop = slop, held = true))
        // The same finger, now travelling sideways: still the page's, because
        // the page has been moving down the chapter since the first frame.
        assertFalse(drag.offer(pointers = 1, dx = 60f, dy = 61f, slop = slop, held = true))
    }

    @Test
    fun `a second finger puts the formula down`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
        assertFalse(drag.offer(pointers = 2, dx = -40f, dy = 0f, slop = slop, held = true))
        assertFalse(drag.panning)
        assertNull(drag.hit)
    }

    @Test
    fun `a prose box is only taken from a drag that held first`() {
        // The paragraph has a wide formula run into it, but it is still the
        // reader's text: a flick that starts there is a page turn or a
        // scroll, and only a hold says the reader meant the maths.
        val quick = FormulaPanDrag()
        quick.take(hits(hit(math = false)))
        assertFalse(quick.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = false))
        assertEquals(FormulaPanDrag.Mode.PASSED, quick.mode)

        val held = FormulaPanDrag()
        held.take(hits(hit(math = false)))
        assertTrue(held.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
    }

    @Test
    fun `a formula box does not wait for a hold`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit(math = true)))
        assertTrue(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = false))
    }

    @Test
    fun `the pan starts where the finger is, not where it landed`() {
        // The claim lands on a drag that has already travelled; showing the
        // formula all of that at once is a jump the reader did not make. The
        // offset is measured from the anchor the claim set, not from zero.
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true)
        // Nothing is written until the box's range is measured.
        assertNull(drag.offset(-40f))
        assertTrue(drag.needsRange)
        drag.give(range(low = 0.0, high = 200.0, current = 100.0), scalePx = 1f)
        assertFalse(drag.needsRange)
        // The travel that made the claim owes nothing; pushing 15px further
        // leftwards reveals content to the right, so the offset climbs.
        assertEquals(100.0, drag.offset(-40f)!!, 0.001)
        assertEquals(115.0, drag.offset(-55f)!!, 0.001)
    }

    @Test
    fun `a fresh touch owes nothing to the last one`() {
        val drag = FormulaPanDrag()
        drag.take(hits(hit()))
        drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true)
        drag.reset()
        assertEquals(FormulaPanDrag.Mode.UNDECIDED, drag.mode)
        assertNull(drag.hit)
        // The boxes went with it: a page recycled into another chapter must
        // not be panned by a handle the new document never handed out.
        assertFalse(drag.offer(pointers = 1, dx = -40f, dy = 0f, slop = slop, held = true))
    }
}
