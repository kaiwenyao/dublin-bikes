interface PageSkeletonProps {
  pathname: string
}

function SkeletonBlock({ className }: { className: string }) {
  return <div className={`skeleton rounded ${className}`} />
}

function DefaultPageSkeleton() {
  return (
    <section className="min-h-screen w-full pt-24 pb-12">
      <div className="mx-auto max-w-4xl px-4 sm:px-6">
        <SkeletonBlock className="h-8 w-48" />
        <SkeletonBlock className="mt-4 h-4 w-72" />
        <div className="mt-8 space-y-4">
          <SkeletonBlock className="h-4 w-full" />
          <SkeletonBlock className="h-4 w-5/6" />
          <SkeletonBlock className="h-4 w-4/6" />
          <SkeletonBlock className="mt-6 h-32 w-full rounded-xl" />
        </div>
      </div>
    </section>
  )
}

function NewsPageSkeleton() {
  return (
    <section className="min-h-screen w-full pt-24 pb-12">
      <div className="mx-auto max-w-4xl px-4 sm:px-6">
        <SkeletonBlock className="h-8 w-24" />
        <SkeletonBlock className="mt-3 h-4 w-40" />
      </div>
    </section>
  )
}

function ChatPageSkeleton() {
  return (
    <section className="flex h-screen w-full flex-col overflow-hidden pt-24 pb-8">
      <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col px-4">
        <div className="mb-4 shrink-0">
          <SkeletonBlock className="h-8 w-32" />
          <SkeletonBlock className="mt-2 h-4 w-64" />
        </div>
        <div className="flex min-h-0 flex-1 gap-4">
          <aside className="hidden w-72 shrink-0 flex-col gap-3 rounded-[28px] border border-white/45 bg-white/70 p-4 md:flex">
            <SkeletonBlock className="h-20 w-full rounded-2xl" />
            <SkeletonBlock className="h-10 w-full rounded-2xl" />
            <div className="mt-2 space-y-2">
              <SkeletonBlock className="h-16 w-full rounded-2xl" />
              <SkeletonBlock className="h-16 w-full rounded-2xl" />
              <SkeletonBlock className="h-16 w-full rounded-2xl" />
            </div>
          </aside>
          <div className="flex min-h-0 flex-1 flex-col rounded-[28px] border border-white/45 bg-white/70 p-4">
            <div className="flex-1 space-y-4">
              <div className="flex justify-end">
                <SkeletonBlock className="h-12 w-2/5 rounded-2xl" />
              </div>
              <div className="flex justify-start">
                <SkeletonBlock className="h-20 w-3/5 rounded-2xl" />
              </div>
              <div className="flex justify-end">
                <SkeletonBlock className="h-10 w-1/3 rounded-2xl" />
              </div>
            </div>
            <SkeletonBlock className="mt-4 h-12 w-full rounded-xl" />
          </div>
        </div>
      </div>
    </section>
  )
}

function MapsPageSkeleton() {
  return (
    <section className="relative h-full w-full overflow-hidden">
      <SkeletonBlock className="absolute inset-0 rounded-none" />
      <div className="absolute top-24 left-4 bottom-10 z-10 w-72 rounded-2xl bg-white/90 p-4 shadow-lg backdrop-blur-sm">
        <SkeletonBlock className="h-5 w-20" />
        <SkeletonBlock className="mt-4 h-10 w-full rounded-xl" />
        <div className="mt-4 space-y-2">
          <SkeletonBlock className="h-12 w-full rounded-lg" />
          <SkeletonBlock className="h-12 w-full rounded-lg" />
          <SkeletonBlock className="h-12 w-full rounded-lg" />
        </div>
        <SkeletonBlock className="mt-6 h-40 w-full rounded-xl" />
      </div>
    </section>
  )
}

function ProfilePageSkeleton() {
  return (
    <section className="min-h-screen w-full pt-24 pb-12">
      <div className="mx-auto max-w-2xl px-4 sm:px-6">
        <div className="rounded-2xl border bg-card p-6 shadow-sm">
          <div className="flex items-center gap-4">
            <SkeletonBlock className="h-20 w-20 rounded-full" />
            <div className="space-y-2">
              <SkeletonBlock className="h-6 w-32" />
              <SkeletonBlock className="h-4 w-48" />
            </div>
          </div>
          <div className="mt-6 space-y-4">
            <SkeletonBlock className="h-14 w-full rounded-lg" />
            <SkeletonBlock className="h-14 w-full rounded-lg" />
            <SkeletonBlock className="h-14 w-full rounded-lg" />
          </div>
        </div>
      </div>
    </section>
  )
}

function HomePageSkeleton() {
  return (
    <section className="relative min-h-screen w-full overflow-hidden">
      <SkeletonBlock className="absolute inset-0 rounded-none" />
      <div className="relative z-10 flex min-h-screen flex-col items-center justify-center px-6 pt-20">
        <SkeletonBlock className="h-8 w-48 rounded-full" />
        <SkeletonBlock className="mt-8 h-14 w-full max-w-xl" />
        <SkeletonBlock className="mt-4 h-14 w-full max-w-lg" />
        <SkeletonBlock className="mt-6 h-5 w-full max-w-md" />
        <div className="mt-10 flex gap-4">
          <SkeletonBlock className="h-12 w-36 rounded-xl" />
          <SkeletonBlock className="h-12 w-36 rounded-xl" />
        </div>
      </div>
    </section>
  )
}

function AuthPageSkeleton() {
  return (
    <section className="flex min-h-screen items-center justify-center px-4 pt-20 pb-12">
      <div className="w-full max-w-md space-y-6 rounded-2xl border bg-card p-8 shadow-sm">
        <SkeletonBlock className="mx-auto h-10 w-40" />
        <SkeletonBlock className="h-4 w-full" />
        <div className="space-y-4">
          <SkeletonBlock className="h-10 w-full rounded-lg" />
          <SkeletonBlock className="h-10 w-full rounded-lg" />
          <SkeletonBlock className="h-10 w-full rounded-lg" />
        </div>
        <SkeletonBlock className="h-10 w-full rounded-lg" />
      </div>
    </section>
  )
}

export default function PageSkeleton({ pathname }: PageSkeletonProps) {
  switch (pathname) {
    case '/':
      return <HomePageSkeleton />
    case '/news':
      return <NewsPageSkeleton />
    case '/chat':
      return <ChatPageSkeleton />
    case '/maps':
      return <MapsPageSkeleton />
    case '/profile':
      return <ProfilePageSkeleton />
    case '/login':
    case '/register':
    case '/verify-email':
      return <AuthPageSkeleton />
    default:
      return <DefaultPageSkeleton />
  }
}
