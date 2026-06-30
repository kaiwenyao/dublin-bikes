import { useRef, type PointerEvent } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { getAccessToken, clearAuthTokens, userLogoutAPI } from '@/api'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import { isSameTabPrimaryActivation } from '@/lib/navigation-intent'
import { useNavigationIntent } from '@/contexts/NavigationContext'

const NAV_PATHS = ['/news', '/chat', '/maps', '/profile'] as const

function isNavPath(path: string): path is (typeof NAV_PATHS)[number] {
  return NAV_PATHS.includes(path as (typeof NAV_PATHS)[number])
}

export default function Header() {
  const navigate = useNavigate()
  const location = useLocation()
  const { pendingPath, setPendingPath } = useNavigationIntent()
  const isLoggedIn = !!getAccessToken()
  const activePath = pendingPath ?? location.pathname
  const navClickCommittedRef = useRef(false)

  const handleLogout = async () => {
    let logoutByServerSucceeded = false
    try {
      await userLogoutAPI()
      logoutByServerSucceeded = true
    } catch {
      // When network error or token expired, still clear local and redirect
    } finally {
      clearAuthTokens()
      if (logoutByServerSucceeded) {
        toast.success('Signed out successfully.')
      } else {
        toast.success('Signed out.')
      }
      navigate('/login', { replace: true })
    }
  }

  const isActive = (path: string) => activePath === path

  const createNavIntentHandlers = (path: string) => {
    const clearPendingForPath = () => {
      setPendingPath((current) => (current === path ? null : current))
    }

    const handlePointerDown = (event: PointerEvent<HTMLAnchorElement>) => {
      if (!isNavPath(path)) return
      if (!isSameTabPrimaryActivation(event)) return
      if (path === location.pathname) return

      navClickCommittedRef.current = false
      setPendingPath(path)
      event.currentTarget.setPointerCapture(event.pointerId)
    }

    const handlePointerUp = (event: PointerEvent<HTMLAnchorElement>) => {
      if (event.button !== 0) return

      const target = event.currentTarget
      if (target.hasPointerCapture(event.pointerId)) {
        target.releasePointerCapture(event.pointerId)
      }

      const intendedPath = path
      window.setTimeout(() => {
        if (!navClickCommittedRef.current) {
          setPendingPath((current) => (current === intendedPath ? null : current))
        }
        navClickCommittedRef.current = false
      }, 0)
    }

    const handlePointerCancel = (event: PointerEvent<HTMLAnchorElement>) => {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId)
      }
      clearPendingForPath()
      navClickCommittedRef.current = false
    }

    const handleClick = (event: React.MouseEvent<HTMLAnchorElement>) => {
      if (!isSameTabPrimaryActivation(event)) {
        clearPendingForPath()
        return
      }
      navClickCommittedRef.current = true
    }

    return {
      onPointerDown: handlePointerDown,
      onPointerUp: handlePointerUp,
      onPointerCancel: handlePointerCancel,
      onClick: handleClick,
    }
  }

  const navLinkClasses = (path: string) => {
    const active = isActive(path)
    return cn(
      'relative px-4 py-2 text-sm font-medium rounded-lg cursor-pointer',
      active
        ? 'text-[#00A8E8] bg-[#00A8E8]/10'
        : 'text-gray-700 hover:text-[#00A8E8] hover:bg-[#00A8E8]/10 transition-colors duration-200'
    )
  }

  return (
    <header className="fixed top-4 left-4 right-4 z-50 flex justify-center">
      <nav className="mx-auto flex items-center bg-white/90 backdrop-blur-sm shadow-lg rounded-full px-4 py-3 w-max max-w-full sm:px-6 md:px-8 md:max-w-6xl lg:min-w-4xl lg:max-w-480 xl:min-w-6xl xl:max-w-720">
        <div className="flex items-center justify-between w-full">
          {/* Left: Logo + News + Maps */}
          <div className="flex items-center gap-4">
            <Link
              to="/"
              className="flex items-center gap-2 text-xl font-bold text-gray-900 transition-opacity hover:opacity-80"
            >
              <img
                src="/favicon.svg"
                alt=""
                aria-hidden
                className="h-8 w-8"
              />
              <span className="hidden sm:inline">Dublin Bikes</span>
            </Link>
            <div className="flex items-center gap-1">
              <Link
                to="/news"
                className={navLinkClasses('/news')}
                {...createNavIntentHandlers('/news')}
              >
                News
              </Link>
              <Link
                to="/chat"
                className={navLinkClasses('/chat')}
                {...createNavIntentHandlers('/chat')}
              >
                Chat
              </Link>
              <Link
                to="/maps"
                className={navLinkClasses('/maps')}
                {...createNavIntentHandlers('/maps')}
              >
                Maps
              </Link>
              <a
                href="https://github.com/kaiwenyao/dublin-bikes"
                target="_blank"
                rel="noopener noreferrer"
                className={cn(
                  'relative px-4 py-2 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer',
                  'text-gray-700 hover:text-[#00A8E8] hover:bg-[#00A8E8]/10 flex items-center gap-2'
                )}
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z" />
                </svg>
                <span className="hidden sm:inline">GitHub</span>
              </a>
            </div>
          </div>

          {/* Right: Sign in / Get started or Profile / Log out */}
          <div className="flex items-center gap-2">
            {isLoggedIn ? (
              <>
                <Link
                  to="/profile"
                  className={cn(
                    navLinkClasses('/profile'),
                    'flex items-center gap-2'
                  )}
                  {...createNavIntentHandlers('/profile')}
                >
                  <svg
                    className="h-4 w-4"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                  <span className="hidden sm:inline">Profile</span>
                </Link>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="ml-2 rounded-lg border border-gray-300 bg-white/60 px-4 py-2 text-sm font-medium text-gray-700 transition-colors duration-200 hover:bg-gray-900 hover:text-white hover:border-gray-900 cursor-pointer"
                >
                  Log out
                </button>
              </>
            ) : (
              <>
                <Link
                  to="/login"
                  className="relative px-4 py-2 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer text-gray-700 hover:text-[#00A8E8] hover:bg-[#00A8E8]/10"
                >
                  Sign in
                </Link>
                <Link
                  to="/register"
                  className="ml-2 rounded-lg px-4 py-2 text-sm font-medium shadow-lg transition-all duration-200 hover:opacity-90 cursor-pointer"
                  style={{ backgroundColor: '#003459', color: '#ffffff' }}
                >
                  Get Started
                </Link>
              </>
            )}
          </div>
        </div>
      </nav>
    </header>
  )
}
