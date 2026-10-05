# Nothing Glyph SDK talks to a system service over AIDL; keep its classes intact.
-keep class com.nothing.ketchum.** { *; }
-keep class com.nothing.thirdparty.** { *; }
-dontwarn com.nothing.**
