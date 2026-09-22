# 37. Mathematics, typeset in the book's own page

Status: accepted

## Context

Mathematics books read badly. A chapter converted from LaTeX carries its
formulae as literal text — `$$\int_0^1 \frac{x^4}{1+x^2},dx$$` — so a
reader who has bought a mathematics book is shown a page of backslashes,
and the one thing on the page they needed is the one thing that did not
arrive. The same books also ship MathJax payloads whose renderer has to be
fetched from a server, which a book read on a train does not have, and
MathML, which the web view draws but which nothing sizes to the column.

A formula that *is* drawn has a second problem. A display equation is
one unwrappable line, and a phone column is 40 characters wide, so almost
every interesting formula is wider than the page it belongs to.

Three facts decide everything else:

- **Reflow already handles the overflow, badly.** In a paginated book the
  multi-column box breaks a too-wide line across columns, which puts the
  end of an equation on the next page with nothing to say it belongs to the
  one the reader left. In a scrolled book the line widens the document
  itself, so the whole page slides sideways under a thumb that was reaching
  for one formula, and the part off the right edge is gone until the reader
  works out how to get it back.
- **A scroll box would not be reached anyway.** `overflow-x: auto` on an
  element is a real scroll container, and a reader with a mouse can drag its
  scrollbar; the touch that would pan it is the one at issue, and the next
  bullet is where it goes. `WideContentFit` exists because the *root*
  overflow, unlike an element's own, paints over the page after it.
- **Readium decides a page turn without asking the document.** Read off the
  shipped 3.3.0 classes: `R2WebView.onTouchEvent` claims a horizontal drag
  from `getScaledPagingTouchSlop()` and, on lift, turns the page when
  `determineTargetPage` sees more than 25dp of travel at more than 400dp a
  second. Nothing in that path evaluates a line of JavaScript, asks what is
  under the finger, or looks at whether the document could have scrolled
  something instead. It behaves the same in both modes: the scrolled branch
  measures the *vertical* travel and turns at 200px.

So the answer cannot be "put the scroll box in and let the browser handle
it", because in a turned book the browser never gets the touch.

## Decision

**1. Formulas are typeset by KaTeX, bundled, at runtime.** `MathTypesetting`
adds a stylesheet and two scripts to the book's own document through
Readium's asset server — `https://readium_assets/…`, which is what
`servedAssets` makes reachable and which answers with a permissive CORS
header. Nothing is downloaded, so a mathematics book reads on a train, and
nothing new is added to the dependency graph: KaTeX is assets, its licence
is listed on the licences screen, and the app stays buildable offline.

**2. It is injected rather than shipped into the resource**, for the reason
`WideContentFit` gives: Readium decides whether to link its own stylesheet
by looking for `<style` in the resource, so a resource rewritten ahead of it
would cost an unstyled book its entire typography.

**3. The pass joins `repairPage`, and runs first.** It is the only one of
those repairs that can make a box too wide for the page, and the only one
that can make a box that was too wide fit, since rendering a formula
replaces a line of literal text with something that has a shape. What it
leaves behind is what the fit measures next, so the order is not arbitrary.

**4. The block a formula sits in is marked as a pan box**, with
`overflow-x: auto` and no scrollbar. A display equation is its own block, so
that is the box; a formula run into a line of prose cannot wrap, and makes
the *paragraph* too wide, so the paragraph is the box — boxing the formula
alone leaves the paragraph pushing the whole document sideways, which is the
same complaint with a new name.

The two are not treated alike. A box holding nothing but mathematics is also
left-aligned, pinned `direction: ltr` and told not to break across a page
column, because it is one thing and a viewport onto left-to-right material.
A box with prose in it gets none of that: an Arabic book's text would come
out reversed, and a paragraph that refuses to break across a column leaves a
page blank.

**5. A horizontal drag starting on a pan box moves the box, not the page.**
`FormulaPan` asks the document what is under the finger as the finger
lands and, if the drag turns sideways over something pannable, takes the
touch in the reader's existing `pointerInput` loop at
`PointerEventPass.Initial` — ahead of both the web view and Readium's own
gesture code, the same place ADR 22 took the pinch.

Over a box that holds prose as well as mathematics, the drag is only taken
if it began by sitting still: a flick that starts on a line of text is the
reader going somewhere, and holding every such flick for the probe would
make a mathematics book sluggish to turn in exactly the pages that are
mostly reading matter.

**6. One rule for both reading modes.** The claim is Liseur's, made above
the navigator, so a dragged formula reads identically whether the chapter is
turned in columns or run in one long scroll, and switching modes mid-book
teaches a reader nothing new.

**7. Everything else is left exactly as it was.** A vertical drag is still
the chapter's. A sideways drag over a formula that has already reached its
edge, with nothing around it to take the drag on, is still a page turn. A
second finger puts the formula down and the pinch goes on being ADR 22's. A
tap is still how the book is read.

## Design

### Delimiters, and the one that is left out

`$$…$$`, `\[…\]`, `\(…\)`, `\begin{equation|align|alignat|gather|CD}`, and
`<script type="math/tex">` — the last because it is what a MathJax-era
export leaves behind, sometimes with the renderer's own output beside it.
The source is hidden through a token-owned attribute rather than by styling
the author's element.

Single `$…$` is deliberately **not** a delimiter. A book that prices
anything in dollars would have its prose eaten: `$5 and not $6` becomes one
malformed formula spanning a sentence, and every number after it is in the
wrong place. The false positive costs a reader their text; the one it
refuses to support costs them a formula they can still read as text.

### Measuring a box that has not been drawn yet

KaTeX's glyphs arrive after the markup that asks for them, and a formula
measured with a substitute face is measured narrow — maths glyphs are wide
and stack. Such a box reads as fitting, is never marked, and the reader is
left with a formula cut off with nothing to drag. So the pass asks once more
once `document.fonts` reports `loaded`, which is the answer `apply()` waits
on through the same bounded poll it already uses for the scripts.

### Which way a box can go is the page's answer, not arithmetic

`scrollWidth - clientWidth` says how much overflow there is and nothing
about which end of it the reader can still reach. That depends on the
writing direction — a right-to-left box overflows to the left and carries a
negative offset in some engines — and on which of the two offset conventions
the web view's version happens to implement, which has changed once already.
So the probe nudges `scrollLeft` by a pixel each way inside one synchronous
block, reads what stuck, and puts it back. Whatever the browser means by the
property, it has just told the truth about it.

A formula's own box is pinned `direction: ltr` regardless: a dragged formula
is a viewport onto left-to-right material whatever the prose around it runs,
and that is what makes the nudge and the drag agree in every book. A
paragraph is not pinned, because its text is the reader's; the nudge is
already true for it either way.

### A stack of boxes, and which one the finger meant

Under one point there can be more than one pannable box — a chapter's own
scrollable block, and the formula inside it — so the probe answers with the
whole stack, innermost first, rather than the first hit. The drag goes to the
innermost box that can still go that way, and when that box reaches an edge
the box around it takes the drag on. Which edge it reached is no longer
something the page has to report: the pan writes absolute positions (below),
so the drag knows where the boundaries are from the range it measured once,
and a box is "out" the moment the position it would write is pinned against
one.

### The pan writes a position, not a delta

A formula that trails the finger and snaps forward when the drag settles is
paying for a round-trip it does not need to. The first version nudged the box
by a delta and asked the page how far it actually went, because only the page
knows its own bounds — so every frame parked the touch on a
`evaluateJavascript` call, its JSON answer parsed back on the main thread,
and only then read the next piece of the finger's travel. A touch fires
faster than that round-trip completes, so the formula lagged and lurched.

The pan now measures a box's full `scrollLeft` range once, as the drag claims
it (and again on an edge handoff, for the next box), and writes an *absolute*
position every frame after that. An absolute write only has to reach the
document after the one before it, and the document's own thread keeps that
order, so it is fire-and-forget: nothing is awaited per frame, and the formula
keeps up with the finger at the touch's own rate. The range has to be
*measured* rather than computed — `scrollLeft` runs positive in a
left-to-right overflow and negative in a right-to-left one, and some web views
keep the older signed conventions — by pushing the box hard past each end and
reading back what stuck, in one synchronous block, the same trick the probe
uses to read direction. That single measurement is also what lets the app
clamp the position and spot an edge itself, without asking the page again.

### Why the pan starts where the finger is

The claim lands on a drag that has already travelled, and a formula handed
all of that at once is a jump the reader did not make. So the position written
is measured from where the finger was when the claim landed — `FormulaPanDrag`
anchors there — and the range arrives a beat later from the one measurement
above, so a frame that lands before it simply writes nothing rather than
jumping to a place the page has not been asked about yet.


### What is held, and what is not

A sideways drag whose answer has not arrived is held for 120ms, in both
modes. It has to be held in the scrolled one too: the web view's answer to an
unclaimed sideways drag over a wide page is to move the whole page, which is
exactly what the marking above works to make impossible and what a reader
reports as the book sliding out from under a thumb that was only reaching for
one equation.

The hold is bounded, and it is paid for only on a page that has a pan box on
it at all — a resource-wide question asked once off the touch path, so a
novel with no mathematics in it runs no script on a touch and turns exactly
as fast as it did. Over a box that is all formula, the claim is made the
moment the drag reads as sideways. Over one that also holds prose, the drag
has to have begun with a hold, measured from the finger's *first movement*
rather than from now — otherwise a reader who turns pages at a thoughtful
pace would find every turn captured by the maths.

A document that will not answer is a document that turns the page.

### The ink a formula paints outside its own box

KaTeX's stylesheet makes the `.katex` span inside a `.katex-display` a block
of its own, so the walk that looks for a formula's containing block stops at
the formula itself. That box is the wrong thing to hand a reader: the rule
over a radical and the head of a superscript are painted above it — by up to
13px at a 34px body size — and a scroll box clips whatever sticks out of it.
Readers see this as glyphs with their tops missing. The walk now steps over
exactly that one span, to the display wrapper, which carries the same ink
inside margins of its own. Boxing the paragraph instead would be a `flow-root`
and would stop the wrapper's margins collapsing through, re-laying out the
whole page for the sake of one formula.

The overhang does not stop at the wrapper either, and it cannot be answered
by `overflow-clip-margin`: beside a scrolling axis, `overflow-y: clip`
computes to `hidden` and the property goes inert. Nor by padding alone —
padding moves the glyphs down. What works is padding the wrapper half an em
each way, painting it back up by the same amount with `position: relative`,
and paying the whole line back with a negative bottom margin — the one
margin a collapse cannot silently absorb, since negatives always count.
Measured against the unmarked page: no glyph moves, no following block moves,
the clip rect covers the ink.

### The formula that widened the page

A box wider than the page can be found by asking it how far its content spills
past its own edge — `scrollWidth - clientWidth`. That question has an answer
only when the box was told how wide to be. Inside a container that was left to
size itself to its content — a table cell, a flex item, an `inline-block` —
the container grows to hold the formula, so the formula spills past nothing
and the difference is zero. The box is never marked, and the cost is not paid
by the formula alone: the reader lays the page's text into one column, and
that column is as wide as the widest thing in it. A single unmarked formula in
a content-sized container widens the column, and every line of prose on the
page is clipped at both edges against the page's own measure. That is what a
reader photographs and sends: not one formula off the side, but a whole page
unreadable on both sides.

So the test for "too wide" is made against the page, not against the box's
container. `pageWidth()` reads the body's content box — its `clientWidth` less
its horizontal padding — falling back to the document element; a column is a
fragment of that body, so the body's measure is also the column's. A box is a
candidate when its content is wider than the page *or* than itself. Marking it
alone is not enough, though: a box that never spilled has nothing to scroll,
so the column stays wide. The box is also given the page's width back — a
`width`/`max-width` of a root custom property, `--liseur-pan-w`, minted per
pass and set `!important` — so it scrolls inside the page instead of stretching
it. The cap comes off before a box is measured, so a box that no longer needs
it is not held wide by the very rule that was meant to narrow it, and a cap is
taken off any box that is no longer marked. The property is a plain custom
property rather than a per-box length because the page is one measure and it
is written once, not once per formula.

### A cold render sliced so the page keeps turning

Rendering an eighty-formula chapter was one task: 2.4s on a CPU throttled to
a mid-range phone, and a page turn that lands inside it waits. The pass now
renders block by block and hands the thread back every 24ms, which is also
when the browser lays out what the render invalidated — on its own frames,
instead of one forced flush inside a measuring pass. The longest single task
falls from 483ms to 81ms, and the whole chain finishes faster besides.

The chain answers `loading` until the last formula is down, so it runs on the
same poll that already waits for the assets — and the wait has to be long
enough for a whole render, because giving up part-way through would close the
reflow scope while the page was still moving underneath it. That is the one
outcome worse than a slow chapter: the navigator reads the later movement as
a page turn the reader never made, and their place is not put back. A render
that moved the page is announced once as `changed`, through a flag spent on
the first `done` it meets, so the pass after it settles rather than moving
the reader again on every turn.

### A render that dies mid-chapter must be picked back up

The cold render is a chain of `setTimeout` slices, and it is the one part of
this pass driven from *inside* the page rather than by a caller. That makes it
fragile in a way the rest of the state machine is not, in two ways that both
leave the reader on a page whose maths is half rendered and half still literal
TeX — and, crucially, with nothing left to finish it.

The first is a race between the chain and a re-entry. A jump re-runs the pass,
and if that pass lands in the gap between two slices — the chain flagged as
running but no timer currently queued — an earlier answer of "still loading,
go away" let the pass return without arming anything. The old chain had already
handed back its last slice, so the render was orphaned. This is most visible on
a jump *within* one chapter, where the resource never changes and the usual
"different document" prompt that would have retried never fires.

The fix is a liveness stamp rather than a flag. Each slice refreshes
`st.alive`; a pass that meets a running chain left alone if the stamp is fresh
(a slow device is not a dead one), and re-arms a fresh chain if the stamp has
gone quiet for longer than `RENDER_STALL_MS`. Re-arming is safe against racing
the old chain because the old one is by definition not running, and because
auto-render is idempotent — it skips a block it has already typeset.

The second way is a single formula that throws. auto-render is called block by
block, and an error escaping one block used to reach the chain's outer catch,
skip `finish()`'s successors, and abandon every block after it. Each block is
now rendered inside its own guard, so a formula that cannot be parsed is left
as the text it is and the chain carries on to the rest of the chapter.

The other half of the fix is *where the repair is allowed to fire*. The
position/layout collect loop keys each pass by the web view and the resource it
names, and repairs a resource once per key. A jump publishes the target position
before the web view under the reader has loaded it, so the pass that first sees
the new key is looking at a document still in transit — the page being left. The
bug was committing the key against that transitional document: the real resource
then lands on the *same* view under the *same* key, the once-per-key guard returns
on every later pass, and the page is never typeset. Reading front-to-back works
because there the view already shows the resource; jumping does not. The gate is
`ResourceAddress.shows(web.url, href)` asked *before* the key is committed — a
pass over a document that does not yet show what the position names returns
cheaply, and the next layout pass, once the resource has landed, does the repair
for real. The wait for the sliced render then runs inside that real pass, on the
shared collect loop, exactly as the assets' load always has.

The layout-generation key is what re-arms a repair for an *in-place* reflow —
a font-size or margin change that `submitPreferences` applies to the live
fragment, where neither the view nor the `href` changes, so `web to href` alone
would match the key the previous layout committed and typeset nothing again.
A scroll<->paged switch, by contrast, changes `effectiveScrolling`, which is
itself a key of the repair `LaunchedEffect`, so that loop restarts and its
`fitted` guard resets regardless — and that is why the generation alone did
*not* fix the mode-switch case a reader reported, where maths went raw forever
after a switch yet came back on reopening the book.

The real mode-switch failure is one rung down, inside the page. The state
machine latches an untouched chapter to `empty` once `readyState` is `complete`
(MathTypesetting `scan`), and `empty` was, until now, a life sentence for the
`window`: every later pass short-circuited on `st.state === 'empty'`. A mode
switch re-lays the chapter *in place* under that same window, and the repair
pass that follows can land while the body is still `complete` but not yet
filled with the chapter's text — so the first look sees no `$$`/`\(`, latches
`empty`, and the formulas that arrive a moment later are never scanned again
for that window. Reopening fixes it because that hands back a fresh web view
whose `window.__liseurMath` starts blank. The fix makes `empty` a claim about
*the content that was scanned* rather than about the window: the latch records
a cheap signature of the body (`document.body.childElementCount`), and the scan
branch is re-entered whenever the body no longer matches what was latched. It
is O(1) and stable for a settled page, so a genuinely maths-free chapter still
answers `empty` on every pass instead of re-scanning — verified headlessly: a
complete-but-under-filled body that is then swapped for a maths chapter renders
all 60 formulas under the fixed script and zero under the old one, while a
stable plain page stays latched.

### What a book's Content-Security-Policy can refuse

A book may refuse the injected stylesheet, in which case the maths is not
typeset and the page is untouched, and the failure is reported as one rather
than as an empty chapter. `evaluateJavascript` is used rather than
`addJavascriptInterface`: nothing is exposed to the document's scripts, so a
hostile book has one more thing it cannot reach.

### Deliberately not here

- **No setting.** It is not a preference but a repair, like the footnote and
  table passes: a reader who does not know the word "LaTeX" should not have
  to find a switch to read a mathematics book, and a reader who does should
  not find their formulae missing because a switch was off on a book they
  opened once. If that changes, it belongs in the Advanced sheet.
- **No renderer switch.** KaTeX is the one that ships as plain assets under
  an MIT licence; MathJax needs a browser bundle and a server.
- **No zoom, no full-screen formula view.** A dragged formula is the whole
  feature. A tap-to-enlarge would collide with the tap that shows the
  chrome, and with ADR 22's long press.

## Consequences

- A chapter with mathematics in it is now measured after its fonts land, so
  the repair can wait on it. That wait covers a sliced render of a dense
  chapter as well as the assets' own load, so it is bounded at 20s rather
  than the 4s a local file load needed; it is inside the reflow scope that
  already holds the reader's place, where a page that has not settled has
  nowhere else to be. The pass only enters that scope once the web view
  actually shows the resource the position names, so a jump does not spend
  the wait on a document still in transit and then refuse to typeset the one
  that lands.
- A page is no longer left wide by a formula it holds. The mark is against the
  page's own measure rather than the formula's container, and a box that
  widened the page is capped back to it, so the prose keeps the column it was
  laid out for. The cap is a repair like the rest of the pass: it is put on
  for the page that needs it and taken off when the page no longer does.
- 600KB of assets ship with the app for the maths fonts and library —
  `woff2` only, since the web view in everything we support reads it.
- The pointer loop now consumes a third kind of gesture. It is claimed under
  the same conditions the image viewer is, and released the same way, and
  `SelectionHandleFix` continues to work because a selection still refuses
  the turn — and the pan — exactly as it refuses everything else.
- The three reading modes that a formula can appear in — paginated, scrolled,
  fixed-layout — are covered by the same code, but only the first two can
  show the pan boxes: `evaluateJavascript` answers nothing on a fixed-layout
  book, which is the hole `ImageAtPoint` documents. Fixed-layout mathematics
  books are therefore typeset but not pannable, and that is the same
  limitation every other runtime repair already has.
