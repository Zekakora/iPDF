# iPDF

A local-first Android PDF reader focused on the Redmi K Pad 2 and Xiaomi Focus Pen.
Android package: `com.doge.ipdf`.

## MVP

- Open multiple PDFs and switch between recent documents.
- Read, zoom, and navigate pages.
- Write with the Xiaomi Focus Pen, erase strokes, and ignore accidental finger/palm input while in pen mode.
- Autosave recoverable editing data, then store strokes as standard PDF Ink annotations so desktop PDF readers can display them.
- Resume each document at its last page and zoom/scroll position.

## Current state

The app is usable as a reading and handwriting prototype on the target tablet.

- **Library:** import a course folder through Android's folder picker and keep a local index (no PDFs
  are copied). Course folders can carry the semester as a prefix or suffix (`26ws-HPC`,
  `Power Electronics-27ss`). Only second-level folders named exactly `Lecture`, `Exercise`, `Exam`
  or `Others` are categorised; anything else stays under `Others` with its original folder name.
  The left pane shows a recursive, collapsible folder tree and filters by course / type / semester
  with counts and search; the right pane keeps the last 40 opened PDFs. Rename the real course
  folders to those four exact names before importing — otherwise those folders land in `Others`.
  A `Collapse` / `Expand` button next to the result count folds or unfolds every course, category
  and subfolder in one tap.
- **Themes:** six light palettes — Purple, Lake Green, Ocean Blue, Warm Sand, Sakura and Graphite —
  selected by long-pressing the library's `+` button. The choice applies immediately, persists, and
  also tints the status bar; Lake Green is the default palette and a mint/teal scheme tuned for long
  reading sessions.
- **Calendar & tasks:** under History, the right column is split roughly half and half. History is
  above; below it, To-do shows three rows at a time (scroll for more) and the agenda follows. The
  agenda reads an iCalendar subscription over HTTPS, groups classes by date and keeps current/next
  classes on top; today's date is the sticky first heading and `Tomorrow` / later dates appear as
  divider rows while scrolling. The To-do header's small `+` creates a task; a long press opens
  **All tasks** (overdue, today, upcoming, completed) or **Calendar & task settings**. Settings
  store the feed URL, list the parsed course names (marked **Mapped** or **No folder**) and let each
  library folder be mapped to one course by hand (plus a best-effort auto-match). Tapping a class
  also tolerates a stale punctuation/whitespace course key or a matching folder name and repairs
  that mapping in place. It filters the library to the mapped course. Tasks are sorted by due time;
  today's tasks use a light-orange highlight and overdue tasks an error colour. The personal
  RWTHonline feed is pre-filled as a first-run default and can be replaced or cleared in settings.
  Feeds are cached for four hours.
- **Branding:** the app is **iPDF**, package `com.doge.ipdf`. The library header is a plain `iPDF`
  wordmark, and the launcher icon is a plain PNG generated from the untouched `logo.jpg` by
  `tools/generate-icons.ps1`. There is deliberately **no adaptive icon**: the whole square is drawn
  (never cropped) at 80 % inside a rounded plate so the launcher does not stretch it over the full
  adaptive canvas. Replace the logo and re-run the script before building to ship the new mark.
  Note that HyperOS keeps a launcher icon cache across `install -r`: restart the launcher
  (`am force-stop com.miui.home`, then Home) or reboot the tablet to see the new artwork. The
  launcher also normalises the legacy icon to its own icon cell, so transparent padding around the
  plate is trimmed — the artwork scale inside the plate is what changes the visible mark size.
- **Default PDF viewer:** the app registers `ACTION_VIEW` for `application/pdf` (content and file
  schemes) with `singleTop` re-delivery, so it can be chosen as the system default PDF app from a
  file manager. The incoming URI is opened as a tab and added to history.
- **Reading:** continuous page rendering with lazy per-page bitmaps, finger scrolling with fling,
  slow-drag handoff, focal two-finger zoom, horizontal panning when the page is wider than the
  screen, double-tap zoom, and per-document position/zoom restore.
- **Loading and switching:** page sizes are measured once and cached, annotation adoption is a
  single PDFBox pass on first open (later opens skip the scan entirely), and page bitmaps render on
  a background worker while white placeholders fill in. Switching tabs waits only for the small
  stroke journal; the heavier PDF export continues in the background.
- **Foreign ink annotations:** standard `/Ink` and `/Highlight` annotations created by other apps
  are adopted into the editable stroke list with PDFBox on first open (grey/RGB/CMYK `/C`,
  `/CA` opacity, `/BS /W` width; highlights become highlighter strokes along their quad). They can
  be erased, undone, moved and resized like native strokes. The journal records `foreignAdopted`,
  so they are adopted once; the first export removes the original foreign annotations and writes
  the adopted strokes as this app's `/Ink` annotations to avoid duplicates (highlights become
  translucent Ink on desktop). Pages with a non-zero `/Rotate`, appearance-stream-only annotations
  and other subtypes (text, shapes) are left untouched and are not adopted.
- **Pen:** a collapsible floating dock with pen, highlighter, eraser and lasso, stacked undo/redo.
  Selecting a pen, highlighter or eraser shows four presets in a vertical rail; every swatch shows
  its size in the bottom-right corner, without a selection ring. Tapping a preset selects it and
  tapping the selected preset opens its editor. Pen/highlighter presets expose width and colours:
  each tool has one shared colour row, and long-pressing a swatch opens a free HSV wheel with
  brightness and opacity whose result overwrites that swatch for every preset of the same tool.
  The eraser reuses the
  same rail and editor, exposing the tip size and a choice between erasing pixels (only the part
  under the tip disappears, splitting the stroke) and erasing whole strokes. A setting can detach
  the presets into a draggable horizontal bubble. Pen strokes are pressure-weighted, colours and
  opacity are honoured on screen and in the PDF, the eraser and lasso edits are undoable, and the
  lasso selects strokes, moves them by dragging inside the selection and resizes them
  proportionally with the four corner handles (both with the stylus and with a finger, each as one
  undoable action). Fingers never draw, so palm contact cannot leave marks; finger gestures are
  ignored while the pen is down and briefly after it lifts. Stroke stabilization is adjustable from
  the gear in the reader's top bar (Off → Strong, persisted): it chases the pen with a
  distance-adaptive filter, so hand tremor is absorbed while deliberate movement still catches up,
  and the stroke lands exactly on the lift point. A document opens with **no tool selected**; the
  first stylus contact then selects the marker (the pen tool's first preset) and draws that stroke
  with it. Tapping the already-selected tool in the dock leaves no tool selected again, and the
  physical eraser tip still erases.
- **Storage access:** the app declares `MANAGE_EXTERNAL_STORAGE` and asks for it on launch (the
  system "All files access" page) because a PDF opened from a file manager only arrives with a
  read-only URI grant. Saves therefore try the content URI first, then write the real file on shared
  storage through the all-files permission (external-storage and file-manager document URIs,
  including HyperOS' `com.android.fileexplorer.myprovider`). Only the PDF the user opened is ever
  rewritten; nothing else is scanned or uploaded. On grant, pending saves are retried immediately.
- **Stylus button:** Xiaomi's official PenEngine touch-film SDK
  (`com.miui.penengine.touchfilm.MiuiTouchFilmUtils`) is bridged through reflection and registers
  the pen's touch-film gestures; `SWITCH_BETWEEN_BRUSH_AND_ERASER` (and the previous-brush gesture)
  drive the pen ↔ eraser toggle, remembering the last pen/highlighter. Drop the official
  `PenEngine-release_*.aar` into `app/libs/` to enable it (it is not on Maven and not bundled in the
  system jar). Without the AAR, a raw-key fallback handles `KEYCODE_F19` / standard stylus button
  codes, but HyperOS reserves the physical button for Quick Note/Screenshot before third-party apps,
  so the SDK is the supported, non-root path.
- **Saving:** strokes are journaled locally as they are drawn and written back to the original PDF
  as standard `/Ink` annotations with `/AP` appearance streams through PDFBox. The app keeps a
  private pristine copy for rendering, so a save never invalidates the visible pages, and it reads
  its own annotations back when the local journal is missing. The journal is never discarded just
  because the provider file changed; it wins over the PDF when both exist, and the previous copy is
  kept as `strokes.bak` for recovery. A serial save status is shown in the top bar. Switching tabs,
  returning to the library, closing a file and leaving the app (home/recents/screen off) force an
  immediate journal + PDF save and flush the reading position, without waiting for the autosave
  debounce. A failed write restores the pristine copy. Recovered journal strokes are marked dirty on
  open, so they are exported even when the user does not draw again; a save that failed before the
  permission was granted is retried as soon as all-files access appears.
- **UI:** the pen dock can be collapsed, the brush panel is anchored next to its chip, and the top
  bar shows the library, tabs and save state.
- **Motion:** state changes stay short (150–300 ms) and never delay a tap. Opening a document
  cross-fades the library out while the reader settles in slightly smaller; going back is a plain
  fade with no zoom or slide. The library's loading, scanning, empty and tree states fade in,
  course/category/folder rows animate into and out of the tree as they are expanded or collapsed
  while their chevrons rotate, and the filter chips and pills ease between selected and unselected
  colours.

### Known limitations

- Android's `PdfRenderer` does not draw PDF annotations, so the app paints its own strokes as an
  overlay. Annotations saved to the file are visible in desktop readers (Acrobat, Chrome/Edge and
  others) but not in apps that rely on `PdfRenderer`. Pre-existing annotations from other apps are
  preserved in the file but not displayed.
- Saving is a full PDF rewrite (not an incremental update). Encrypted/linearized PDFs and unusual
  document providers need more testing; some providers may refuse writes, in which case the app
  reports "Save failed" and keeps the journal.
- Page bitmaps are rendered at screen density, so heavy zoom is slightly soft. Tile-based
  rendering is the next quality step.
- No text markup, lasso selection, OCR, cloud sync, or encryption support yet.
- The calendar parser reads the events as the feed supplies them. It does not expand `RRULE`
  recurrence rules, so a feed made of one recurring event shows only its first occurrence; the
  RWTHonline feed already contains every individual date. The subscription must be HTTPS.

See [PLAN.md](PLAN.md) for scope, milestones, and acceptance criteria.
