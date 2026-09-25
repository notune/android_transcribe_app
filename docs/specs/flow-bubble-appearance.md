# Flow Bubble Appearance (phase 2)

This spec extends `flow-bubble.md`. Phase 1 (behaviour) is done and works on a real phone.

## Problem Statement

The bubble has one fixed look: a purple disc. It does not use the app's Material You accent colour. I cannot make it fit my wallpaper, my theme, or my taste. I cannot make it smaller or less visible when I do not use it.

## Solution

A new "Bubble style" screen. It shows a live preview of the bubble in its three states: idle, recording and processing.

- I pick one style for light theme and one style for dark theme. The bubble changes with the system theme.
- There are four styles:
  - **Material**: the app's Material You colours. This is the default.
  - **Black & white**: a monochrome bubble.
  - **Glass**: a clear, very translucent bubble with a bright rim and a highlight, in the style of iOS "Liquid Glass".
  - **Blur**: a frosted bubble that blurs the content behind it.
- I pick a size, an idle opacity, and an auto-shrink behaviour.

## User Stories

Style screen

1. As a user, I want a "Bubble style" button in the Flow bubble card, so that I can open the style screen.
2. As a user, I want a live preview of the bubble in the idle, recording and processing states, so that I see the result before I use it.
3. As a user, I want the preview on a light and on a dark sample background, so that I see both theme choices at once.
4. As a user, I want each change to update the preview at once, so that I can compare options fast.
5. As a user, I want each change to apply to the real bubble at once, so that I do not need to restart anything.

Styles

6. As a user, I want the default bubble to use the app's Material You accent colours, so that it matches my phone.
7. As a user, I want to choose a style for light theme, so that the bubble fits light apps.
8. As a user, I want to choose a separate style for dark theme, so that the bubble fits dark apps.
9. As a user, I want the bubble to switch style when the system theme changes, so that I do not switch it by hand.
10. As a user, I want a black and white style, so that the bubble is neutral and does not use colour.
11. As a user, I want a glass style, so that the bubble looks light and modern and shows the app through it.
12. As a user, I want a blur style, so that the bubble is readable on any content.
13. As a user on a phone without window blur, I want the blur style to fall back to a frosted look, so that it still looks good.
14. As a user on a phone without window blur, I want the style screen to tell me that, so that I know why there is no real blur.
15. As a user, I want the recording state to stay clear in every style, so that I always know when the mic is on.

Size, opacity, shrink

16. As a user, I want four sizes, so that the bubble fits my hand and my screen.
17. As a user, I want to set the idle opacity from 20% to 100%, so that the bubble hides more when I do not use it.
18. As a user, I want the bubble at full opacity while it records or processes, so that the state is always clear.
19. As a user, I want an option to shrink the bubble after 5 seconds of no use, so that it covers less of the app.
20. As a user, I want an option to shrink it to a small dot, so that it is almost invisible.
21. As a user, I want a touch on a shrunk bubble to restore it and not start a recording, so that I do not record by mistake.

## Implementation Decisions

### Base

- Build on `feature/flow-bubble`.
- Phase 1 code stays as it is, except where this spec says so.

### Modules

- **`BubbleAppearance`** (new, plain Java, no Android imports). It is a pure function. Given the settings, the night mode, blur support and the mode, it returns an `Appearance` value: style, scale, alpha, shrink level and whether to blur. It is the main test seam.
- **`BubbleStyler`** (new, Android). It applies an `Appearance` to a bubble view. It builds drawables and colours for each style. The service and the style screen both use it, so that the preview and the real bubble look the same.
- **`BubbleStyleActivity`** (new). It is the style screen.
- **`BubbleController`** (changed). It adds the shrink timer and the restore-on-touch rule.
- **`InsertionAccessibilityService`** (changed):
  - It uses `BubbleStyler`.
  - It re-renders on a theme change (`onConfigurationChanged`).
  - It hosts the bubble in a `Dialog` window (see Blur).
- **`BubblePrefs`** (changed). See the schema below.

### Appearance value

```java
enum Style { MATERIAL, MONO, GLASS, BLUR }
enum Shrink { NONE, SMALL, DOT }

final class Appearance {
    Style style;      // resolved for the current night mode
    float scale;      // 0.7, 0.85, 1.0 or 1.15; applied to the 56 dp base
    float alpha;      // idle opacity 0.2..1.0; 1.0 when not IDLE
    Shrink shrink;    // NONE unless IDLE and the shrink timer fired
    boolean blur;     // true only for BLUR and when blur is supported
}
```

Rules:

- `style` is `styleDark` when night mode is on, else `styleLight`.
- `alpha` is the opacity setting in IDLE only. In RECORDING and PROCESSING it is 1.0.
- `shrink` is NONE outside IDLE.
- `blur` is true only for the BLUR style, API 31 or later, and `WindowManager.isCrossWindowBlurEnabled()`.

### Styles

The styler reads every colour from resources or from the Material theme. Java code holds no colours. The service wraps its context with `DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(this, R.style.AppTheme))`, as `RustInputMethodService` does. It resolves the light and dark variants with a configuration override.

| Style | Idle | Recording | Processing |
|---|---|---|---|
| Material | `colorPrimary` fill, `colorOnPrimary` icon | `colorError` fill, `colorOnError` icon, `colorErrorContainer` ring | `colorTertiary` fill, `colorOnTertiary` icon |
| Black & white | Light: black fill, white icon. Dark: white fill, black icon | Same colours, with a 3 dp ring in the icon colour | Fill at 60% of the idle contrast |
| Glass | Clear fill (white at 12% in light, 10% in dark), gradient rim, top highlight, soft shadow, icon in `colorOnSurface` | Red rim tint and red icon | Spinner in `colorOnSurface` |
| Blur | Window blur 24 dp, tint: white at 55% (light) or black at 45% (dark), icon in `colorOnSurface` | Red icon and red ring | Spinner in `colorOnSurface` |

Glass details:

- Rim: a 1.5 dp sweep-gradient stroke. It is white at 70% top-left and white at 15% bottom-right.
- Highlight: an oval at the top third. It is a white-to-clear gradient at 35%.
- Shadow: elevation 6 dp.

The effect is an approximation. True "Liquid Glass" refraction needs pixels of the content behind the bubble, and an overlay window cannot read those.

The cancel chip and the snooze target follow the same style as the bubble.

### Blur

- `Window.setBackgroundBlurRadius` is the public API (Android 12+). It blurs only the area behind the window bounds. The corners follow the window background drawable's corner radius.
- It needs a real `Window`. The service therefore hosts the bubble in a `Dialog`:
  - Window type `TYPE_ACCESSIBILITY_OVERLAY`.
  - Flags `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCH_MODAL` and `FLAG_LAYOUT_NO_LIMITS`.
  - No dim.
  - A transparent `GradientDrawable` background with a corner radius of half the size. This gives a round blur.
  - Position through `window.setAttributes`.
- The cancel chip and the snooze target stay plain `WindowManager` views. They have no blur and use the frosted fallback in the Blur style.
- The service listens to `addCrossWindowBlurEnabledListener`. Battery saver and developer options can turn blur off at run time. The bubble then re-renders with the fallback.
- Fallback when blur is off: the same tint at 85% alpha ("frosted").
- **Known device fact:** the Galaxy S24 (One UI, Android 16) reports `Blur supported on device: false`. Samsung disables AOSP cross-window blur. The S24 therefore always shows the fallback. The API 35 emulator supports blur, so use it to test the blur path.
- **Spike first:** confirm on the emulator that a `Dialog` with `TYPE_ACCESSIBILITY_OVERLAY` shows, receives touches, lets touches outside pass through, and blurs only a circle behind it. If it fails, keep the `WindowManager` view and make Blur use the fallback on all devices. Record the result here.

  **Result (2026-09-23, API 35 emulator):** it works. The `Dialog` window shows, takes drags and taps, and passes other touches through. The blur is round and covers only the area behind the bubble. One catch: `DecorView` applies background blur only to translucent windows. The `BubbleWindow` theme therefore sets `android:windowIsTranslucent=true`. Without it, `setBackgroundBlurRadius` has no effect.

### Size, opacity, shrink

- Sizes are four scale factors of a 56 dp base: 0.7 (39 dp), 0.85 (48 dp), 1.0 (56 dp) and 1.15 (64 dp). The default is 1.0.
- The 0.7 size keeps a 48 dp touch target. The window is 48 dp, and the visible disc is smaller and centred.
- Migration: an existing `bubble_size_dp` of 48 maps to 0.85, 56 maps to 1.0, and 72 maps to 1.15. After the migration, delete `bubble_size_dp`.
- Opacity: a slider from 20% to 100% in steps of 10. The default is 100%. It sets the view alpha in IDLE only.
- Auto-shrink (default off): after 5 s in IDLE with no touch, the bubble shrinks.
  - SMALL: the visible disc scales to 60%. The touch target stays at least 40 dp.
  - DOT (option "Shrink to a dot", only with auto-shrink on): the bubble becomes a 12 dp dot at the screen edge. The touch target stays 40 dp.
- The shrink timer restarts on each show, touch, cancel and text result.
- A touch down on a shrunk bubble restores full size. That gesture then ends: it does not start a recording or a drag. The next gesture works as normal.
- The change of size and shrink animates over 150 ms.

### Controller changes

- New host call: `setShrunk(boolean shrunk)`. The host chooses SMALL or DOT from the settings.
- New constant: `SHRINK_DELAY_MS = 5000`.
- `onTouchDown` on a shrunk bubble calls `setShrunk(false)`, re-arms the shrink timer, and ignores the rest of that gesture.
- The shrink timer does not run when auto-shrink is off, or outside IDLE.

### Prefs schema (added to `BubblePrefs`)

| File | Type | Default | Notes |
|---|---|---|---|
| `bubble_style_light` | int | 0 (MATERIAL) | 0 MATERIAL, 1 MONO, 2 GLASS, 3 BLUR |
| `bubble_style_dark` | int | 0 (MATERIAL) | Same values |
| `bubble_scale_index` | int | 2 | Index into {0.7, 0.85, 1.0, 1.15} |
| `bubble_opacity` | int, percent | 100 | Snapped to 20..100 in steps of 10 |
| `bubble_auto_shrink` | presence flag | absent (off) | |
| `bubble_shrink_dot` | presence flag | absent (off) | Used only with auto-shrink |

- Remove `bubble_size_dp` after the migration.
- `BubbleHost.Settings` gains these fields. `sizeDp` becomes the computed size.

### Style screen layout

1. Two preview panels, stacked at full width so that the L size fits at real size. The top panel is a light sample background with the light-theme style. The bottom panel is a dark sample background with the dark-theme style. Each panel shows idle, recording and processing bubbles side by side.
   - The sample backgrounds are colourful drawables (gradient plus shapes), so that glass and blur have something to show.
   - The preview blur uses `RenderEffect.createBlurEffect` on a clipped copy of the sample background (API 31+). Otherwise it uses the fallback.
2. "Light theme style": a single-choice segmented button (`MaterialButtonToggleGroup`, one selection required) with four options.
3. "Dark theme style": the same control.
4. The notice "Blur isn't supported on this device; a frosted look is used instead." It shows only when blur is off.
5. "Size": a single-choice segmented button (XS, S, M, L).
   - Do not use filter chips for these choices. Filter chips read as multiple choice.
   - Segment labels wrap to two lines instead of ellipsizing.
6. "Idle opacity": a slider with a percent label.
7. "Shrink when idle": a switch.
8. "Shrink to a dot": a switch. It is enabled only when shrink is on.

- Each change writes the prefs and calls `InsertionAccessibilityService.notifyPrefsChanged()`.
- Remove the "Bubble size" radio group from the main screen. The Flow card gets the button "Bubble style" instead.
- Add every new string to all seven locales.

## Testing Decisions

- **Seam 1:** `BubbleAppearance.resolve(...)`, plain JUnit. Test:
  - style per night mode
  - alpha 1.0 outside IDLE
  - opacity in IDLE
  - shrink NONE outside IDLE
  - blur only when BLUR and supported
  - each scale index
- **Seam 2:** the existing `BubbleHost` fake for the controller. Test:
  - shrink after 5 s in IDLE
  - no shrink with the option off
  - no shrink while recording
  - touch on a shrunk bubble restores and does not record
  - timer restart on show, touch and text
- `BubblePrefsTest` covers the new prefs: defaults, snapping, and the `bubble_size_dp` migration.
- Manual check:
  - each style in light and dark on the emulator (blur works there)
  - the S24 fallback
  - a theme switch while the bubble shows

## Out of Scope

- Samsung's private blur API (`semSetBlurInfo`). It could give real blur on One UI, but it is undocumented. It may be a later spike.
- A free colour picker or custom colours per state.
- Custom icons.
- Animations beyond the 150 ms size change and the existing states.
- Real refraction for Glass.

## Further Notes

- Blur check commands: `adb shell wm disable-blur` prints support and state. `settings get global disable_window_blurs` shows the developer switch.
- The Material style keeps today's colour roles, but from the theme. On Android 12+ it follows the wallpaper through dynamic colour. On older versions it uses the app's seed colours.
