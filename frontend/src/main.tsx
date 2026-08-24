import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { router } from '@/router'
import { Toaster } from '@/components/ui/sonner'
import { installChunkLoadRecovery } from '@/lib/chunk-load-recovery'

installChunkLoadRecovery()

createRoot(document.getElementById('root')!).render(
  <>
    <RouterProvider router={router} />
    <Toaster />
  </>
)
