import { Link, useNavigate } from 'react-router-dom'
import useAuthStore from '../../store/authStore'
import { serverLogout } from '../../api/auth'

export default function Header() {
  const { token, user, logout } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await serverLogout()  // 서버 세션 제거 (에러 무시)
    logout()              // Zustand + localStorage 제거
    navigate('/', { replace: true })
  }

  return (
    <header className="sticky top-0 z-50 bg-white border-b border-gray-100 shadow-sm">
      <div className="max-w-screen-xl mx-auto px-4 h-14 flex items-center justify-between">
        {/* 로고 */}
        <Link to="/" className="text-xl font-black tracking-tight text-primary-600">
          TICKETLY
        </Link>

        {/* 네비게이션 (데스크탑) */}
        <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-gray-600">
          <Link to="/" className="hover:text-primary-600 transition-colors">
            홈
          </Link>
          {token && (
            <Link to="/my-bookings" className="hover:text-primary-600 transition-colors">
              내 예매
            </Link>
          )}
        </nav>

        {/* 인증 영역 */}
        <div className="flex items-center gap-2">
          {token ? (
            <div className="flex items-center gap-3">
              {/* 사용자 이메일 (데스크탑만) */}
              {user?.sub && (
                <span className="hidden md:block text-sm text-gray-400 truncate max-w-[140px]">
                  {user.sub}
                </span>
              )}
              <button
                onClick={handleLogout}
                className="text-sm text-gray-500 hover:text-red-500 transition-colors px-3 py-1.5 rounded-lg border border-gray-200 hover:border-red-200"
              >
                로그아웃
              </button>
            </div>
          ) : (
            <>
              <Link
                to="/login"
                className="text-sm text-gray-600 hover:text-primary-600 transition-colors px-3 py-1.5"
              >
                로그인
              </Link>
              <Link
                to="/register"
                className="text-sm bg-primary-500 hover:bg-primary-600 text-white px-4 py-1.5 rounded-lg font-semibold transition-colors"
              >
                회원가입
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  )
}
