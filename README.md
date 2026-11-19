# Widget Spinner

A interactive spinner widget for your home screen ported from NothingOS.

Original author page - https://playground.nothing.tech/detail/app/a7hcZh4Q5LKy9WUN

## Build

Requires **JDK 17** and **Android SDK 35** (`ANDROID_HOME` set or `sdk.dir=...` in `local.properties`).

```bash
# Using Make (creates dist/*.apk)
make

# Or using Gradle directly
./gradlew :app:assembleDebug
```

The built APK is located at `dist/*.apk`.
