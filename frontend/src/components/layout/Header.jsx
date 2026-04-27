import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { HiOutlineTicket } from 'react-icons/hi2'
import { HiOutlineArrowRightOnRectangle, HiOutlineArrowLeftOnRectangle, HiOutlineCog6Tooth } from 'react-icons/hi2'
import useAuthStore from '../../features/auth/store'
import { performLogout } from '../../features/auth/auth-actions'
import { CONCERT_CATEGORIES } from '../../constants/concertCategories'

export default function Header() {
  const { token, user } = useAuthStore()
  const navigate = useNavigate()
  const location = useLocation()
  const currentKeyword = new URLSearchParams(location.search).get('keyword') || ''
  const [searchKeyword, setSearchKeyword] = useState(currentKeyword)

  useEffect(() => {
    setSearchKeyword(currentKeyword)
  }, [currentKeyword])

  const handleLogout = async () => {
    await performLogout()
    navigate('/', { replace: true })
  }

  const moveToSearchResult = () => {
    const params = new URLSearchParams(location.search)
    const normalized = searchKeyword.trim()

    if (normalized) {
      params.set('keyword', normalized)
    } else {
      params.delete('keyword')
    }

    navigate({
      pathname: '/concerts',
      search: params.toString() ? `?${params.toString()}` : '',
    })
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    moveToSearchResult()
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
            TICKETFLOW
          </Link>

          {/* 검색 바 (데스크탑) */}
          <form
            onSubmit={handleSubmit}
            className="hidden md:flex flex-1 max-w-xl items-center gap-3 bg-gray-100 rounded-full px-4 py-2"
          >
            <button
              type="submit"
              className="shrink-0 text-gray-400 hover:text-gray-600 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
            </button>
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="공연, 아티스트 검색..."
              className="bg-transparent border-none outline-none w-full text-sm text-gray-700 placeholder-gray-400"
            />
          </form>

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

            {/* 관리자 (ADMIN만 표시) */}
            {user?.role === 'ADMIN' && (
              <Link
                to="/admin/concerts"
                className="flex flex-col items-center gap-0.5 px-3 py-1.5 rounded-lg text-gray-500 hover:text-gray-900 hover:bg-gray-50 transition-colors"
              >
                <HiOutlineCog6Tooth className="w-5 h-5" />
                <span className="text-[10px] font-medium hidden md:block">관리자</span>
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
  const navigate = useNavigate()
  const location = useLocation()
  const currentGenre = new URLSearchParams(location.search).get('genre') || ''
  const isConcertListPage = location.pathname === '/concerts'

  const handleCategoryClick = (category) => {
    const params = new URLSearchParams(location.search)

    if (category.queryValue) {
      params.set('genre', category.queryValue)
    } else {
      params.delete('genre')
    }

    navigate({
      pathname: '/concerts',
      search: params.toString() ? `?${params.toString()}` : '',
    })
  }

  return (
    <div className="border-t border-gray-100">
      <div className="max-w-screen-xl mx-auto px-4 md:px-6 lg:px-8">
        <nav className="flex items-center justify-center gap-1 md:gap-2 overflow-x-auto scrollbar-none py-1">
          {CONCERT_CATEGORIES.map((category) => (
            <CategoryTab
              key={category.label}
              label={category.label}
              active={isConcertListPage && currentGenre === category.queryValue}
              onClick={() => handleCategoryClick(category)}
            />
          ))}
        </nav>
      </div>
    </div>
  )
}

function CategoryTab({ label, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex-shrink-0 px-4 md:px-5 py-2.5 md:py-3 text-sm md:text-[15px] font-semibold transition-colors whitespace-nowrap rounded-md ${
        active
          ? 'text-primary-600 bg-primary-50'
          : 'text-gray-600 hover:text-primary-600 hover:bg-primary-50'
      }`}
    >
      {label}
    </button>
  )
}
