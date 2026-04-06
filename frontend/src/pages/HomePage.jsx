import { useEffect, useMemo, useState } from 'react'
import { getConcerts } from '../api/concert'
import HeroCarousel from '../components/concert/HeroCarousel'
import ConcertCard from '../components/concert/ConcertCard'
import LoadingSpinner from '../components/common/LoadingSpinner'
import PageContainer from '../components/common/PageContainer'
import { getPosterByConcert } from '../constants/posterMap'

/* ─── 목 데이터 (API 실패 시 폴백) ───────────── */
const MOCK_CONCERTS = [
  {
    id: 1,
    title: 'AESPA WORLD TOUR 2026',
    artist: '에스파 (aespa)',
    eventAt: '2026-06-14T18:00:00',
    price: 132000,
    totalSeats: 500,
    bookedCount: 120,
    status: 'OPEN',
    posterUrl: null,
    genre: 'K-POP',
  },
  {
    id: 2,
    title: 'BTS Yet To Come in Seoul',
    artist: 'BTS',
    eventAt: '2026-07-05T19:00:00',
    price: 165000,
    totalSeats: 300,
    bookedCount: 300,
    status: 'SOLD_OUT',
    posterUrl: null,
    genre: 'K-POP',
  },
  {
    id: 3,
    title: 'IU 콘서트 : 클레이',
    artist: 'IU (아이유)',
    eventAt: '2026-08-20T19:30:00',
    price: 99000,
    totalSeats: 200,
    bookedCount: 50,
    status: 'OPEN',
    posterUrl: null,
    genre: '발라드',
  },
]

/* ─── 필터 칩 ─────────────────────────────────── */
function Chip({ label, active, onClick }) {
  return (
    <button
      onClick={onClick}
      className={`flex-shrink-0 px-5 py-2 rounded-full text-sm font-semibold transition-colors border ${
        active
          ? 'bg-primary-500 text-white border-primary-500 shadow-md shadow-primary-100'
          : 'bg-white text-gray-500 border-gray-200 hover:border-primary-300 hover:text-primary-600'
      }`}
    >
      {label}
    </button>
  )
}

/* ─── 메인 ────────────────────────────────────── */
export default function HomePage() {
  const [concerts, setConcerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatus] = useState('all')
  const [genreFilter, setGenre] = useState('all')

  useEffect(() => {
    getConcerts()
      .then((res) => setConcerts(res.data))
      .catch(() => setConcerts(MOCK_CONCERTS))
      .finally(() => setLoading(false))
  }, [])

  /* 장르 목록 (동적 생성) */
  const genres = useMemo(
    () => [...new Set(concerts.map((c) => c.genre).filter(Boolean))],
    [concerts],
  )

  /* 필터 적용 */
  const filtered = useMemo(() => {
    return concerts.filter((c) => {
      const byStatus =
        statusFilter === 'all' ||
        (statusFilter === 'OPEN' && c.status === 'OPEN') ||
        (statusFilter === 'sold' && c.status !== 'OPEN')
      const byGenre = genreFilter === 'all' || c.genre === genreFilter
      return byStatus && byGenre
    })
  }, [concerts, statusFilter, genreFilter])

  /* 트렌딩: 필터 적용, 최대 5개 */
  const trending = filtered.slice(0, 5)
  /* 트렌딩 이후 추가 목록 */
  const more = filtered.slice(5)

  return (
    <div className="min-h-screen bg-[#F9F9F9] pb-20 md:pb-0">
      <main>
        <PageContainer className="pt-6 pb-10">
          {/* ── 히어로 캐러셀 ── */}
          {loading ? (
            <div className="flex justify-center py-32">
              <LoadingSpinner size="lg" />
            </div>
          ) : (
            <HeroCarousel concerts={concerts} />
          )}

          {/* ── 필터 칩 행 ── */}
          {!loading && (
            <section className="flex flex-wrap gap-2 mb-10">
              <Chip
                label="전체"
                active={statusFilter === 'all'}
                onClick={() => setStatus('all')}
              />
              <Chip
                label="예매가능"
                active={statusFilter === 'OPEN'}
                onClick={() => setStatus('OPEN')}
              />
              <Chip
                label="마감"
                active={statusFilter === 'sold'}
                onClick={() => setStatus('sold')}
              />
              {genres.map((g) => (
                <Chip
                  key={g}
                  label={g}
                  active={genreFilter === g}
                  onClick={() => setGenre(genreFilter === g ? 'all' : g)}
                />
              ))}
            </section>
          )}

          {/* ── Trending Section ── */}
          {!loading && trending.length > 0 && (
            <section className="mb-16">
              <div className="flex items-end justify-between mb-8">
                <div>
                  <span className="text-gray-400 font-bold text-[11px] tracking-[0.2em] uppercase block mb-2">
                    Weekly Highlights
                  </span>
                  <h2 className="text-3xl font-black font-jakarta text-gray-900 leading-none">
                    Trending Now
                  </h2>
                </div>
                <button className="text-primary-600 font-bold text-sm flex items-center gap-1 hover:text-primary-700 transition-colors">
                  더보기 <span className="text-base">→</span>
                </button>
              </div>

              <div className="grid grid-cols-2 md:grid-cols-5 gap-6 md:gap-8">
                {trending.map((concert, i) => (
                  <ConcertCard
                    key={concert.id}
                    concert={concert}
                    rank={i + 1}
                    fallbackImage={getPosterByConcert(concert)}
                  />
                ))}
              </div>
            </section>
          )}

          {/* ── 추가 목록 (trending 이후) ── */}
          {!loading && more.length > 0 && (
            <section>
              <div className="flex items-end justify-between mb-8">
                <h2 className="text-2xl font-black font-jakarta text-gray-900">
                  더 많은 공연
                </h2>
                <span className="text-sm text-gray-400">{more.length}개</span>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-6 md:gap-8">
                {more.map((concert) => (
                  <ConcertCard
                    key={concert.id}
                    concert={concert}
                    fallbackImage={getPosterByConcert(concert)}
                  />
                ))}
              </div>
            </section>
          )}

          {/* ── 빈 상태 ── */}
          {!loading && filtered.length === 0 && (
            <div className="flex flex-col items-center justify-center py-32 text-center">
              <span className="text-5xl mb-4">🎵</span>
              <p className="text-base font-semibold text-gray-600 mb-1">
                조건에 맞는 공연이 없습니다
              </p>
              <p className="text-sm text-gray-400">다른 필터를 선택해보세요</p>
            </div>
          )}
        </PageContainer>
      </main>
    </div>
  )
}