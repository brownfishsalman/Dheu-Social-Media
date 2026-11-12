# Dheu for Android

The native Android app for [Dheu](../README.md), written in Kotlin with Jetpack Compose. It talks to the same Supabase project as the website and depends on the same Row Level Security, so the only credential it ever holds is the signed-in member's own session.

It runs on Android 9 (API 28) and newer. Test builds are on the [Releases page](https://github.com/brownfishsalman/Dheu-Social-Media/releases).

## What's in 0.4.0

Everything a member can do on the website, except the admin panel, which stays there.

- Sign-up with a public or private account (and an invite code while the admin has made sign-up invite-only), sign-in, and the privacy choice for older accounts that never made one.
- The home feed with stories and, now and then, "Suggested for you". Posts with swipeable photos, double-tap likes, comments with replies and likes, a likers list, a bookmark that keeps a post in your Saved tab, saving your own posts' photos to the phone, and sharing a new post of up to 10 photos from the + at the top of Home.
- Find friends, the flame tab: make a swipe profile (1 to 6 photos, a bio, an optional age and up to five interests), then swipe right to like or left to pass, with one undo. A mutual like is a match, with an "It's a match" screen and a chat in Messages; a matches list lets you unmatch or report. Once you've seen everyone, the people you passed on come back.
- Profiles with follow, unfollow, follow requests, follower lists, blocking, search, a Saved tab only you can see, and highlights: view them, make one from your stories, add a story to one from the story itself, remove a story or delete a highlight.
- Stories you can view, post to everyone or to your close friends, react to, reply to and check who has seen.
- Activity with follow requests to accept or decline.
- Messages with notes, 1-to-1 and group chats, photos (view-once too), voice messages (record up to 2 minutes), reactions, typing and "Seen", swipe-to-reply, unsend, delete chat for yourself, and the announcements thread. Groups: start one, rename it, add people, remove people (the owner) and leave.
- @mentions in captions, comments and group chats, with suggestions as you type. The person mentioned gets an Activity entry and a notification.
- Reporting a post, story, comment or account to the admin.
- Push notifications for messages, mentions, comments, likes, follows and announcements, with a switch in Settings.
- Settings for your profile, account type, close friends, read receipts, notifications, theme, password, blocked accounts, sharing Dheu with friends (the sign-up link, or invite codes while sign-up is invite-only) and deleting your account.
- A banner on the feed when a newer version is out, and crash reports that reach the admin the next time the app opens.

Screens slide in from the right and back out, and the back gesture drags the page with your finger, as on Instagram. Stories and highlights fade in and out.

0.3.0 is signed with a new release key, as 0.2.0 was before it (see [Release builds](#release-builds)), so it won't install over an older version: uninstall the app first. Nothing is lost, since everything lives on the server; you just log in again.

## Building

You need Android Studio, which bundles a JDK, or JDK 17+ with the Android SDK (platform 37). The Gradle wrapper downloads Gradle 9.8.2 the first time it runs.

Create `android/local.properties`. Git ignores it, and Android Studio fills in `sdk.dir` on its own. Add the Dheu keys:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
dheu.supabaseUrl=https://<project>.supabase.co
dheu.supabaseKey=<publishable key>
dheu.siteUrl=https://<your-deployment>.vercel.app
```

`dheu.supabaseKey` is the publishable key, the same value as `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` in the website's `.env.local`. Never put the secret key here: everything in an APK can be read back out. `dheu.siteUrl` must point at a deployment of this repo, because sign-up and invite codes go through its `/api/auth/signup` and `/api/invites`. Without it the build uses `https://dheu-beige.vercel.app`.

### Push notifications (Firebase)

Push goes through Firebase Cloud Messaging. Without a Firebase project the app still builds and works; it just never notifies.

1. In the [Firebase console](https://console.firebase.google.com), create a project (Analytics isn't needed) and add an Android app with the package name `com.salmansaadiq.dheu`.
2. Download its `google-services.json` into `android/app/`. Git ignores it. The build reads the app ID, API key, project ID and sender ID from it; there's no google-services Gradle plugin.
3. For sending, open Project settings → Service accounts → Generate new private key. Put the whole JSON file's contents in the website's `FCM_SERVICE_ACCOUNT` environment variable on Vercel and redeploy. The key is a secret: keep it out of the repo and out of the app. `/api/health` shows `fcmProject` once the website can read it.
4. Run `supabase/migrations/0020_mentions_app_push.sql`, which adds the `push_devices` table the app registers its token in.

Then build a debug APK:

```bash
cd android
./gradlew assembleDebug
```

On Windows run `gradlew.bat assembleDebug`. The APK lands in `app/build/outputs/apk/debug/`. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or open the `android` folder in Android Studio and press Run.

## Release builds

R8 shrinks the release build to about 6 MB. Gradle leaves it unsigned, and you sign it as a separate step so the keystore password is typed at a prompt and never written to a file.

Create a signing key once, outside the repo. Back up the `.jks` file and its password, since every update has to be signed with the same key:

```bash
keytool -genkeypair -v -keystore dheu-release.jks -alias dheu -keyalg RSA -keysize 4096 -validity 10000
```

If the key or its password is ever lost, the next build has to be signed with a new one, and everyone has to uninstall the old app once before installing it. That happened between 0.1.0 and 0.2.0 (the key was lost) and between 0.2.0 and 0.3.0 (its password was forgotten). Keep the password in a password manager, and a copy of the `.jks` somewhere other than this computer.

Build, sign with `apksigner` from the SDK's `build-tools` folder (it asks for the password), and check the result:

```bash
./gradlew assembleRelease
apksigner sign --ks dheu-release.jks --ks-key-alias dheu --out Dheu-0.3.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs Dheu-0.3.0.apk
```

Raise `versionCode` and `versionName` in `app/build.gradle.kts` before each release. Google Play takes an app bundle (`./gradlew bundleRelease`) rather than an APK; with Play App Signing, the key above becomes your upload key.

Publish on GitHub with the tag `v` + `versionName` (for example `v0.4.0`) and the signed APK attached. Keep the `v` lowercase: 0.3.0 reads only that, and later versions take either case. From 0.3.0 the app asks GitHub for the latest release (`data/Updates.kt`, at most twice a day) and shows a "new version" banner on the feed when the tag is newer than itself, linking straight to the attached `.apk`. `dheu.releasesRepo` in `local.properties` points it at another repository.

Release builds aren't obfuscated (`proguard-rules.pro`): R8 still shrinks and optimises, but class and method names stay readable, so crash reports (`data/CrashReports.kt`, shown in the website's Admin → Crashes) read as they are, without a mapping file.

## Code

```
app/src/main/java/com/salmansaadiq/dheu/
  DheuApp.kt          image loader with a 250 MB disk cache
  MainActivity.kt     splash screen, edge-to-edge, theme
  data/               the Supabase client and one object per area:
                      Account, Profiles, Posts, Stories, Chat, Notes, Activity, Friends
  data/Images.kt      photo resizing and JPEG encoding
  data/Mentions.kt    reading @mentions (same rule as the website and the database)
  data/Push.kt        Firebase setup and this phone's token in push_devices
  push/               the messaging service and the notifications it builds
  ui/nav/             type-safe routes, screen transitions, the bottom bar, the privacy gate,
                      and opening a tapped notification's screen
  ui/auth, feed, post, profile, search, activity, create, stories, messages, settings, friends
                      the screens
  ui/components/      shared pieces: avatars, buttons, fields, sheets, dialogs
  ui/theme/           the website's colour tokens, Inter and Outfit
  ui/icons/Lucide.kt  Lucide icons as ImageVectors, generated by tools/generate-icons.mjs
tools/                Node scripts for the icons and launcher icons
```

The `data` layer makes the same calls as the website: PostgREST selects with embedded relations, and RPCs such as `badge_counts`, `profile_stats`, `follow_user` and `delete_chat_for_me`. Screens hold their state in Compose or a small ViewModel; there's no extra framework on top.

An open chat subscribes to new messages in that conversation over Realtime. Realtime can't filter delete events, which carry only the row id, so the chat drops any unsent message whose id it has on screen. The Activity and Messages badges refresh from one shared channel, debounced, and the counts come from a single `badge_counts()` call.

`Images.kt` decodes photos with the platform's `ImageDecoder`, which handles HEIC and EXIF rotation, then crops and scales on a canvas, flattens transparency onto white and encodes a JPEG. The sizes match the website's: 1080 × 1920 at quality 90 for posts and stories, a 480 px square at 80 for the profile-grid thumbnail, and a 320 px square at 88 for avatars. Re-encoding drops all metadata, GPS included.

supabase-kt keeps the session in the app's private storage. Backups are off (`allowBackup="false"`), and `data_extraction_rules.xml` also keeps the app's data out of Android 12+ cloud backups and phone-to-phone transfers, so a copied phone doesn't arrive signed in.

The Ktor HTTP engine and Coil's network fetcher are wired up by hand in `Backend.kt` and `DheuApp.kt`. Libraries usually find them through `ServiceLoader`, which R8 can strip from a release build.

## Licences

The app is GPL-3.0 like the rest of the repo. Inter and Outfit are under the SIL Open Font License and Lucide's icons under the ISC licence; their notices ship in `app/src/main/assets/licenses/`.
