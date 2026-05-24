import { useState, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { userRegisterAPI, sendVerificationCodeAPI, activateAccountAPI } from '@/api'
import { toast } from 'sonner'

const RESEND_COOLDOWN_SECONDS = 60

export default function Register() {
  const navigate = useNavigate()
  const [step, setStep] = useState<'form' | 'verify'>('form')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [identifier, setIdentifier] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [resendCooldown, setResendCooldown] = useState(0)

  useEffect(() => {
    if (resendCooldown <= 0) return
    const timer = setInterval(() => setResendCooldown((c) => c - 1), 1000)
    return () => clearInterval(timer)
  }, [resendCooldown])

  const handleRegisterSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }
    setLoading(true)
    try {
      await userRegisterAPI({ username, email, password })
      const normalizedEmail = email.trim().toLowerCase()
      await sendVerificationCodeAPI(normalizedEmail)
      setIdentifier(normalizedEmail)
      setStep('verify')
      toast.success('Registration successful. Check your email for the verification code.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed')
    } finally {
      setLoading(false)
    }
  }

  const handleResendCode = async () => {
    if (resendCooldown > 0) return
    setError(null)
    try {
      await sendVerificationCodeAPI(identifier)
      setResendCooldown(RESEND_COOLDOWN_SECONDS)
      toast.success('Verification code sent. Please check your email.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send code')
    }
  }

  const handleVerifySubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    const trimmed = code.trim()
    if (trimmed.length !== 6 || !/^\d{6}$/.test(trimmed)) {
      setError('Please enter a 6-digit verification code.')
      return
    }
    setLoading(true)
    try {
      await activateAccountAPI(identifier, trimmed)
      toast.success('Account activated. You can sign in now.')
      navigate('/login', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Verification failed')
    } finally {
      setLoading(false)
    }
  }

  const handleBackToForm = () => {
    setStep('form')
    setCode('')
    setError(null)
    setResendCooldown(0)
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 pt-24 pb-12">
      {/* Background Decoration */}
      <div className="fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 -left-40 h-80 w-80 rounded-full bg-secondary/20 blur-3xl" />
        <div className="absolute -bottom-40 -right-40 h-80 w-80 rounded-full bg-primary/20 blur-3xl" />
        <div className="absolute top-1/3 right-1/4 h-60 w-60 rounded-full bg-accent/10 blur-3xl" />
      </div>

      <div className="w-full max-w-md">
        {/* Card */}
        <div className="glass-card p-8">
          {step === 'form' ? (
            <>
          {/* Header */}
          <div className="text-center mb-8">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-white text-gray-900 shadow-lg shadow-gray-900/10 border border-gray-200">
              <svg
                className="h-7 w-7"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="8.5" cy="7" r="4" />
                <line x1="20" y1="8" x2="20" y2="14" />
                <line x1="23" y1="11" x2="17" y2="11" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-foreground">
              Create Account
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              Join us and start your journey
            </p>
          </div>

          <form onSubmit={handleRegisterSubmit} className="space-y-5">
            {error && (
              <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive" role="alert">
                <svg className="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>{error}</span>
              </div>
            )}

            <div className="space-y-4">
              <div>
                <label htmlFor="username" className="mb-1.5 block text-sm font-medium text-foreground">
                  Username
                </label>
                <input
                  id="username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  required
                  minLength={3}
                  maxLength={64}
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="glass-input w-full rounded-lg px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  placeholder="3-64 characters, letters, numbers, _, -, ."
                />
              </div>

              <div>
                <label htmlFor="email" className="mb-1.5 block text-sm font-medium text-foreground">
                  Email
                </label>
                <input
                  id="email"
                  name="email"
                  type="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="glass-input w-full rounded-lg px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  placeholder="Enter your email address"
                />
              </div>

              <div>
                <label htmlFor="password" className="mb-1.5 block text-sm font-medium text-foreground">
                  Password
                </label>
                <input
                  id="password"
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  required
                  minLength={8}
                  maxLength={128}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="glass-input w-full rounded-lg px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  placeholder="8-128 characters"
                />
              </div>

              <div>
                <label htmlFor="confirm-password" className="mb-1.5 block text-sm font-medium text-foreground">
                  Confirm Password
                </label>
                <input
                  id="confirm-password"
                  name="confirm-password"
                  type="password"
                  autoComplete="new-password"
                  required
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="glass-input w-full rounded-lg px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  placeholder="Confirm your password"
                />
              </div>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="btn-glow relative w-full rounded-lg py-3 text-sm font-semibold shadow-lg transition-all duration-200 hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              style={{ backgroundColor: '#00A8E8', color: '#ffffff' }}
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                  </svg>
                  Creating account...
                </span>
              ) : (
                'Create Account'
              )}
            </button>

            <p className="text-center text-xs text-muted-foreground">
              By creating an account, you agree to our{' '}
              <a href="#" className="text-primary hover:underline">Terms of Service</a>
              {' '}and{' '}
              <a href="#" className="text-primary hover:underline">Privacy Policy</a>
            </p>
          </form>
            </>
          ) : (
            <>
          <div className="text-center mb-8">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-white text-gray-900 shadow-lg shadow-gray-900/10 border border-gray-200">
              <svg
                className="h-7 w-7"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
                <polyline points="22,6 12,13 2,6" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-foreground">
              Verify your email
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              We&apos;ve sent a 6-digit code to <strong className="text-foreground">{identifier}</strong>. You can click the link in the email to activate, or enter the code below.
            </p>
          </div>

          <form onSubmit={handleVerifySubmit} className="space-y-5">
            {error && (
              <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive" role="alert">
                <svg className="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>{error}</span>
              </div>
            )}

            <div>
              <label htmlFor="code" className="mb-1.5 block text-sm font-medium text-foreground">
                Verification code
              </label>
              <input
                id="code"
                name="code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                required
                minLength={6}
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                className="glass-input w-full rounded-lg px-4 py-3 text-center text-lg tracking-[0.5em] font-mono text-foreground placeholder:text-muted-foreground focus:outline-none"
                placeholder="000000"
              />
            </div>

            <button
              type="submit"
              disabled={loading || code.length !== 6}
              className="btn-glow relative w-full rounded-lg py-3 text-sm font-semibold shadow-lg transition-all duration-200 hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              style={{ backgroundColor: '#00A8E8', color: '#ffffff' }}
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                  </svg>
                  Verifying...
                </span>
              ) : (
                'Verify and activate'
              )}
            </button>

            <div className="text-center">
              <button
                type="button"
                onClick={handleResendCode}
                disabled={resendCooldown > 0}
                className="text-sm text-primary hover:underline disabled:text-muted-foreground disabled:no-underline disabled:cursor-not-allowed"
              >
                {resendCooldown > 0
                  ? `Resend code in ${resendCooldown}s`
                  : 'Resend verification code'}
              </button>
            </div>

            <p className="text-center text-sm text-muted-foreground">
              <button
                type="button"
                onClick={handleBackToForm}
                className="text-primary hover:underline"
              >
                Use a different email
              </button>
            </p>
          </form>
            </>
          )}

          {/* Divider */}
          <div className="relative my-6">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-border" />
            </div>
            <div className="relative flex justify-center text-xs uppercase">
              <span className="bg-card px-2 text-muted-foreground">Or</span>
            </div>
          </div>

          {/* Sign In Link */}
          <p className="text-center text-sm text-muted-foreground">
            Already have an account?{' '}
            <Link
              to="/login"
              className="font-medium text-primary hover:text-primary/80 transition-colors"
            >
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
