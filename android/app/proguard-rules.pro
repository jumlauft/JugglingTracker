# The Garmin Connect IQ Mobile SDK (ciq-companion-app-sdk) ships no consumer
# ProGuard rules of its own. Its broadcast receiver (IQMessageReceiver)
# reconstructs SDK model classes such as IQDevice from Intent extras at
# runtime, which R8 cannot see as a reference, so without these keep rules it
# strips/renames the classes and the receiver dies with a
# ClassNotFoundException in every minified build. This crashed the app on
# launch for real Play Store testers (invisible locally since debug builds
# skip minification) — see Crashlytics issue IQMessageReceiver.onReceive.
-keep class com.garmin.android.connectiq.** { *; }
-keep class com.garmin.android.apps.connectmobile.connectiq.** { *; }
-keep class com.garmin.monkeybrains.serialization.** { *; }
-dontwarn com.garmin.android.connectiq.**
-dontwarn com.garmin.android.apps.connectmobile.connectiq.**
-dontwarn com.garmin.monkeybrains.serialization.**
