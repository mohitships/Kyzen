-keep class com.binarybrigade.kyzen.MainActivity { *; }
-keep class com.binarybrigade.kyzen.ModeSelectionActivity { *; }
-keep class com.binarybrigade.kyzen.PinEntryActivity { *; }
-keep class com.binarybrigade.kyzen.ChildDashboardActivity { *; }
-keep class com.binarybrigade.kyzen.ParentDashboardActivity { *; }
-keep class com.binarybrigade.kyzen.OverlayActivity { *; }
-keep class com.binarybrigade.kyzen.DetoxBreakActivity { *; }
-keep class com.binarybrigade.kyzen.UsageMonitorService { *; }
-keep class com.binarybrigade.kyzen.BootReceiver { *; }
# FlowPlayerActivity — the embedded YouTube player entry point (Phase 2).
# Pinned to primary dex to prevent START_CLASS_NOT_FOUND on Android 16
# when launched via explicit Intent from the intercept overlay.
-keep class io.github.aedev.flow.ui.player.FlowPlayerActivity { *; }
