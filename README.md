# PROG2007 — Lecture 7: AR & Computer Vision (DropZone)

DropZone is a small Android app used to teach three progressively-built features on top of the same screen flow: capture a proof-of-delivery photo, run live computer vision on the camera feed, and mark a delivery spot with real ARCore-based augmented reality.

Package: `com.example.lect7arcv`
Project name (Android Studio): `Lect7ARCV`
UI: Jetpack Compose + Compose Navigation (`Start` → `Camera` → `Ar`)

## What's in this repo

This repo does **not** contain a full buildable Gradle project. It contains full-file snapshots of `MainActivity.kt` at each teaching milestone, plus the standalone AR renderer — copy the one you want into your own Android Studio project (see setup below).

| File | Contains |
|---|---|
| `Feature-1-code` | Feature 1 only — CameraX preview + `ImageCapture`, saves a proof-of-delivery photo via `MediaStore` |
| `Feature-1-2-code` | Feature 1 + 2 — adds OpenCV grayscale → Canny edge detection running inside a CameraX `ImageAnalysis` use case, toggled on/off over the live preview |
| `Feature-1-2-3-code` | Feature 1 + 2 + 3, **final/complete `MainActivity.kt`** — adds the "Mark Delivery Spot" AR screen: real ARCore plane detection, tap-to-place anchor, live camera passthrough |
| `ArRenderer.kt` | The `GLSurfaceView.Renderer` that Feature 3's `ArScreen` delegates to — ARCore camera passthrough shader, floor-plane hit-testing, and a hand-built OpenGL 3D package box rendered at the anchor |

Each `Feature-*-code` file is the complete `MainActivity.kt` for that stage, not a diff — use whichever matches how far you want to follow along.

## Project setup

1. **Create the project** in Android Studio: Empty Activity (Compose), name `Lect7ARCV`, package `com.example.lect7arcv`, minSdk 24 (ARCore's minimum).

2. **Add the plugin** (needed for the `@Serializable` navigation routes) to the module's `build.gradle.kts` `plugins {}` block:
```kotlin
   id("org.jetbrains.kotlin.plugin.serialization") version "<matches your Kotlin version>"
```

3. **Add dependencies** to `app/build.gradle.kts`:
```kotlin
   dependencies {
       // Compose / navigation (adjust versions to your Compose BOM)
       implementation(platform("androidx.compose:compose-bom:2024.09.00"))
       implementation("androidx.compose.ui:ui")
       implementation("androidx.compose.ui:ui-graphics")
       implementation("androidx.compose.ui:ui-tooling-preview")
       implementation("androidx.compose.material3:material3")
       implementation("androidx.activity:activity-compose:1.9.1")
       implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
       implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
       implementation("androidx.navigation:navigation-compose:2.8.0")
       implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

       // Feature 1 + 2: CameraX
       implementation("androidx.camera:camera-core:1.3.4")
       implementation("androidx.camera:camera-camera2:1.3.4")
       implementation("androidx.camera:camera-lifecycle:1.3.4")
       implementation("androidx.camera:camera-view:1.3.4")

       // Feature 2: OpenCV (published directly to Maven Central — no separate SDK module needed)
       implementation("org.opencv:opencv:4.9.0")

       // Feature 3: ARCore
       implementation("com.google.ar:core:1.44.0")
   }
```

4. **Add manifest entries** to `AndroidManifest.xml`:
```xml
   <!-- inside <manifest>, alongside/above <application> -->
   <uses-permission android:name="android.permission.CAMERA"/>
   <uses-feature android:name="android.hardware.camera.ar" android:required="true"/>
```
```xml
   <!-- inside <application> -->
   <meta-data android:name="com.google.ar.core" android:value="required"/>
```

5. **Copy the code:**
   - Feature 1 only → copy `Feature-1-code` into `MainActivity.kt`
   - Features 1+2 → copy `Feature-1-2-code` into `MainActivity.kt`
   - Full app (all 3 features) → copy `Feature-1-2-3-code` into `MainActivity.kt`, **and** copy `ArRenderer.kt` as-is into the same package folder (`app/src/main/java/com/example/lect7arcv/`)

## Running each feature

**Feature 1 — Photo capture:** grant the camera permission when prompted, tap "Take Proof-of-Delivery Photo." A saved `content://` URI confirms the write to `MediaStore`.

**Feature 2 — Edge detection:** tap "Show Edge Detection" to overlay the live Canny output on the preview. On an emulator, Extended Controls → Camera → pick a virtual scene or a webcam feed with visible edges/text (a document, a QR code, a checkerboard) — the emulator's feed runs through the real `ImageAnalysis` pipeline, so this is processing real frames, not mocked data.

**Feature 3 — Mark Delivery Spot (AR):** after taking a photo, tap "Mark Delivery Spot." Watch the status text at the bottom of the screen:
- "Move your phone slowly to start tracking" → ARCore hasn't reached `TRACKING` yet
- "Point your phone at the floor to detect it" → tracking is live, no horizontal upward-facing plane found yet
- "Floor detected — tap the floor to place the package" → tap the floor in the camera view to anchor the box

No physical device is needed for this — but it's genuinely easier with one. On the emulator, Extended Controls → Camera → **Virtual scene** gives ARCore a textured room to track against; move slowly with WASD + mouse drag rather than jumping the view, since sudden motion is exactly what breaks tracking on real devices too. On a real device, just walk around a well-lit, textured floor; tracking typically locks in within a few seconds.

## Practical considerations

- Not every device or emulator image supports ARCore — check for "Google Play Services for AR" before relying on it.
- ARCore needs decent lighting and a visually textured surface to find enough feature points; a blank wall or a dark room will not track.
- `GLSurfaceView.RENDERMODE_CONTINUOUSLY` plus ARCore's continuous camera + IMU fusion make Feature 3 noticeably more battery- and thermally-intensive than Features 1 and 2 — factor that into how long you test it in one sitting.

## Known simplifications

- `ArRenderer.setDisplayGeometry(0, ...)` assumes the app is portrait-locked (`Surface.ROTATION_0`). Supporting rotation would need the real display rotation from `WindowManager`.
- There's no visual highlight of the detected floor plane's extent — only the status text tells you tracking succeeded.
- `session.update()` is wrapped in a broad `catch (e: Exception)` in `onDrawFrame`, because `GLSurfaceView`'s continuous render mode can call it in the brief window the main thread is pausing the `Session` (`SessionPausedException`) — this is expected and logged, not a bug.
