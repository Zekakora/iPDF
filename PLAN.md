# Project plan

## Product target

A private, offline-first PDF reader for one device: Redmi K Pad 2, used with Xiaomi Focus Pen (standard Focus Pen, not Focus Pen Pro). The first release is for personal use and does not include cloud sync or cross-device editing. The saved PDF itself is the cross-device interchange format.

## MVP user flow

1. Choose a PDF through Android's document picker; retain its URI permission and list it in Recents.
2. Read with page navigation, zoom, and multiple open/recent documents.
3. Enter pen mode. Pen writes; eraser mode removes strokes; fingers remain available for page navigation/zoom when not writing. Provide an optional strict pen-only setting for writing.
4. Autosave each completed stroke to a recoverable sidecar journal, then serialize standard PDF `/Ink` annotations to the original PDF using a safe temporary-copy/replace flow where the document provider permits it.
5. Reopen the saved PDF on a desktop PDF reader and verify annotations are visible.

## Scope

### Included

- Android app, optimized for Redmi K Pad 2.
- Multiple PDFs via recent list and in-app document switching.
- Continuous page reading, page jump, zoom, and last-position restore.
- Pen drawing, width/colour selection, stroke eraser, undo/redo.
- Palm/finger rejection strategies and a pen-only writing model (fingers navigate, stylus draws).
- Crash-recoverable autosave and standard PDF Ink annotations.
- Offline operation; original documents remain under user-selected storage permissions.

### Deferred

- Windows/macOS app, cloud sync, OCR, text markup, bookmarks, signatures, collaboration, handwriting recognition, Xiaomi-only squeeze/gesture shortcuts, and broad-device certification.

## Technical direction

- Native Android with Kotlin and Jetpack Compose for the app shell/toolbars; one custom `View`
  owns page rendering, vertical scrolling/flinging, horizontal panning and pinch zoom.
- PDF rendering uses the platform `PdfRenderer`, with page bitmaps created lazily and kept in an
  LRU cache. Ink is stored in page-relative PDF points and drawn as an overlay, independent of
  zoom, scroll and device pixels.
- PDF writing uses PDFBox-Android (`com.tom-roush:pdfbox-android`) to append standard `/Ink`
  annotation dictionaries with colour, width, opacity, blend mode and `/AP` appearance streams.
  The app renders from a private pristine copy of the document, so the provider file can be
  rewritten in the background without disturbing the visible pages.
- Strokes are journaled as JSON before the PDF write; on launch the app restores the journal, or
  re-reads its own annotations from the PDF if the journal was lost. A failed write restores the
  pristine copy and reports the failure.
- Document metadata (URI, display name, last viewport, save state) lives in app storage; the PDF
  remains the source of truth for saved annotations.

## Milestones

1. **Foundation (complete):** project scaffold, product plan, build setup, and a debug APK build/install path.
2. **PDF spike (complete):** system picker, multi-page continuous rendering, tabs, recent URI memory,
   scroll-position restore, custom gesture surface with slow-drag handoff, fling, focal pinch zoom,
   horizontal panning and double-tap zoom.
3. **Ink spike (complete for pen/highlighter/eraser/lasso):** stylus-only drawing, pressure-weighted
   width, user colours with opacity, highlighter, pixel or whole-stroke erasing, undo/redo for
   strokes, erasing and lasso moves, lasso selection plus move and proportional resize (stylus or
   finger), palm/finger rejection and live preview.
4. **Annotation persistence (complete for standard Ink):** journal with a recovery copy, PDFBox
   `/Ink` export with `/AP`, pristine working copy, read-back of our annotations, and restore on
   failed writes. Incremental updates, encrypted PDFs and provider-specific replace semantics remain
   open.
5. **Reader workflow (complete):** recents/library, tabs, collapsible pen dock, vertically expanded
   preset rail, an optional detached draggable preset bubble, brush editor, and a serial save-status
   indicator.
6. **Device acceptance (in progress):** verified on the Redmi K Pad 2 for slow/fast scrolling,
   double-tap zoom, horizontal pan, draw, highlighter, erase, undo/redo, save, restart, tab
   switching, the vertical preset rail, the detached draggable preset bubble, lasso selection and
   lasso drag/undo. Still to cover: real two-finger pinch on device, rotation, very long documents,
   low storage, read-only providers, and visible annotations in at least one desktop reader.
7. **Course library (first pass complete):** SAF folder import with a cached index; semester prefix
   or suffix on course folder names; second-level folders map exactly to Lecture / Exercise / Exam /
   Others (anything else lands in Others with its original name); recursive collapsible folder tree;
   course + type + semester filters with facet counts and search; right-hand history; debug APK
   builds and unit tests pass. Real `AAAMaster` (123 PDFs) imported, tree/filters/history/PDF open
   verified; remaining: rename the course folders to the four exact names and rescan (the current
   unrenamed tree puts 93 files in Others).
8. **Themes, tree controls and default PDF handler (complete):** six light palettes including Lake
   Green, opened by long-pressing the library `+`, applied immediately with persistence and a
   matching status-bar tint; one-tap Collapse / Expand for the whole folder tree; `ACTION_VIEW`
   filter for `application/pdf` (content/file) with `singleTop` + `onNewIntent`; the app was selected
   as the system default PDF app and opened a PDF from the file manager via `ACTION_VIEW`.
9. **iPDF rebrand (complete):** application label **iPDF**, package/namespace **`com.doge.ipdf`**;
   `logo.jpg` turned into adaptive launcher icons (transparent foreground + legacy rounded PNGs)
   through `tools/generate-icons.ps1`; the Library header is a plain `iPDF` wordmark; Lake Green is
   the fallback theme; new package installed from the local APK installer because HyperOS blocks new
   ADB installs; old `com.example.redmipdf` removed, library re-granted and the new package verified
   as the default PDF app.
10. **Motion polish (complete):** the header logo was removed; the library/reader switch,
   library body states, folder-tree rows, chevrons and filter chips now animate (150–300 ms), with
   the launcher icon regenerated from the latest artwork. Returning to the library is a plain
   cross-fade (no zoom or slide). Verified on the Redmi K Pad 2: header wordmark, reader
   open/close, tab switching, row collapse, and the new icon on the launcher.
11. **Stroke stabilization (complete):** a distance-adaptive stabilizer in `StrokeStabilizer.kt`
   filters stylus samples before they become ink (tremor is absorbed, deliberate movement catches
   up, the stroke still ends on the lift point). The strength is a persisted Off → Strong slider in
   the reader's top-right pen settings, default Light 30%, and is bypassed entirely at Off.
   Covered by six unit tests plus the filter being wired through `PdfPageView`/`ReaderScreen`.
12. **Storage access, reliable saving and first-touch marker (complete):** the app declares and
   asks for all-files access because PDFs opened from a file manager only grant reading; saves try
   the URI first and otherwise write the real file on shared storage (including HyperOS'
   file-explorer URIs), and recovered journal strokes now export on open instead of sitting at
   "Not saved". Documents open with **no tool selected**; the first stylus contact selects the
   marker (first pen preset) and draws with it, and tapping the selected tool again clears it. The
   launcher icon is a single legacy PNG generated from the uncropped `logo.jpg` — no adaptive-icon
   layers, so the launcher no longer stretches it across the adaptive canvas. Verified on device by
   opening `01-Introduction.pdf` from the HyperOS file manager and watching the recovered stroke
   export (size/mtime changed, chip reads "Saved").
13. **Shared palettes (complete):** the pen and the highlighter each own one persisted colour row;
   every preset of the same tool draws its swatches from that row. Long-pressing a swatch and
   picking a colour overwrites that swatch, updates every preset that used it, and leaves the new
   colour in place instead of only attaching it to the open preset.
14. **Calendar and tasks (complete):** History and the schedule pane split the right column roughly
    half and half. To-do (three visible rows, scrollable) sits above a date-grouped agenda. The
    agenda subscribes to the RWTHonline iCalendar feed over HTTPS, keeps the ongoing/next class at
    the top, removes finished classes and re-anchors as time passes; today's date is the sticky
    heading and `Tomorrow` / later dates are divider rows. Tapping a class filters the library to
    its mapped course. Settings store the URL, parse course names and map each library folder to a
    course manually (with auto-match). Tasks are added from the small To-do `+`, sorted by due time,
    light-orange-highlighted for today and error-tinted when overdue; long-pressing `+` opens all
    tasks (including completed) or settings. Verified in landscape on the tablet: live feed (261
    events / 10 courses), class-to-library filtering, date separators, task add/scroll/complete and
    the all-tasks view.
15. **Fast open and tab switching (complete):** page sizes are measured once off the UI thread and
    cached per document; our and foreign annotations are read in one PDFBox pass and only on the
    first open (a per-document scanned marker skips later scans); page bitmaps render on a daemon
    worker with white placeholders so `onDraw` never blocks; tab/home/open navigation flushes only
    the journal and lets the PDF export continue in the background. Measured on a 288-page,
    49.9 MB PDF: first open ready in ~0.9 s, relaunch open in ~0.18 s, tab switch in ~5 ms plus an
    asynchronous first-page render instead of a ~0.5 s page-size loop on the UI thread.

## Acceptance criteria

- Can open at least two PDFs, switch between them, and return to each document's prior reading position. (verified on device)
- Pen strokes stay registered to the page through zoom and scrolling; erase and undo work. (verified on device)
- Writing does not create accidental finger marks during normal palm contact; strict pen-only mode is available if device-level rejection varies. (fingers never draw; additional palm heuristics can be added)
- Completed strokes survive force-close/relaunch, including recovery from an interrupted PDF write. (journal and PDF read-back implemented; force-close tested)
- Saved output remains a valid PDF with visible standard Ink annotations in at least one desktop reader. (valid PDF and `/Ink` + `/AP` verified structurally; desktop visual check still needed)
- Existing PDF pages and prior annotations remain intact after saving. (page content streams byte-identical and pre-existing annotations preserved in testing)
- The agenda keeps the current/next class on top as time passes, a class tap filters the library to the mapped course, and today/overdue task styling is visibly different. (verified in landscape on the tablet)

## Risks / decisions to validate early

- PDF modification through Android document providers varies. Some providers cannot atomically replace a document; test local storage first and retain a recoverable journal.
- PDFBox full rewrites invalidate digital signatures and may be slow/memory-heavy on very large documents; tile rendering and incremental saves are future work.
- Palm rejection is partly device/OS behaviour. Fingers are navigation-only and ignored while the pen is down, with a short post-pen window.
- Xiaomi Focus Pen input is consumed through standard Android stylus events. Xiaomi-specific gestures are outside MVP.

## Immediate next step

1. Rename the real `AAAMaster` course folders to the four exact category names (Lecture / Exercise /
   Exam / Others, with the semester prefix or suffix), then run the library import / filter / tree /
   history pass on the tablet.
2. Verify two-finger pinch on the tablet and compare focal anchoring against double-tap zoom.
3. Open the saved PDF in one desktop reader (Acrobat/Chrome) and confirm the handwriting is visible.
4. Then improve zoom sharpness with tiled rendering and add per-point pressure plus low-storage/provider failure tests.
