# kotlinx.serialization ships its own rules. Keep our @Serializable DTOs' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.vyntric.offset.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# usb-serial-for-android looks drivers up reflectively by class.
-keep class com.hoho.android.usbserial.driver.** { *; }
# Crash traces stay readable for bug reports (no crash reporting service exists).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Tink (used by androidx.security-crypto) references compile-only errorprone annotations.
-dontwarn com.google.errorprone.annotations.**
