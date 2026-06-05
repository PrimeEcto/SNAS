# SNAS

Private Fire TV streaming app with encrypted catalog delivery.

## Install

Open Downloader on Fire TV and enter:
```
github.com/PrimeEcto/SNAS/releases/latest/download/SNAS-debug.apk
```

## Unlock

On first launch, enter the unlock code to decrypt the catalog.
The code is never stored in the APK or this repository.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17+ and Android SDK 35.
