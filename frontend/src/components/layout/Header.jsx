import { Link, useNavigate } from 'react-router-dom'
import { HiOutlineTicket } from 'react-icons/hi2'
import { HiOutlineUser, HiOutlineArrowRightOnRectangle, HiOutlineArrowLeftOnRectangle } from 'react-icons/hi2'
import useAuthStore from '../../store/authStore'
import { serverLogout } from '../../api/auth'

export default function Header() {
  const { token, logout } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await serverLogout()
    logout()
    navigate('/', { replace: true })
  }

  return (
    <header className="sticky top-0 z-50 bg-white w-full border-b border-gray-100 shadow-sm">
      <div className="max-w-screen-xl mx-auto px-4 md:px-6 lg:px-8">

        {/* ── 상단 행: 로고 + 검색 + 액션 ── */}
        <div className="flex items-center justify-between gap-4 md:gap-8 h-14">

          {/* 로고 */}
          <Link
            to="/"
            className="text-xl font-black tracking-tighter text-primary-600 font-jakarta shrink-0"
          >
            TICKETLY
          </Link>

          {/* 검색 바 (데스크탑) */}
          <div className="hidden md:flex flex-1 max-w-xl items-center gap-3 bg-gray-100 rounded-full px-4 py-2">
            <svg className="w-4 h-4 text-gray-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="공연, 아티스트 검색..."
              className="bg-transparent border-none outline-none w-full text-sm text-gray-700 placeholder-gray-400"
              readOnly
            />
          </div>

          {/* 우측 액션 영역 */}
          <div className="flex items-center gap-1 md:gap-2 shrink-0">

            {/* 로그인 / 로그아웃 */}
            {token ? (
              <button
                onClick={handleLogout}
                className="flex flex-col items-center gap-0.5 px-3 py-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-50 transition-colors"
              >
                <HiOutlineArrowRightOnRectangle className="w-5 h-5" />
                <span className="text-[10px] font-medium hidden md:block">로그아웃</span>
              </button>
            ) : (
              <Link
                to="/login"
                className="flex flex-col items-center gap-0.5 px-3 py-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-50 transition-colors"
              >
                <HiOutlineArrowLeftOnRectangle className="w-5 h-5" />
                <span className="text-[10px] font-medium hidden md:block">로그인</span>
              </Link>
            )}

            {/* 내 예약 (항상 표시) */}
            <Link
              to={token ? '/my-bookings' : '/login'}
              className="flex flex-col items-center gap-0.5 px-3 py-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-50 transition-colors"
            >
              <HiOutlineTicket className="w-5 h-5" />
              <span className="text-[10px] font-medium hidden md:block">내 예약</span>
            </Link>

            {/* 회원가입 (비로그인 데스크탑) */}
            {!token && (
              <Link
                to="/register"
                className="hidden md:flex items-center gap-1.5 text-sm font-semibold bg-primary-500 text-white px-4 py-1.5 rounded-full hover:bg-primary-600 transition-colors ml-1"
              >
                <HiOutlineUser className="w-4 h-4" />
                회원가입
              </Link>
            )}
          </div>
        </div>
      </div>

      {/* ── 카테고리 탭 행 ── */}
      <CategoryNav />
    </header>
  )
}

/* ── 카테고리 탭 (별도 컴포넌트, HomePage의 필터 칩과 별개) ── */
function CategoryNav() {
  return (
    <div className="border-t border-gray-100">
      <div className="max-w-screen-xl mx-auto px-4 md:px-6 lg:px-8">
        <nav className="flex items-center justify-center gap-1 md:gap-2 overflow-x-auto scrollbar-none py-1">
          {CATEGORIES.map(({ label, href }) => (
            <CategoryTab key={label} label={label} href={href} />
          ))}
        </nav>
      </div>
    </div>
  )
}

const CATEGORIES = [
  { label: '뮤지컬',    href: '/' },
  { label: '콘서트',    href: '/' },
  { label: '스포츠',    href: '/' },
  { label: '전시/행사', href: '/' },
  { label: '클래식/무용', href: '/' },
  { label: '아동/가족', href: '/' },
  { label: '연극',      href: '/' },
  { label: '레저/캠핑', href: '/' },
]

function CategoryTab({ label, href }) {
  return (
    <Link
      to={href}
      className="flex-shrink-0 px-4 md:px-5 py-2.5 md:py-3 text-sm md:text-[15px] font-semibold text-gray-600 hover:text-primary-600 transition-colors whitespace-nowrap rounded-md hover:bg-primary-50"
    >
      {label}
    </Link>
  )
}
