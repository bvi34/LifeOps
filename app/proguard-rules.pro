# Keep all data models (Room entities, domain models, Gson targets)
-keep class com.lifeops.app.data.** { *; }

# Keep WorkManager workers — looked up by class name at runtime
-keep class com.lifeops.app.worker.** { *; }

# Gson TypeToken subclasses used for Map<String,Int> deserialization
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepattributes Signature

# Kotlin enums (Priority, TaskStatus, etc.) must keep their values() and valueOf()
-keepclassmembers enum com.lifeops.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
