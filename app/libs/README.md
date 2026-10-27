# Xiaomi PenEngine SDK

Drop the official **PenEngine-release_x.x.x.aar** here.

- Source: HyperOS developer docs → 小米手写笔引擎 → 开发准备 → 获取SDK
  (the download is a Xiaomi enterprise-drive share; the extraction code is in the docs).
- Gradle picks up every `*.aar` / `*.jar` in this folder automatically.
- The app talks to it through reflection (`com.miui.penengine.touchfilm.MiuiTouchFilmUtils`), so
  the project still builds when this folder is empty.

Without this AAR the pen's side button cannot reach the app on HyperOS: the system reserves it for
Quick Note / Screenshot. With the AAR, `MiuiTouchFilmUtils` registers for the touch-film gestures
and receives `SWITCH_BETWEEN_BRUSH_AND_ERASER`, which drives the pen ↔ eraser toggle.
