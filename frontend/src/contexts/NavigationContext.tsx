import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'

interface NavigationContextValue {
  pendingPath: string | null
  setPendingPath: (path: string | null) => void
}

const NavigationContext = createContext<NavigationContextValue | null>(null)

export function NavigationProvider({ children }: { children: ReactNode }) {
  const location = useLocation()
  const [pendingPath, setPendingPath] = useState<string | null>(null)

  useEffect(() => {
    if (pendingPath !== null && pendingPath === location.pathname) {
      setPendingPath(null)
    }
  }, [location.pathname, pendingPath])

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
