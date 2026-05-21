import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { activateByTokenAPI } from '@/api'
import { toast } from 'sonner'

export default function Activate() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const lastTokenRef = useRef<string | null>(null)

  useEffect(() => {
    const normalizedToken = token?.trim() ?? ''
    if (!normalizedToken) return
    if (lastTokenRef.current === normalizedToken) return
    lastTokenRef.current = normalizedToken

    let cancelled = false

    const verify = async () => {
      setStatus('loading')
      setErrorMsg(null)
      try {
        await activateByTokenAPI(normalizedToken)
        if (cancelled) return
        setStatus('success')
        toast.success('Account activated. You can sign in now.')
        navigate('/login', { replace: true })
      } catch (err) {
        if (cancelled) return
        const msg = err instanceof Error ? err.message : 'Verification failed. The link may have expired.'
        setStatus('error')
        setErrorMsg(msg)
        toast.error(msg)
      }
    }

    verify()
    return () => {
      cancelled = true
    }
  }, [token, navigate])

  return (
    <div className="min-h-screen flex items-center justify-center px-4 pt-24 pb-12">
      <div className="fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 -left-40 h-80 w-80 rounded-full bg-secondary/20 blur-3xl" />
        <div className="absolute -bottom-40 -right-40 h-80 w-80 rounded-full bg-primary/20 blur-3xl" />
      </div>

      <div className="w-full max-w-md">
        <div className="glass-card p-8 text-center">
          {status === 'loading' && (
            <>
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-white text-gray-900 shadow-lg border border-gray-200">
                <svg
                  className="h-7 w-7 animate-spin text-primary"
                  viewBox="0 0 24 24"
                  fill="none"
                >
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
              </div>
              <h1 className="text-xl font-bold text-foreground">Verifying your email...</h1>
              <p className="mt-2 text-sm text-muted-foreground">Please wait, do not close this page.</p>
            </>
          )}

          {status === 'error' && (
            <>
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
                <svg className="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="15" y1="9" x2="9" y2="15" />
                  <line x1="9" y1="9" x2="15" y2="15" />
                </svg>
              </div>
              <h1 className="text-xl font-bold text-foreground">Verification failed</h1>
              <p className="mt-2 text-sm text-muted-foreground">{errorMsg}</p>
              <div className="mt-6 flex flex-col gap-3">
                <Link
                  to="/login"
                  className="inline-block rounded-lg py-2.5 px-4 text-sm font-medium text-primary hover:underline"
                >
                  Sign in
                </Link>
                <Link
                  to="/register"
                  className="inline-block rounded-lg py-2.5 px-4 text-sm font-medium text-muted-foreground hover:underline"
                >
                  Register again
                </Link>
              </div>
            </>
          )}

          {status === 'success' && (
            <>
              <h1 className="text-xl font-bold text-foreground">Verification successful</h1>
              <p className="mt-2 text-sm text-muted-foreground">Redirecting to sign in...</p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
