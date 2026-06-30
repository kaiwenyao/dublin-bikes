import { lazy } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import Layout from '@/components/Layout'

const Home = lazy(() => import('@/pages/Home/Home'))
const Activate = lazy(() => import('@/pages/Activate/Activate'))
const Chat = lazy(() => import('@/pages/Chat/Chat'))
const Login = lazy(() => import('@/pages/Login/Login'))
const Maps = lazy(() => import('@/pages/Maps/Maps'))
const News = lazy(() => import('@/pages/News/News'))
const Profile = lazy(() => import('@/pages/Profile/Profile'))
const Register = lazy(() => import('@/pages/Register/Register'))
const VerifyEmail = lazy(() => import('@/pages/VerifyEmail/VerifyEmail'))

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
