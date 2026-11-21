# Keep the app's own classes: services, receivers and tiles are named in the manifest, and some
# Bluetooth calls go through reflection. Libraries are still shrunk.
-keep class nz.galaxybridge.watch.** { *; }
-dontwarn com.google.errorprone.annotations.**

# Sensor wire types: the Samsung Health tracking service delivers data points over Binder and
# resolves ServiceValue/ServiceDataPoint by their exact class name, so these must not be renamed
# or stripped (and their Parcelable CREATORs must survive).
-keep class com.samsung.android.service.health.tracking.data.** { *; }
