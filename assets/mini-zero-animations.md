# `mini-zero.svg` (Blob Edition) Animation Reference

The new pixel-art "Blob" Zero mascot supports several snappy, CLI-optimized animations triggered by CSS classes on the root element.

## How to Use
Add the following classes to the `<g class="root">` element in `mini-zero.svg`.

| Class | Animation / Expression | Vibe |
| :--- | :--- | :--- |
| **`.idle`** | Floating & Blinking | Default peaceful state. |
| **`.success`**| High Bounce + Smiling | Task completed! |
| **`.error`**| Rapid Shaking + Angry | Something went wrong. |
| **`.angry`** | Shaking + Slanted Eyes | "I didn't like that command." |
| **`.sleeping`**| Slow Breathing + Closed Eyes | Processing a long task or idle. |
| **`.love`** | Bouncing + Heart Eyes | "I love this code!" |
| **`.smiling`**| Floating + Happy Eyes | Happy to help. |
| **`.peek`** | Side-to-side scan | Searching the codebase. |
| **`.typing`** | Pulsing Scale | Thinking or ruminating. |

## Technical Details
- **Grid:** 16x16 pixels.
- **Rendering:** `shape-rendering="crispEdges"` is used for sharp pixels.
- **Dynamic Eyes:** The SVG contains multiple eye sets. Applying a class (like `.love`) automatically hides the default eyes and shows the correct expression.

## Example
```xml
<g class="root love">
  <!-- Zero will now have heart eyes and bounce! -->
</g>
```
