"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { createClient } from "@/lib/supabase/client";
import { ArrowLeft, CheckCircle2, KeyRound, Loader2, Mail } from "lucide-react";

export function ForgotPasswordForm() {
  const router = useRouter();
  const [step, setStep] = useState<1 | 2>(1);
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  async function handleSendCode(e: React.FormEvent) {
    e.preventDefault();
    const cleanEmail = email.trim();
    if (!cleanEmail || !cleanEmail.includes("@")) {
      setError("Please enter a valid email address.");
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const supabase = createClient();
      const { error: resetErr } = await supabase.auth.resetPasswordForEmail(cleanEmail);
      if (resetErr) {
        setError(resetErr.message || "Could not send recovery code. Please try again.");
      } else {
        setStep(2);
      }
    } catch {
      setError("Failed to connect. Please check your internet connection.");
    } finally {
      setLoading(false);
    }
  }

  async function handleResetPassword(e: React.FormEvent) {
    e.preventDefault();
    const cleanCode = code.trim().replace(/\s+/g, "");

    if (cleanCode.length < 6) {
      setError("Please enter the code sent to your email.");
      return;
    }
    if (password.length < 8) {
      setError("Password must be at least 8 characters long.");
      return;
    }
    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const supabase = createClient();

      // Step 1: Verify the recovery OTP code
      const { error: verifyErr } = await supabase.auth.verifyOtp({
        email: email.trim(),
        token: cleanCode,
        type: "recovery",
      });

      if (verifyErr) {
        setError("Invalid or expired code. Please double-check your code or request a new one.");
        setLoading(false);
        return;
      }

      // Step 2: Now update the password in the verified session
      const { error: updateErr } = await supabase.auth.updateUser({
        password,
      });

      if (updateErr) {
        setError(updateErr.message || "Failed to update password. Please try again.");
        setLoading(false);
        return;
      }

      setSuccess(true);
      setTimeout(() => {
        router.push("/");
        router.refresh();
      }, 1500);
    } catch {
      setError("An unexpected error occurred. Please try again.");
      setLoading(false);
    }
  }

  async function handleResendCode() {
    if (loading) return;
    setLoading(true);
    setError(null);
    try {
      const supabase = createClient();
      const { error: resetErr } = await supabase.auth.resetPasswordForEmail(email.trim());
      if (resetErr) {
        setError(resetErr.message);
      } else {
        setError(null);
      }
    } catch {
      setError("Could not resend code. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  if (success) {
    return (
      <div className="space-y-4 py-4 text-center">
        <div className="mx-auto flex size-12 items-center justify-center rounded-full bg-brand-soft text-brand-strong">
          <CheckCircle2 className="size-6" />
        </div>
        <h2 className="font-display text-lg font-semibold">Password updated!</h2>
        <p className="text-sm text-ink-muted">
          Your password has been changed successfully. Logging you in now…
        </p>
      </div>
    );
  }

  if (step === 1) {
    return (
      <form onSubmit={handleSendCode} className="space-y-3.5">
        <div>
          <label htmlFor="email" className="mb-1.5 block text-sm font-medium">
            Account email
          </label>
          <div className="relative">
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              required
              autoFocus
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="input pr-10"
            />
            <Mail className="pointer-events-none absolute right-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-faint" />
          </div>
          <p className="mt-1.5 text-xs text-ink-faint">
            We will email you a 6-digit recovery code to reset your password.
          </p>
        </div>

        {error && (
          <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={loading}
          className="btn-primary w-full flex items-center justify-center gap-2"
        >
          {loading && <Loader2 className="size-4 animate-spin" />}
          {loading ? "Sending code…" : "Send recovery code"}
        </button>

        <p className="pt-2 text-center text-sm text-ink-muted">
          <Link
            href="/login"
            className="inline-flex items-center gap-1.5 font-medium text-ink-muted hover:text-ink hover:underline"
          >
            <ArrowLeft className="size-3.5" /> Back to log in
          </Link>
        </p>
      </form>
    );
  }

  return (
    <form onSubmit={handleResetPassword} className="space-y-3.5">
      <div className="rounded-xl bg-brand-soft/60 p-3 text-xs text-brand-strong">
        Recovery code sent to <strong className="font-semibold">{email.trim()}</strong>. Enter the
        code below along with your new password.
      </div>

      <div>
        <label htmlFor="code" className="mb-1.5 block text-sm font-medium">
          Recovery code
        </label>
        <div className="relative">
          <input
            id="code"
            name="code"
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            maxLength={10}
            required
            autoFocus
            placeholder="Enter code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            className="input text-center font-mono text-lg tracking-[0.25em]"
          />
          <KeyRound className="pointer-events-none absolute right-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-faint" />
        </div>
      </div>

      <div>
        <label htmlFor="new-password" className="mb-1.5 block text-sm font-medium">
          New password
        </label>
        <input
          id="new-password"
          name="password"
          type="password"
          autoComplete="new-password"
          required
          placeholder="At least 8 characters"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="input"
        />
      </div>

      <div>
        <label htmlFor="confirm-password" className="mb-1.5 block text-sm font-medium">
          Confirm new password
        </label>
        <input
          id="confirm-password"
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          required
          placeholder="Repeat your new password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          className="input"
        />
      </div>

      {error && (
        <p role="alert" className="rounded-xl bg-danger-soft px-3.5 py-2.5 text-sm text-danger">
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={loading}
        className="btn-primary w-full flex items-center justify-center gap-2"
      >
        {loading && <Loader2 className="size-4 animate-spin" />}
        {loading ? "Resetting…" : "Reset password & log in"}
      </button>

      <div className="flex items-center justify-between pt-2 text-xs">
        <button
          type="button"
          onClick={handleResendCode}
          disabled={loading}
          className="font-medium text-brand hover:underline disabled:opacity-50"
        >
          Resend code
        </button>
        <button
          type="button"
          onClick={() => {
            setStep(1);
            setCode("");
            setError(null);
          }}
          className="text-ink-muted hover:text-ink hover:underline"
        >
          Change email
        </button>
      </div>

      <p className="pt-2 text-center text-sm text-ink-muted">
        <Link
          href="/login"
          className="inline-flex items-center gap-1.5 font-medium text-ink-muted hover:text-ink hover:underline"
        >
          <ArrowLeft className="size-3.5" /> Back to log in
        </Link>
      </p>
    </form>
  );
}
