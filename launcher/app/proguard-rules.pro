# Everything the framework instantiates by name is referenced from the
# manifest, which R8 already keeps. Nothing is loaded by reflection.
-dontwarn org.jetbrains.annotations.**
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
