# Gear Tracker (Android)

Kotlin + Jetpack Compose app for Zebra handhelds (Android 11-14). Open **this folder** in Android Studio.

## Configure the backend (not committed)
Create `apps/android/local.properties` (gitignored):
```properties
sdk.dir=/path/to/Android/sdk
supabase.url=http://10.0.2.2:54321
supabase.anonKey=<publishable/anon key from `supabase status`>
```
`10.0.2.2` reaches your computer's `supabase start` from the emulator. On a real Zebra use your server's https URL. The URL can also be changed in the app under Settings (restart to apply). Never put a service-role key in the app.

## Create a test user
With the local stack running: Studio (http://127.0.0.1:54323) -> Authentication -> Add user. New users are `viewer`; promote to `crew`/`manager`/`admin` with
`update public.profiles set role = 'crew' where id = '<user id>';` (see `docs/architecture.md`).

## Build and test
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
The APK is `app/build/outputs/apk/debug/app-debug.apk` (application id `com.lfcreative.geartracker.debug`).

## What exists so far
Hilt DI, Room mirror (tags, assets, locations, projects, open checkouts) and the `outbox_events` queue, email/password login (Supabase Auth, session kept offline), Home with session tiles (disabled until their tasks land) and sync/overdue status, Settings/Diagnostics. Scanner input, sessions and sync arrive in the next tasks.

## Layout
`core/` shared types (`ScanInput`, `ServerUrl`) - `data/local` Room - `data/auth` Supabase auth - `data/outbox` event queue - `data/settings` preferences - `di/` Hilt modules - `ui/` Compose screens and ViewModels.
