import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { restoreBrowserSession } from './bootstrap'
import useAuthStore from './store'
import { paymentLookupPath } from '../payment/paymentIntent'

export default function AuthGate({ children }) {
  const token = useAuthStore(state => state.token)
  const { pathname, search, hash } = useLocation()
  const [status, setStatus] = useState(token ? 'ready' : 'checking')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    if (useAuthStore.getState().token) return undefined
    restoreBrowserSession().then(result => {
      if (active) setStatus(result === 'unavailable' ? 'unavailable' : 'ready')
    })
    return () => { active = false }
  }, [attempt])

  if (token || status === 'ready' || (status === 'unavailable' && pathname === '/login')) return children
  const from = pathname === '/payment/success'
    ? paymentLookupPath(new URLSearchParams(search)) : pathname + search + hash
  return (
    <main className="mx-auto max-w-md px-4 py-16 text-center">
      {status === 'checking' ? <p role="status">로그인 상태를 확인하고 있습니다.</p> : (
        <>
          <p role="alert">로그인 상태를 확인하지 못했습니다.</p>
          <button type="button" className="mt-4 font-medium text-primary-600" onClick={() => {
            setStatus('checking')
            setAttempt(previous => previous + 1)
          }}>다시 확인</button>
          <Link to="/login" state={{ from }} className="ml-4 font-medium text-primary-600">Google 로그인</Link>
        </>
      )}
    </main>
  )
}
