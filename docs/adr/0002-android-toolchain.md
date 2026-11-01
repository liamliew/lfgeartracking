# ADR 0002: Android toolchain pins

## Status
Accepted (task 3).

## Context
The newest releases are not mutually compatible, and the spec says to follow the docs when they differ.
- AndroidX from Compose BOM 2026.03+ / lifecycle 2.11 / navigation 2.10 / core-ktx 1.18+ requires Android Gradle Plugin 9.1+ and compileSdk 37. AGP 9 needs Gradle 9 and replaces the Kotlin plugin setup (built-in Kotlin), a large migration with no benefit for this project yet.
- supabase-kt 3.7+ is compiled with Kotlin 2.4. Dagger/Hilt 2.57-2.59 read Kotlin metadata only up to 2.2.x, and the Kotlin compiler can read metadata only one minor version ahead of itself.
- The Android docs now steer new projects to Room 3 (`androidx.room3`); the latest stable Room 2 is 2.8.5.

## Decision
| | Pin | Why |
|---|---|---|
| AGP / Gradle | 8.13.2 / 8.14.3 | last line compatible with the AndroidX set below |
| Kotlin / KSP | 2.2.21 / 2.2.21-2.0.5 | matches Hilt 2.58 metadata support and supabase-kt 3.2.6 |
| Hilt | 2.58 (+ androidx.hilt 1.3.0) | Hilt docs show 2.57.1; 2.58 reads Kotlin 2.2.20 metadata |
| supabase-kt | 3.2.6 (Ktor 3.3.1) | built with exactly Kotlin 2.2.21 |
| Compose BOM / AndroidX | 2026.02.01, lifecycle 2.10.0, navigation 2.9.8, activity 1.12.4, core-ktx 1.17.0, WorkManager 2.11.2 | newest set that works with AGP 8.13 |
| Room | 2.8.5 (KSP) | stable; Room 3 is a package rename plus driver API change, adopt later as its own task |
| compileSdk / targetSdk / minSdk | 36 / 35 / 30 | libraries need 36 to compile; targetSdk 35 and minSdk 30 per spec (Zebra Android 11-14) |

## Consequences
Upgrade as one coordinated step: AGP 9 + Gradle 9 + Kotlin 2.3/2.4 + Hilt 2.59+ + supabase-kt 3.7+ + current AndroidX + Room 3. Do not bump a single library alone; `./gradlew :app:checkDebugAarMetadata` tells you quickly when a pin is incompatible.
