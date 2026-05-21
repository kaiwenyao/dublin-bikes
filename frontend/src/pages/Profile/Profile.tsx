import { useEffect, useState } from 'react'
import { getMeAPI } from '@/api'
import type { UserProfileVO } from '@/api'

function ProfileSkeleton() {
  return (
    <div className="space-y-6">
      {/* Avatar Skeleton */}
      <div className="flex items-center gap-4">
        <div className="h-20 w-20 rounded-full skeleton" />
        <div className="space-y-2">
          <div className="h-6 w-32 rounded skeleton" />
          <div className="h-4 w-48 rounded skeleton" />
        </div>
      </div>
      {/* Info Skeleton */}
      <div className="space-y-4">
        <div className="h-4 w-full rounded skeleton" />
        <div className="h-4 w-3/4 rounded skeleton" />
        <div className="h-4 w-1/2 rounded skeleton" />
      </div>
    </div>
  )
}

function ProfileField({ label, value, icon }: { label: string; value: string | number | undefined; icon: React.ReactNode }) {
  return (
    <div className="flex items-center gap-4 rounded-lg bg-muted/50 p-4 transition-colors hover:bg-muted">
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
        {icon}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {label}
        </p>
        <p className="truncate text-sm font-medium text-foreground">
          {value ?? '-'}
        </p>
      </div>
    </div>
  )
}

export default function Profile() {
  const [loading, setLoading] = useState(true)
  const [profile, setProfile] = useState<UserProfileVO | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let isMounted = true

    const fetchProfile = async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await getMeAPI()
        if (isMounted) {
          setProfile(data)
        }
      } catch (err) {
        if (isMounted) {
          setError(err instanceof Error ? err.message : 'Network error')
        }
      } finally {
        if (isMounted) {
          setLoading(false)
        }
      }
    }

    fetchProfile()

    return () => {
      isMounted = false
    }
  }, [])

  return (
    <div className="min-h-screen px-4 pt-28 pb-12">
      {/* Background Decoration */}
      <div className="fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-40 right-1/4 h-80 w-80 rounded-full bg-primary/10 blur-3xl" />
        <div className="absolute bottom-0 left-0 h-60 w-60 rounded-full bg-secondary/15 blur-3xl" />
      </div>

      <div className="mx-auto max-w-2xl">
        {/* Header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-foreground">Profile</h1>
          <p className="mt-1 text-muted-foreground">Manage your account information</p>
        </div>

        {/* Content Card */}
        <div className="glass-card p-6">
          {loading ? (
            <ProfileSkeleton />
          ) : error ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10 text-destructive">
                <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
              </div>
              <h3 className="text-lg font-semibold text-foreground">Failed to load profile</h3>
              <p className="mt-1 text-sm text-muted-foreground">{error}</p>
              <button
                onClick={() => window.location.reload()}
                className="mt-4 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary/90 cursor-pointer"
              >
                Try Again
              </button>
            </div>
          ) : profile ? (
            <div className="space-y-6">
              {/* Profile Header */}
              <div className="flex items-center gap-4">
                <div className="flex h-20 w-20 items-center justify-center rounded-full bg-gradient-primary text-3xl font-bold text-white shadow-lg shadow-primary/25">
                  {profile.username?.charAt(0).toUpperCase() || 'U'}
                </div>
                <div>
                  <h2 className="text-xl font-bold text-foreground">
                    {profile.username || 'User'}
                  </h2>
                  <p className="text-sm text-muted-foreground">
                    {profile.email || 'No email provided'}
                  </p>
                  <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-green-500/10 px-2 py-0.5 text-xs font-medium text-green-600 dark:text-green-400">
                    <span className="h-1.5 w-1.5 rounded-full bg-current" />
                    Active
                  </span>
                </div>
              </div>

              {/* Divider */}
              <div className="border-t border-border" />

              {/* Profile Fields */}
              <div className="grid gap-3">
                <ProfileField
                  label="User ID"
                  value={profile.id}
                  icon={
                    <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
                      <line x1="16" y1="2" x2="16" y2="6" />
                      <line x1="8" y1="2" x2="8" y2="6" />
                      <line x1="3" y1="10" x2="21" y2="10" />
                    </svg>
                  }
                />
                <ProfileField
                  label="Username"
                  value={profile.username}
                  icon={
                    <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  }
                />
                <ProfileField
                  label="Email"
                  value={profile.email}
                  icon={
                    <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
                      <polyline points="22,6 12,13 2,6" />
                    </svg>
                  }
                />
                {profile.created_at && (
                  <ProfileField
                    label="Member Since"
                    value={new Date(profile.created_at).toLocaleDateString('en-US', {
                      year: 'numeric',
                      month: 'long',
                      day: 'numeric'
                    })}
                    icon={
                      <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <circle cx="12" cy="12" r="10" />
                        <polyline points="12 6 12 12 16 14" />
                      </svg>
                    }
                  />
                )}
              </div>

              {/* Actions */}
              <div className="flex gap-3 pt-2">
                <button className="flex-1 rounded-lg border border-border bg-transparent py-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted cursor-pointer">
                  Edit Profile
                </button>
                <button className="flex-1 rounded-lg py-2.5 text-sm font-medium text-white shadow-lg transition-colors hover:opacity-90 cursor-pointer" style={{ backgroundColor: '#00A8E8', boxShadow: '0 10px 15px -3px rgba(0, 168, 232, 0.25)' }}>
                  Settings
                </button>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted text-muted-foreground">
                <svg className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              </div>
              <h3 className="text-lg font-semibold text-foreground">No profile data</h3>
              <p className="mt-1 text-sm text-muted-foreground">Your profile information is not available</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
