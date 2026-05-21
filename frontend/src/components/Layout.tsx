import { Outlet, useLocation } from 'react-router-dom'
import Header from './Header'
import Footer from './Footer'

export default function Layout() {
  const { pathname } = useLocation()
  const isMapsPage : boolean = pathname === '/maps'

  return (
    <div
      className={
        isMapsPage
          ? 'relative flex h-screen flex-col overflow-hidden'
          : 'relative flex min-h-screen flex-col'
      }
    >
      <Header />
      <main className={isMapsPage ? 'flex-1 min-h-0' : 'flex-1'}>
        <Outlet />
      </main>
      {!isMapsPage && <Footer />}
    </div>
  )
}
