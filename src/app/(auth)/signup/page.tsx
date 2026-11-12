import type { Metadata } from "next";
import { isInviteOnly } from "@/lib/signup-settings";
import { SignupForm } from "./SignupForm";

export const metadata: Metadata = { title: "Create account" };

export default async function SignupPage(props: PageProps<"/signup">) {
  const sp = await props.searchParams;
  const code = typeof sp.code === "string" ? sp.code : undefined;
  // Open to anyone unless the admin has made sign-up invite-only (0028).
  const inviteOnly = await isInviteOnly();

  return (
    <>
      <h1 className="mb-1 font-display text-xl font-semibold">Join Dheu</h1>
      <p className="mb-5 text-sm text-ink-muted">
        {inviteOnly ? "You need an invite code from a member." : "Create your account and say hi."}
      </p>
      <SignupForm inviteOnly={inviteOnly} inviteCode={code} />
    </>
  );
}
