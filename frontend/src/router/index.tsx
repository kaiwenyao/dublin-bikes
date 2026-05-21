import { createBrowserRouter } from 'react-router-dom'
import Layout from '@/components/Layout'
import Activate from '@/pages/Activate/Activate'
import Chat from '@/pages/Chat/Chat'
import Home from '@/pages/Home/Home'
import Login from '@/pages/Login/Login'
import Maps from '@/pages/Maps/Maps'
import News from '@/pages/News/News'
import Profile from '@/pages/Profile/Profile'
import Register from '@/pages/Register/Register'
import VerifyEmail from '@/pages/VerifyEmail/VerifyEmail'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      {
        index: true,
        element: <Home />,
      },
      {
        path: 'register',
        element: <Register />,
      },
      {
        path: 'activate/:token',
        element: <Activate />,
      },
      {
        path: 'verify-email',
        element: <VerifyEmail />,
      },
      {
        path: 'login',
        element: <Login />,
      },
      {
        path: 'news',
        element: <News />,
      },
      {
        path: 'chat',
        element: <Chat />,
      },
      {
        path: 'profile',
        element: <Profile />,
      },
      {
        path: 'maps',
        element: <Maps />,
      },
    ],
  },
])
