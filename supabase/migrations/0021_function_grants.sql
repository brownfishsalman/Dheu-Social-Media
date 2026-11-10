-- =====================================================================
--  Dheu — functions can only be called by signed-in members
--  Postgres lets every role execute a new function, and Supabase serves
--  each function in "public" at /rest/v1/rpc/<name>. So a caller holding
--  only the publishable key, not signed in, could call every SECURITY
--  DEFINER function below. Nothing relies on that: the website and the
--  app sign in before they touch the database, and sign-up goes through
--  the server. This takes that access away.
--
--  Members ("authenticated") and the server ("service_role") keep theirs
--  through their own grants, so nothing changes for a signed-in member.
--  SECURITY INVOKER functions (badge_counts, profile_stats, ...) are left
--  alone: they run as the caller, so Row Level Security already applies.
--
--  Supabase's Security Advisor lists these as "Public Can Execute
--  SECURITY DEFINER Function". Afterwards it still lists the 21 functions
--  of section 1 for signed-in users, which is intended.
--
--  A function added later is open to everyone again until it gets the
--  same line.
--  Run AFTER 0020_mentions_app_push.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) What members call, and the checks the policies use
-- ---------------------------------------------------------------------
-- "authenticated" keeps these: the clients call them, and a policy can
-- only use a function that the member reading the table may execute.

revoke execute on function public.accept_follow_request(uuid)             from public, anon;
revoke execute on function public.add_group_members(uuid, uuid[])         from public, anon;
revoke execute on function public.block_status(uuid)                      from public, anon;
revoke execute on function public.blocked_either(uuid, uuid)              from public, anon;
revoke execute on function public.can_read_message(uuid, uuid, timestamptz) from public, anon;
revoke execute on function public.can_view_content_of(uuid)               from public, anon;
revoke execute on function public.conversation_blocked(uuid)              from public, anon;
revoke execute on function public.create_group_conversation(text, uuid[]) from public, anon;
revoke execute on function public.delete_chat_for_me(uuid)                from public, anon;
revoke execute on function public.follow_user(uuid)                       from public, anon;
revoke execute on function public.get_or_create_conversation(uuid)        from public, anon;
revoke execute on function public.group_owner(uuid)                       from public, anon;
revoke execute on function public.has_blocked_me(uuid)                    from public, anon;
revoke execute on function public.is_admin()                              from public, anon;
revoke execute on function public.is_conversation_member(uuid)            from public, anon;
revoke execute on function public.leave_group(uuid)                       from public, anon;
revoke execute on function public.register_push_device(text)              from public, anon;
revoke execute on function public.remove_group_member(uuid, uuid)         from public, anon;
revoke execute on function public.rename_group(uuid, text)                from public, anon;
revoke execute on function public.set_account_privacy(boolean)            from public, anon;
revoke execute on function public.set_note(text)                          from public, anon;

-- ---------------------------------------------------------------------
-- 2) Trigger functions
-- ---------------------------------------------------------------------
-- Nobody calls these: they only run from their triggers. Postgres checks
-- EXECUTE on a trigger function when the trigger is created, not when it
-- fires, so the triggers keep working for every member.

revoke execute on function public.add_dm_members()          from public, anon, authenticated;
revoke execute on function public.handle_new_user()         from public, anon, authenticated;
revoke execute on function public.notify_caption_mentions() from public, anon, authenticated;
revoke execute on function public.notify_comment()          from public, anon, authenticated;
revoke execute on function public.notify_comment_mentions() from public, anon, authenticated;
revoke execute on function public.notify_follow()           from public, anon, authenticated;
revoke execute on function public.notify_follow_request()   from public, anon, authenticated;
revoke execute on function public.notify_message_mentions() from public, anon, authenticated;
revoke execute on function public.notify_post_like()        from public, anon, authenticated;
revoke execute on function public.notify_push_webhook()     from public, anon, authenticated;
revoke execute on function public.notify_story_reaction()   from public, anon, authenticated;
revoke execute on function public.on_block()                from public, anon, authenticated;
revoke execute on function public.retouch_conversation()    from public, anon, authenticated;
revoke execute on function public.touch_conversation()      from public, anon, authenticated;
revoke execute on function public.unnotify_follow()         from public, anon, authenticated;
revoke execute on function public.unnotify_follow_request() from public, anon, authenticated;
revoke execute on function public.unnotify_post_like()      from public, anon, authenticated;
