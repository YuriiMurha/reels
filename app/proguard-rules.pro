# Release keep rules. Room, kotlinx.serialization and Media3 ship their own consumer rules.

# Navigation's type-safe routes (kotlinx.serialization) find an enum route argument by its serial name, with Class.forName,
# when the nav graph is built: LoginRoute(purpose: LoginPurpose). R8 renamed the enum and the release build crashed on launch
# (IllegalArgumentException: Cannot find class with name "...ui.login.LoginPurpose"). Any other @Serializable enum used as a
# route argument needs the same, which is why this covers the app's @Serializable enums and not just that one.
-keep @kotlinx.serialization.Serializable enum io.github.yuriimurha.reels.** { *; }

# A run's lastError ("Unexpected error: <class>", SyncEngine) and the HTTP crash guard (IOException(<class>)) show an exception's
# simpleName. Without this R8 renames the classes and the Sync screen would say "Unexpected error: a".
-keepnames class * extends java.lang.Throwable
