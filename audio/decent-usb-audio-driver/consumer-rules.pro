# Consumer rules for the USB-exclusive driver: JNI entry points use classic
# name mangling, so the Kotlin facades must survive app minification.
-keep class com.decent.usbaudio.NativeAudioEngine { *; }
-keep class com.decent.usbaudio.UsbAudioDevice { *; }
-keep class com.decent.usbaudio.UsbAudioDeviceInfo { *; }
-keep class com.decent.usbaudio.UsbAudioStream { *; }
-keep class com.decent.usbaudio.UsbAudioPermissionHelper { *; }
-keep class com.decent.usbaudio.UsbAudioException { *; }
-keep class com.decent.usbaudio.media3.** { *; }
