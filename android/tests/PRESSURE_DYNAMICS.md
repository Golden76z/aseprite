# Physical pressure dynamics validation

Use a real pen and the existing editor. `adb input` may prepare the document and
settings, but must not draw validation strokes. Capture logcat before testing.

## Size

Create a 512x512 RGBA transparent document. Select Pencil, a solid visible color,
and a round brush. In the normal Dynamics popup select Size / Pressure, minimum
1 and maximum 32. Leave Angle and Gradient disabled and the existing pressure
thresholds unchanged (default 0.1/0.9). Capture the settings and empty document.

Ask the operator to draw, in order: light, normal, firm without forcing,
increasing pressure, decreasing pressure, and several quick strokes. Then test
a finger stroke and UI tap. Capture the result and ask about thickness,
alignment, releases and gaps. Do not infer these observations solely from logs.

## Alpha through existing Gradient dynamics

Use another blank RGBA document or untouched area. Disable Size dynamics, use a
fixed round brush size, and enable Gradient / Pressure. Set a transparent
background color and opaque foreground color, Background to Foreground direction,
and no dithering. Existing `BrushPointShape` interpolates alpha for this gradient;
there is no separate Android opacity engine or new opacity sensor.

Capture settings including colors. Repeat physical light/normal/firm and
progressive strokes. Inspect transparency against the checkerboard independently
of brush width, and record the operator's observations.

## Debug evidence

`MotionSample` retains milestone 8 raw NDK samples. New bounded traces are:

- `PressureEvent`: Android current pressure and the resulting queued Event value,
  type and logical UI position. DOWN/UP and first MOVE in each pressure quartile.
- `PressureUI`: actual MouseMessage pressure and `pointer_from_msg()` output at
  the editor, first sample in each pressure quartile per contact.
- `PressureTool`: actual Pointer pressure and existing threshold-adjusted dynamics
  value, thresholds, resulting size and gradient. First sample in each processed
  pressure quartile per tool loop.

These stages sample independently; correlate equal pressures and coordinates
where available rather than treating adjacent lines as one event. Android raw,
Event, UI message and Pointer pressure should match for real Pen DOWN/MOVE.
Tool dynamics then applies Aseprite's existing thresholds. This is not Android
device-maximum calibration. UP retains raw pressure in Event; the common Manager
does not forward pressure into its UP message. Synthetic cancellation uses zero.

Touch/Mouse event pressure stays zero; existing dynamics treats non-pen pointers
as full strength. No tilt, buttons, synthetic eraser or history replay is added.
