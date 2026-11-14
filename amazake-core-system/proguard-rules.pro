# ProGuard rules for AmazakeCore.azs — obfuscate our own code, keep what the server/JVM reach by name.
# Goal: a decompiled .azs is unreadable. We only rename/repackage (no shrink, no optimize) to avoid breakage.

-dontshrink
-dontoptimize
-ignorewarnings
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable

# Entry point: the core instantiates this class by the name in system.yml and calls the AmazakeSystem methods.
-keep public class core.amazake.core.AmazakeCoreSystem {
    public <init>();
    public void enable(core.amazake.systems.api.SystemContext);
    public void disable(core.amazake.systems.api.SystemContext);
    public void reload(core.amazake.systems.api.SystemContext);
}

# Bukkit calls event-handler methods by reflection: keep the annotated methods (names may change, they must stay).
-keepclassmembers class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}
# Command overrides keep the library method names automatically; be explicit to be safe.
-keepclassmembers class * extends org.bukkit.command.Command {
    public boolean execute(org.bukkit.command.CommandSender, java.lang.String, java.lang.String[]);
    public java.util.List tabComplete(org.bukkit.command.CommandSender, java.lang.String, java.lang.String[]);
}

# The bundled H2 driver: keep it whole (used by reflection internally; we instantiate org.h2.Driver by name-free code).
-keep class org.h2.** { *; }
-keepclassmembers class org.h2.** { *; }
-dontwarn org.h2.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.sql.**
-dontnote **
