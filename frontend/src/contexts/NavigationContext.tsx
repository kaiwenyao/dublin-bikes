import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from 'react'
import { useLocation, useNavigation } from 'react-router-dom'

interface NavigationContextValue {
  pendingPath: string | null
  setPendingPath: Dispatch<SetStateAction<string | null>>
}

const NavigationContext = createContext<NavigationContextValue | null>(null)

const STALE_PENDING_PATH_MS = 250

export function NavigationProvider({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigation = useNavigation()
  const [pendingPath, setPendingPath] = useState<string | null>(null)

  useEffect(() => {
    if (pendingPath !== null && pendingPath === location.pathname) {
      setPendingPath(null)
    }
  }, [location.pathname, pendingPath])

  useEffect(() => {
    if (pendingPath === null || pendingPath === location.pathname) return
    if (navigation.state === 'loading') return

    const timeoutId = window.setTimeout(() => {
      setPendingPath((current) =>
        current !== null && current !== location.pathname ? null : current
      )
    }, STALE_PENDING_PATH_MS)

    return () => clearTimeout(timeoutId)
  }, [pendingPath, location.pathname, navigation.state])

  const value = useMemo(
    () => ({ pendingPath, setPendingPath }),
    [pendingPath]
  )

  return (
    <NavigationContext.Provider value={value}>
      {children}
    </NavigationContext.Provider>
  )
}

export function useNavigationIntent() {
  const context = useContext(NavigationContext)
  if (!context) {
    throw new Error('useNavigationIntent must be used within NavigationProvider')
  }
  return context
}
