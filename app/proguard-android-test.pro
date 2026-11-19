# Used only by benchmark.testProguardFiles, never by the tested application or release APK.
# See docs/verification/optimized-test-r8.md for the inspected artifact bytecode and CI failure.

# Hilt's EntryPointListener has a CLASS-retained AutoValue annotation. Its generated implementation
# is already present in hilt-android-testing; the annotation itself is not read on Android.
-dontwarn com.google.auto.value.AutoValue

# Error Prone's CLASS-retained IncompatibleModifiers and RequiredModifiers meta-annotations expose
# a legacy value() returning this JDK compiler enum. Their analysis runs on the host, not Android.
-dontwarn javax.lang.model.element.Modifier
