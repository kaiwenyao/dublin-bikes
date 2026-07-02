import { Suspense, useLayoutEffect, useState } from 'react'
import { Outlet, useLocation, useNavigation } from 'react-router-dom'
import { NavigationProvider, useNavigationIntent } from '@/contexts/NavigationContext'
import Header from './Header'
import Footer from './Footer'
import PageSkeleton from './PageSkeleton'
import { cn } from '@/lib/utils'

function PageTransition() {
  const location = useLocation()
  const navigation = useNavigation()
  const { pendingPath } = useNavigationIntent()
  const [revealedPathname, setRevealedPathname] = useState(location.pathname)

  const skeletonPath = pendingPath ?? location.pathname
  const isRoutePending =
    navigation.state === 'loading' ||
    (pendingPath !== null && pendingPath !== location.pathname)

  const isContentRevealed =
    !isRoutePending && revealedPathname === location.pathname

  useLayoutEffect(() => {
    if (isRoutePending) return

    const frameId = requestAnimationFrame(() => {
      requestAnimationFrame(() => setRevealedPathname(location.pathname))
    })
    return () => cancelAnimationFrame(frameId)
  }, [isRoutePending, location.pathname])

  const showSkeleton = !isContentRevealed

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      {showSkeleton && (
        <div className="absolute inset-0 z-10" aria-busy="true" aria-live="polite">
          <PageSkeleton pathname={skeletonPath} />
        </div>
      )}
      <div
        className={cn(
          'flex min-h-0 flex-1 flex-col',
          showSkeleton && 'pointer-events-none invisible'
        )}
        aria-hidden={showSkeleton}
      >
        <Suspense fallback={<PageSkeleton pathname={skeletonPath} />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  )
}

function LayoutShell() {
  const { pathname } = useLocation()
  const { pendingPath } = useNavigationIntent()
  const activePath = pendingPath ?? pathname
  const isMapsPage = activePath === '/maps'

  return (
    <div
      className={
        isMapsPage
          ? 'relative flex h-screen flex-col overflow-hidden'
          : 'relative flex min-h-screen flex-col'
      }
    >
      <Header />
      <main className={isMapsPage ? 'flex min-h-0 flex-1 flex-col' : 'flex flex-1 flex-col'}>
        <PageTransition />
      </main>
      {!isMapsPage && <Footer />}
    </div>
  )
}

export default function Layout() {
  return (
    <NavigationProvider>
      <LayoutShell />
    </NavigationProvider>
  )
}
