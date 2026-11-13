import type { Metadata } from "next";
import { ForgotPasswordForm } from "./ForgotPasswordForm";

export const metadata: Metadata = { title: "Reset your password" };

export default function ForgotPasswordPage() {
  return (
    <>
      <h1 className="mb-1 font-display text-xl font-semibold">Reset password</h1>
      <p className="mb-5 text-sm text-ink-muted">
        Get back into your account in two quick steps.
      </p>
      <ForgotPasswordForm />
    </>
  );
}
