import { useEffect, useState } from 'react'
import { getConcerts } from '../api/concert'
import ConcertCard from '../components/concert/ConcertCard'
import LoadingSpinner from '../components/common/LoadingSpinner'

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

export default function HomePage() {
  const [concerts, setConcerts] = useState([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState(null)

  useEffect(() => {
    getConcerts()
      .then((res) => setConcerts(res.data))
      .catch(() => {
        // 백엔드 미연결 시 목업 데이터로 폴백
        setConcerts(MOCK_CONCERTS)
        setError(null)
      })
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="min-h-screen bg-gray-50 pb-20 md:pb-0">
      {/* 히어로 배너 */}
      <section className="bg-gradient-to-br from-primary-50 via-white to-primary-100 border-b border-primary-100">
        <div className="max-w-screen-xl mx-auto px-4 py-10 md:py-16">
          <p className="text-xs font-semibold text-primary-600 uppercase tracking-widest mb-2">
            Concert Ticketing
          </p>
          <h1 className="text-2xl md:text-4xl font-black text-gray-900 leading-tight mb-3">
            지금 인기 공연을<br className="md:hidden" /> 예매하세요
          </h1>
          <p className="text-sm md:text-base text-gray-500">
            공정한 대기열로 누구나 동등하게 예매할 수 있습니다.
          </p>
        </div>
      </section>

      {/* 콘서트 목록 */}
      <main className="max-w-screen-xl mx-auto px-4 py-6">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-base font-bold text-gray-900">
            전체 공연
            {!loading && (
              <span className="ml-2 text-sm font-normal text-gray-400">
                {concerts.length}건
              </span>
            )}
          </h2>
        </div>

        {loading && (
          <div className="flex justify-center py-20">
            <LoadingSpinner size="lg" />
          </div>
        )}

        {!loading && concerts.length === 0 && (
          <div className="text-center py-20 text-gray-400">
            <p className="text-4xl mb-3">🎵</p>
            <p className="text-sm">등록된 공연이 없습니다.</p>
          </div>
        )}

        {!loading && concerts.length > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3 md:gap-5">
            {concerts.map((concert) => (
              <ConcertCard key={concert.id} concert={concert} />
            ))}
          </div>
        )}
      </main>
    </div>
  )
}
