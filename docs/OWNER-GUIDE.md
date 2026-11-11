# Dheu — owner's guide

How to run, deploy and look after Dheu. For an overview of the project see the [README](../README.md).

A small, invite-only photo-sharing community. Still images only: public or private accounts, posts (up to 10 photos, gone after 3 months), 24-hour stories with highlights, 24-hour notes, follows, likes, comments, an activity feed (likes / follows / comments / story reactions), and 1-to-1 and group chat.

Built with **Next.js** (the website) and **Supabase** (database, login, image storage, realtime chat). Hosted for free on **Vercel**.

---

## 1. Running it on your own computer

```bash
npm install
npm run dev
```

Then open http://localhost:3000. The file `.env.local` holds the keys (never share it or commit it to git).

Useful commands:

| Command         | What it does                                  |
| --------------- | --------------------------------------------- |
| `npm run dev`   | Start the site locally with live reload       |
| `npm run build` | Check that the site builds (Vercel runs this) |
| `npm run lint`  | Check the code for mistakes                   |

---

## 2. One-time setup checklist

1. **Supabase project** – create one at supabase.com, then run these files in **SQL Editor → New query**, in order:
   - `supabase/migrations/0001_init.sql`
   - `supabase/migrations/0002_admin.sql`
   - `supabase/migrations/0003_notifications.sql`
   - `supabase/migrations/0004_private_blocks_chat.sql`
   - `supabase/migrations/0005_fixes.sql`
   - `supabase/migrations/0006_announcements.sql`
   - `supabase/migrations/0007_server_read_marks.sql`
   - `supabase/migrations/0008_suggestions_push.sql` (replace the two placeholders first — see section 3a)
   - `supabase/migrations/0009_push_failsafe.sql` (same two placeholders)
   - `supabase/migrations/0010_voice_messages.sql`
   - `supabase/migrations/0011_group_chats.sql`
   - `supabase/migrations/0012_group_system_messages.sql`
   - `supabase/migrations/0013_post_thumbnails.sql`
   - `supabase/migrations/0014_rpc_reads.sql` (run it before deploying code that uses it)
   - `supabase/migrations/0015_rls_initplan.sql` (optional speed-up; the app works without it)
   - `supabase/migrations/0016_account_privacy.sql` (public / private accounts)
   - `supabase/migrations/0017_notes.sql`
   - `supabase/migrations/0018_posts_three_months.sql`
   - `supabase/migrations/0019_delete_chat_unsend.sql`
   - `supabase/migrations/0020_mentions_app_push.sql` (@mentions, and push for the Android app)
   - `supabase/migrations/0021_function_grants.sql` (database functions can only be called when signed in; no code depends on it)
   - `supabase/migrations/0022_read_receipts.sql` (read receipts; the apps work without it, minus "Seen")
   - `supabase/migrations/0023_chat_extras.sql` (reactions, typing, view-once photos, "Seen" in the inbox; run before deploying the code that uses it)
   - `supabase/migrations/0024_posts_stories_extras.sql` (close friends stories, saved posts, comment replies and likes; run before deploying the code that uses it)
   - `supabase/migrations/0025_safety_privacy.sql` (reports, the block check out of the API's reach, photo access rules; run before deploying the code that uses it)
   (0016–0020 must run before deploying the code that uses them.)
   Never add the `private` schema (made by 0024) to the API's exposed schemas: it holds checks that members must not be able to call.
2. **Turn off "Confirm email"** – Supabase → Authentication → Sign In / Providers → Email → untick _Confirm email_. (Accounts are created by the app with the invite code, so no confirmation email is needed. The free tier can't send emails to arbitrary addresses anyway.)
3. **Keys** – Supabase → Project Settings → API keys. Put the URL, the _publishable_ key and the _secret_ key in `.env.local` (copy `.env.example`).
4. **First account = admin.** The very first person to sign up doesn't need an invite code and automatically becomes the admin. Do this yourself immediately after deploying.

---

## 3. Deploying to Vercel (free)

1. Push the code to a GitHub repository.
2. On vercel.com → **Add New… → Project** → import the repo. Framework is detected automatically.
3. Under **Environment Variables**, add every line from `.env.local`.
   (`NEXT_PUBLIC_SITE_URL` is only a fallback — the app detects its own address.)
4. Deploy. Every later `git push` redeploys automatically.
5. Vercel runs the cleanup job (`/api/cron/cleanup`) once a day (see `vercel.json`). It needs the `CRON_SECRET` variable to be set in Vercel too.

To use a custom domain later: Vercel → Project → Settings → Domains.

### 3a. Push notifications (one-time setup)

1. Generate keys once on your PC: `npx web-push generate-vapid-keys` (or reuse the ones already in `.env.local`).
2. Add three variables in Vercel → Settings → Environment Variables, then redeploy:
   `NEXT_PUBLIC_VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `PUSH_WEBHOOK_SECRET` (any long random string).
3. In `0008_suggestions_push.sql`, replace `__PUSH_URL__` with `https://<your-site>/api/push` and `__PUSH_SECRET__` with the same secret, then run it in the SQL Editor.
4. Members turn notifications on under Settings → Notifications (on iPhone only after adding Dheu to the Home Screen).

### 3b. Push notifications for the Android app (one-time setup)

The app uses Firebase Cloud Messaging; the website does the sending.

1. In the Firebase console, create a project and add an Android app with the package name `com.salmansaadiq.dheu`. Download `google-services.json` into `android/app/` before building the app (see `android/README.md`).
2. Project settings → Service accounts → **Generate new private key**. In Vercel → Settings → Environment Variables, add `FCM_SERVICE_ACCOUNT` with the whole contents of that JSON file, then redeploy. Treat it like the Supabase secret key.
3. Check `https://<your-site>/api/health`: `push.fcmProject` shows your Firebase project's ID once it's set.
4. In the app, notifications are on by default; Android 13 and newer asks once after signing in. Members can switch them off under Settings → Notifications.

---

## 4. Day-to-day admin

Everything is under **Settings → Open admin panel** (only visible to the admin).

- **Invites** – generate a code, set how many people can use it and when it expires. Members can also make their own single-use codes (Settings → Share with friends, max 5 unused each); those appear here too, marked "by @username". The copy button gives a link like `https://dheu-beige.vercel.app/signup?code=WAVE-XXXX-XXXX` with the code pre-filled.
- **Members** – suspend (blocks login instantly), reinstate, set a temporary password for someone who forgot theirs (tell them privately; they change it in Settings), or delete a member and everything they posted.
- **Privacy** – each member chooses public or private at sign-up and can switch in Settings → Account privacy (members who joined before this existed are asked once). Public accounts are visible to every member and can be followed instantly; private accounts need an accepted follow request. Members can block each other (Settings → Blocked accounts to undo).
- **Suggestions** – pick members to appear in the "Suggested for you" strip that shows in feeds now and then; drag order with the arrows.
- **Announcements** – Messages → *Dheu announcements* (pinned at the top of everyone's inbox). Only you can post there; members get an unread badge and see it instantly. Use it for new features, downtime notices, house rules.
- **Content** – see all active stories and recent posts; delete anything. You can also delete any post from the "…" menu on the post itself.
- **Reports** – what members reported (a post, story, comment or account), one card per thing with every reason given. Remove it, suspend the member, mark it handled, or dismiss it; closed reports stay listed with who closed them. You get a push for each new report.
- **Overview** – open reports, member count, storage used vs. the free 1 GB, etc.
- **Deleted accounts** – members can delete their own account (Settings → Delete account, password needed). It removes their files and everything they posted, like deleting a member here. The admin account can't delete itself.

### Making photos private (once members have updated)

Photos used to be readable by anyone who had a photo's link. Since 0025, the website and the app (from the version after 0.2.0) load every photo through a short-lived signed link, which Storage gives only to members allowed to see that photo. The buckets themselves are still public, because older app versions load photos from the public links. Once everyone has updated the app, switch them off:

1. Supabase → Storage → for each of `avatars`, `posts`, `stories` and `chat`: **Edit bucket** → untick **Public bucket** → Save. (Or in the SQL Editor: `update storage.buckets set public = false where id in ('avatars', 'posts', 'stories', 'chat');`)
2. Check the website and the app still show photos. Anyone on the old app will see blank photos until they update.

To undo, tick **Public bucket** again (or run the same statement with `public = true`).

---

## 5. Limits of the free tiers (what to expect)

| Service  | Free limit                                       | What happens                                                                                               |
| -------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Supabase | 500 MB database, 1 GB images, 5 GB bandwidth/mo  | Images are resized to 1080 px (~150–400 KB each). Expired posts/stories are deleted daily to free space.   |
| Supabase | Project **pauses after 7 days with no traffic**  | The site shows errors until you click **Restore** in the Supabase dashboard. Regular use prevents this.    |
| Vercel   | 100 GB bandwidth/mo, non-commercial use          | Fine for a small community.                                                                                |

If you outgrow this, Supabase Pro ($25/mo) lifts the limits with no code changes.

---

## 6. If something breaks

- **"Couldn't load stats" in admin** → run `0002_admin.sql` in the SQL Editor.
- **Everything errors after a quiet week** → Supabase project is paused; restore it in the dashboard.
- **Someone can't log in** → check Members: are they suspended? Otherwise set them a temporary password.
- **Images don't upload** → Supabase → Storage: the buckets `avatars`, `posts`, `stories` must exist (they're created by `0001_init.sql`).
- **Rotate a leaked key** → Supabase → Project Settings → API keys → rotate, then update `.env.local` and the Vercel environment variables, and redeploy.

---

## 7. Project layout (for developers)

```
src/app/(auth)/        login & sign-up (server actions in actions.ts)
src/app/(app)/         everything behind login: feed, profiles, posts, stories, chat, admin
src/app/api/cron/      daily cleanup of expired posts/stories
src/components/        UI pieces
src/lib/data/          server-side data queries (Supabase)
src/lib/supabase/      Supabase clients (browser, server, admin) + session refresh (proxy)
src/lib/images.ts      browser-side resize / HEIC conversion / metadata stripping
supabase/migrations/   database schema, security rules (RLS), storage buckets
scripts/build-logo.mjs regenerates public/brand + app icons from the original logo PNGs
```

Security model: every table has Row Level Security. Users can only change their own data; the admin can delete anything. Privileged actions (sign-up, bans, cleanup) run on the server with the secret key.
