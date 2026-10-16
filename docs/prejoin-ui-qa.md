# Pre-Join layout audit and QA

## Audit

- `client.Main` launches `MainApp`; `SceneManager.preJoin(RoomInfo)` creates `PreJoinScreen` at 1100 x 700. `SceneManager.show` retains maximized/fullscreen state and attaches `ThemeManager` and `/styles/app.css`.
- The actual root is a `BorderPane`. Previously the native-video path placed a minimum-300-high preview, camera controls, audio controls, error and navigation row inside a single center `VBox`. It had no ScrollPane. Its combined minimum height could exceed the center region and push navigation below the Scene. The legacy path put navigation in the settings card.
- `CameraControls` owns consent, device discovery, camera selection and the toggle callback. `AudioControls` owns microphone consent, input/output selection and its toggle callback. `VideoCallController.frames(self).imageProperty()` supplies the preview. The session owns capture and publishing; `SceneManager.meeting()` publishes the prepared camera after replacing the view.
- Pre-existing theme work was present in the working tree. It has been retained. This change only adds scoped pre-join CSS to that stylesheet and uses its existing semantic colors.

## Implementation

Files changed for this task: `src/main/java/client/UI/screens/PreJoinScreen.java`, `src/main/java/client/UI/SceneManager.java`, `src/main/java/client/UI/components/CameraControls.java`, `src/main/java/client/UI/components/AudioControls.java`, new `src/main/java/client/UI/components/DeviceChoiceStyle.java`, additions to `src/main/resources/styles/app.css`, new `src/test/java/client/UI/PreJoinLayoutTest.java`, and this report.

- Header / independently scrolling content / pinned footer are separate BorderPane regions. Both native and legacy paths share this arrangement. Footer actions retain the existing permission, meeting and leave-room entry points. A disposed/entry guard prevents repeated entry from queued clicks.
- GridPane reuses the same controls at a content-width breakpoint of 860: 60/40 columns above it, stacked panels below it. Content has a readability cap, settings a bounded width and preferred height. These are layout limits, not prescribed window dimensions.
- Preview negotiates height from width at 16:9, capped at 420 high. The ImageView binds its fits once to the surface and preserves the source ratio. Its intrinsic image resolution does not determine the container size. StackPane centers the image, allowing letterboxing.
- Existing camera/mic buttons move beside preview. Their original handlers and asynchronous operations remain in their owning components. Busy labels and disabled states reflect work in progress. No extra capture, signaling, media thread or frame listener was added.
- Camera-off, starting/waiting and device error text appear in the preview. Dropdowns can shrink, use ellipsis and show complete device names in tooltips. Errors collapse when empty. The native microphone level remains explicitly unavailable because the current audio controller exposes no input level; no second capture session is started.
- Pre-join alone permits a 560 x 480 minimum Stage; other screens keep their current minimums. Footer actions can wrap at still narrower test viewports.
- Dispose removes controller listeners and unbinds the frame, fit, visibility and placeholder bindings; it never stops session-owned capture during handoff.

## Verification

`PreJoinLayoutTest` exercises actual JavaFX CSS/skins/layout for native and legacy paths in Light / Dark / Light sequence:

- Viewports: 1000 x 700, 1180 x 780, 1280 x 720, 1366 x 768, 1600 x 900; also 700 x 520 and 480 x 440, then restores 1000 x 700.
- Asserts footer/button bounds, consistent button heights, settings bounds, no horizontal overflow, two-column/stacked positioning and preview ratio.
- Synthetic 4:3, 16:9 and portrait frames verify source ratio, containment and centering without hardware access.
- A shown native-path Stage maximizes on this machine (Scene about 1536 x 793), restores, narrows and widens through real JavaFX pulses. Footer remains inside the viewport and layout settles.
- Rendered PNGs were inspected. Artifacts: `target/qa/prejoin/`, including `maximized.png`, `small-window.png`, Light/Dark examples and the measured maximized viewport.
- Regression tests: `CameraControlsTest`, `AudioControlsTest`, `RoomFlowTest` pass. The sandbox initially blocked atomic renames of temporary consent files; the same tests pass outside that sandbox. No production consent-store changes were made.
- Maven `test` completed with exit code 0. Final scoped runs of the two layout tests and camera/mic/room regression tests also completed with exit code 0 after the last changes. Maven `-DskipTests package` completed with exit code 0 and produced `target/MiniMeetingRoom-1.0-SNAPSHOT.jar`.
- Environment: installed JDK 22 compiling with Maven release 21, JavaFX 21.0.2, Maven bundled with NetBeans 22. Maven/JavaFX emits the existing unnamed-module warning.

## Limits

No physical camera/microphone, hotplug or two-machine WebRTC call was exercised. Device tests use deterministic catalogs and consent fixtures; preview ratio checks use synthetic images. The existing behavior requires stopping the camera before choosing another camera and stopping the mic before choosing another input; this UI change retains that lifecycle. Hardware FPS and physical permission behavior need a manual session. Native media integration profiles are excluded from the normal Maven suite by the existing POM.
