<p align="center">
  <img src="docs/screenshots/hero.png" alt="Dheu, a small invite-only photo community" width="100%" />
</p>

<h1 align="center">Dheu</h1>

<p align="center">
  An invite-only, Instagram-style photo community for a small circle of people, on the web and on Android.<br/>
  Public or private accounts, posts that expire after 3 months, 24-hour stories and notes, real-time chat with disappearing photos<br/>and push notifications, all running on free tiers.
</p>

<p align="center">
  <a href="https://dheu-beige.vercel.app"><img alt="Live" src="https://img.shields.io/badge/live-dheu--beige.vercel.app-1589a5?style=flat-square" /></a>
  <a href="https://github.com/brownfishsalman/Dheu-Social-Media/releases/latest"><img alt="Android APK" src="https://img.shields.io/badge/Android-APK-3ddc84?style=flat-square&logo=android&logoColor=white" /></a>
  <img alt="Next.js 16" src="https://img.shields.io/badge/Next.js-16-000000?style=flat-square&logo=nextdotjs" />
  <img alt="React 19" src="https://img.shields.io/badge/React-19-20232a?style=flat-square&logo=react" />
  <img alt="TypeScript" src="https://img.shields.io/badge/TypeScript-strict-3178c6?style=flat-square&logo=typescript&logoColor=white" />
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7f52ff?style=flat-square&logo=kotlin&logoColor=white" />
  <img alt="Supabase" src="https://img.shields.io/badge/Supabase-Postgres%20%C2%B7%20Auth%20%C2%B7%20Storage%20%C2%B7%20Realtime-3ecf8e?style=flat-square&logo=supabase&logoColor=white" />
  <img alt="Tailwind CSS 4" src="https://img.shields.io/badge/Tailwind_CSS-4-06b6d4?style=flat-square&logo=tailwindcss&logoColor=white" />
  <img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-00273d?style=flat-square" />
</p>

*Dheu* means wave in Bengali. It's built for one small community that wanted the feel of early Instagram: photos from friends and nothing else, with no ads, no algorithm and no audience of strangers. You can only join with an invite code.

## Features

| | |
|---|---|
| **Public or private** | Each member picks one at sign-up and can switch later in Settings. Anyone in the community can see a public account's posts, stories, highlights and followers, and following it is instant. Following a private account sends a request that the owner accepts or declines from Activity, and only approved followers see what they post. Switching to public approves any pending requests. Row Level Security in Postgres enforces these rules. Blocking someone removes every tie between the two of you, in both directions, and hides you from each other. |
| **Posts** | 1 to 10 photos per post in a swipeable carousel, at any aspect ratio, with a caption, likes (double-tap works too) and comments. Comments take replies (one level, as on Instagram) and likes. Bookmark a post to keep it in a Saved tab on your profile that only you can see, and save your own photos to your phone or computer, full size, before they go. Every post expires 3 months after it's shared. |
| **Mentions** | Type @ in a caption, a comment or a group chat to tag someone, with suggestions as you type. They get an Activity entry and a notification, but only if they're allowed to see the post, or are in the group. |
| **Notes** | A 60-character note that sits above your avatar at the top of Messages for 24 hours. Mutual follows can see it, and a reply arrives as a DM that quotes the note. |
| **Stories** | 24-hour photo stories in a full-screen viewer with progress bars, tap, hold and swipe gestures, and keyboard controls on desktop. Viewers can react with an emoji or reply in chat, you can see who has seen each story, and Highlights keep chosen stories on your profile for good. A story can go to everyone who sees your stories or only to your close friends, a list in Settings that only you can see; theirs show with a green ring. |
| **Chat** | 1-to-1 and group chats delivered over WebSockets. A group holds up to 50 people; any member can add people or rename it, and the creator can remove people. Photos and voice messages (recorded in the browser, up to 2 minutes) disappear after 7 days, and a photo can be sent as view-once: each recipient can open it a single time. You see when someone is typing, react to a message with an emoji or a double-tap ❤️, and see "Seen" under your last message (anyone can switch their own read receipts off). You can swipe to reply with a quote, unsend your own messages, and delete a chat from your inbox without affecting anyone else's. Unread badges, and a pinned read-only announcements thread from the admin in every inbox. |
| **Activity and push** | Follow requests, accepted requests, likes, comments, mentions, new followers and story reactions each raise an alert. Database triggers create them and a live badge counts them. Reactions to your messages and likes on your comments arrive as push notifications only. Installed PWAs receive them as Web Push notifications (on Android, and on iOS 16.4+ from the Home Screen), and the Android app through Firebase Cloud Messaging. |
| **Discovery** | People search, follower lists, and a "Suggested for you" strip, chosen by the admin, that turns up in the feed now and then. |
| **Photos** | Resized to 1080 px on the device before upload. HEIC files become JPEGs, EXIF orientation is applied, and GPS and other metadata are stripped. |
| **Accounts** | Email and password. Sign-up needs an invite code; members create single-use codes for friends and share them from Settings. The first account becomes the admin. Members can delete their own account, with their password, and everything they shared goes with it. Light, dark or system theme, and the website installs as a home-screen app (PWA). |
| **Reports** | Any post, story, comment or account can be reported with a reason and a note. Only the admin sees reports, in a queue where they can remove the content, suspend the member, or dismiss it, and they get a push for each new one. |
| **Admin** | Invite codes with use limits and expiry, suspending members, resetting passwords, deleting accounts, the reports queue, crash reports from the app, content moderation, curating suggestions, announcements, and an overview of storage use. |
| **Housekeeping** | A nightly job deletes expired posts, stories, chat photos, opened view-once photos, old notifications and crash reports, along with their files. |

## Screenshots

<p align="center">
  <img src="docs/screenshots/feed.png" width="19%" alt="Feed with suggested people" />
  <img src="docs/screenshots/story.png" width="19%" alt="Story viewer" />
  <img src="docs/screenshots/post.png" width="19%" alt="Post with comments" />
  <img src="docs/screenshots/activity.png" width="19%" alt="Activity with follow requests" />
  <img src="docs/screenshots/chat.png" width="19%" alt="Chat with a disappearing photo and a quoted reply" />
</p>
<p align="center">
  <img src="docs/screenshots/profile.png" width="19%" alt="Profile" />
  <img src="docs/screenshots/private-profile.png" width="19%" alt="Private profile with a pending request" />
  <img src="docs/screenshots/settings.png" width="19%" alt="Settings: push notifications and invite codes" />
  <img src="docs/screenshots/feed-dark.png" width="19%" alt="Dark mode" />
  <img src="docs/screenshots/login-dark.png" width="19%" alt="Login (dark)" />
</p>
<p align="center">
  <img src="docs/screenshots/desktop-feed.png" width="49%" alt="Desktop feed" />
  <img src="docs/screenshots/desktop-chat.png" width="49%" alt="Desktop chat" />
</p>

<sub>Demo accounts and stock photos; not real members.</sub>

## Android app

[`android/`](android) holds a native Android app written in Kotlin with Jetpack Compose. It uses the same Supabase project and the same Row Level Security rules as the website, so a member can move between the two and see the same feed, chats and notifications. It runs on Android 9 and newer.

The app does everything a member can do on the website: the feed with suggestions, posts, profiles with highlights, stories (close friends too), Activity, Messages with notes, voice messages and group chats you can start and manage, @mentions, reports, and push notifications. Only the admin tools stay on the website. From 0.3.0 it tells you when a newer version is out, and if it crashes it sends the details to the admin the next time it opens.

To try it, download the APK from the [latest release](https://github.com/brownfishsalman/Dheu-Social-Media/releases/latest) on your phone and open it. If you have 0.1.0 or 0.2.0, uninstall it first: 0.3.0 is signed with a new key, and you only need to log in again. Android will ask you to allow your browser to install apps; allow it for this one install. Build steps, release signing and a tour of the code are in [android/README.md](android/README.md).

## How it works

```mermaid
flowchart LR
  B["Browser / installed PWA<br/>React 19 client components<br/>+ service worker"]
  A["Android app<br/>Kotlin + Jetpack Compose<br/>supabase-kt"]
  V["Vercel (sin1)<br/>Next.js 16 server components,<br/>server actions, /api/*, nightly cron"]
  subgraph S["Supabase (ap-southeast-1)"]
    DB[("Postgres<br/>25 tables · 69 RLS policies<br/>20 triggers · Vault")]
    AUTH["Auth<br/>email + password, ES256 JWT"]
    ST["Storage<br/>avatars · posts · stories · chat"]
    RT["Realtime<br/>Postgres changes over WebSocket"]
  end
  P["Push services<br/>FCM / APNs / Mozilla<br/>(Web Push + Firebase for the app)"]
  B -- "pages & actions" --> V
  A -- "sign-up, invite codes<br/>(Bearer token)" --> V
  V -- "queries (user JWT) /<br/>privileged ops (secret key)" --> DB
  B -- "likes, follows, comments,<br/>uploads (user JWT)" --> DB & ST
  A -- "every read and write<br/>(user JWT)" --> DB & ST
  B -- "chat, badges, announcements" --> RT
  A -- "chat, badges" --> RT
  RT --> DB
  DB -- "pg_net webhook on<br/>notification / message / announcement" --> V
  V -- "Web Push (VAPID)" --> P
  P -- "push event" --> B
  P -- "push (Firebase)" --> A
  B --> AUTH
  A --> AUTH
```

### One codebase, no separate backend

Next.js server components render pages on Vercel and query Supabase directly. The interactive parts (feed, story viewer, chat, uploads) are client components that talk to Supabase from the browser. The Android app also goes straight to Supabase through supabase-kt, and calls the website for only two jobs, explained in the next section.

### Security in the database

Every table has Row Level Security: 67 policies that Postgres checks against the caller's JWT. Members can change only their own rows, read only the chats they belong to, and update only the `read_at` column of their own notifications. The admin can delete anything. The browser and the APK carry only a publishable key, which grants nothing by itself. Privileged operations (invite-checked sign-up, bans, cleanup, sending push) run on the server with the secret key.

The secret key can't ship inside an APK, because anyone can unpack one. So the app sends sign-ups to `/api/auth/signup` and asks `/api/invites` for new invite codes, and the website does the privileged part. The invite endpoint verifies the member's Supabase access token before it creates a code.

### Privacy in one function

`can_view_content_of(author)` is true for your own content, for a public account, or for someone you follow, and never across a block. It is the single check behind the RLS policies for posts, images, comments, likes, stories and highlights. `follow_user()` follows a public account at once and sends a private one a `follow_requests` row, which only `accept_follow_request()` can turn into a `follows` row. Blocking fires a trigger that removes follows, requests and notifications in both directions.

### Notifications from triggers

Inserting a like, follow request, comment or story reaction fires a PL/pgSQL trigger that writes a notification row, and unliking or declining deletes it again. Realtime streams the insert to the recipient's open tab or app.

### Mentions are read by the database

`mentioned_profiles()` finds the @usernames in a caption, comment or group message, with the same rule the website and the app use to link them. Triggers on comments, the first photo of a post, and group messages turn them into notifications, but only for people allowed to see the post, or in the group, and never across a block. In a group, the mention replaces that person's ordinary message notification, so they get a single notification for it.

### Push: database, then site, then device

A second trigger on notifications, messages and announcements calls the site's `/api/push` through `pg_net`. The call is asynchronous, carries a shared secret kept in Supabase Vault, and can't block the original insert. The endpoint looks up the recipient's devices and sends a VAPID-signed Web Push to browsers, and a Firebase Cloud Messaging message to the Android app, signed in with a service account and no extra library. The service worker or the app shows it and opens the right screen on tap. Read markers use the database clock, so a phone with a wrong clock can't leave things marked unread.

### Photos are processed on the device

No server touches a photo before it's stored. The website decodes the file in the browser (HEIC through a WebAssembly build of libheif) and the Android app uses the platform's `ImageDecoder`. Both fix the orientation and redraw the photo at up to 1080 × 1920, which also strips all metadata, and cut a 480 px square thumbnail for profile grids. The JPEGs go straight to Storage under `<userId>/<uuid>.jpg`, and Storage policies allow writes only into your own folder.

### Photos load through signed links

Reading works the other way round. The website's `/media/<bucket>/<path>` route and the app's Coil fetcher ask Storage for a short-lived signed link with the member's own session, and Storage policies hand one out only to members allowed to see the row that uses the file: the post, the story (close friends included), the message or the profile. The app caches photos on disk by their path, so a photo seen before needs no new link. The buckets stay public until older app versions are gone; switching them to private then leaves the policies as the only way in (see the owner's guide).

### Expiry is a timestamp

`posts.expires_at` defaults to `now() + 3 months` and notes to `now() + 24 hours`. A trigger stamps chat photos and voice messages with `now() + 7 days` on insert, so a client can't pick its own expiry. RLS hides expired rows straight away, and a Vercel cron job (`/api/cron/cleanup`) deletes the rows and their files every night, keeping stories that are in a highlight.

### Region

Vercel's functions are pinned to the database's region (`vercel.json`), which on its own cut page render time by roughly 4×. Badge counts are rendered on the server and kept current over one shared Realtime channel, and a `loading.tsx` skeleton appears as soon as you navigate.

## Stack

- Website: Next.js 16 (App Router, Turbopack, server actions, `proxy.ts` for session refresh), React 19 and strict TypeScript. The `Database` type is generated from the schema, so every query is type-checked.
- Styling: Tailwind CSS 4 with a small set of tokens, light and dark themes, Inter and Outfit.
- Android: Kotlin, Jetpack Compose with Material 3, Navigation Compose, supabase-kt over Ktor and OkHttp, Coil for images, kotlinx.serialization, Firebase Cloud Messaging.
- Supabase: Postgres (with `pg_net` webhooks and Vault), Auth, Storage and Realtime. The schema, policies and triggers are in [`supabase/migrations`](supabase/migrations), and [`supabase/tests`](supabase/tests) checks the access rules on GitHub against a throwaway database built from them.
- CI on GitHub Actions: type-check, lint and build the website; build the Android app; build the database and run the access-rule tests.
- Vercel for hosting and cron, with functions pinned to the database's region.
- web-push (VAPID) for browser notifications and Firebase Cloud Messaging for the app, zod for input validation, lucide-react icons, heic-to for HEIC decoding.

About 11,100 lines of TypeScript, 8,700 of Kotlin and 2,600 of SQL (25 tables, 69 RLS policies, 20 triggers, 51 functions). The website has ten runtime dependencies.


## License

[GPL-3.0](LICENSE) © Salman Saadiq. The logo and the name *Dheu* are not covered by the licence.
