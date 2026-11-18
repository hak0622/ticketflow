import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { exchangeLoginSession } from '../features/auth/api'
import { applyOAuthToken } from '../features/auth/auth-actions'

function takeReturnPath() {
  const path = sessionStorage.getItem('loginRedirect')
  sessionStorage.removeItem('loginRedirect')
  if (!path?.startsWith('/') || path.startsWith('//')) return '/'

  try {
    const url = new URL(path, window.location.origin)
    if (url.origin !== window.location.origin || url.pathname.startsWith('//')
        || /^\/auth\/callback\/?$/i.test(decodeURI(url.pathname))) return '/'
    return url.pathname + url.search + url.hash
  } catch {
    return '/'
  }
}

export default function AuthCallbackPage() {
  const navigate = useNavigate()
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    async function completeLogin() {
      try {
        const { accessToken } = await exchangeLoginSession()
        if (!active) return
        if (!accessToken || !applyOAuthToken(accessToken)) {
          setFailed(true)
          return
        }
        navigate(takeReturnPath(), { replace: true })
      } catch {
        if (active) setFailed(true)
      }
    }

    completeLogin()
    return () => { active = false }
  }, [navigate])

  return (
    <main className="mx-auto max-w-md px-4 py-16 text-center">
      {failed ? (
        <>
          <p role="alert">로그인을 완료하지 못했습니다. 다시 로그인해 주세요.</p>
          <Link to="/login?error=oauth" replace className="mt-4 inline-block font-medium text-primary-600">
            로그인 다시 시작
          </Link>
        </>
      ) : (
        <p role="status">로그인을 완료하고 있습니다.</p>
      )}
    </main>
  )
}
