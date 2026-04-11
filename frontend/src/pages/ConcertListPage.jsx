import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ConcertCard from '../components/concert/ConcertCard'
import { CATEGORY_BY_QUERY } from '../constants/concertCategories'
import { getConcerts } from '../features/concert/api'
import { getPosterByConcert } from '../constants/posterMap'
import LoadingSpinner from '../shared/ui/LoadingSpinner'
import PageContainer from '../shared/ui/PageContainer'

const MOCK_CONCERTS = [
  {
    id: 1,
    title: '오케스트라 갈라 나이트',
    artist: '서울 심포니',
    eventAt: '2026-06-14T18:00:00',
    price: 132000,
    totalSeats: 500,
    bookedCount: 120,
    status: 'OPEN',
    posterUrl: null,
    genre: '무용',
  },
  {
    id: 2,
    title: '브로드웨이 갈라',
    artist: '뮤지컬 스타즈',
    eventAt: '2026-07-05T19:00:00',
    price: 165000,
    totalSeats: 300,
    bookedCount: 300,
    status: 'SOLD_OUT',
    posterUrl: null,
    genre: '뮤지컬',
  },
  {
    id: 3,
    title: '인디 뮤직 페스티벌',
    artist: 'Various Artists',
    eventAt: '2026-08-20T19:30:00',
    price: 99000,
    totalSeats: 200,
    bookedCount: 50,
    status: 'OPEN',
    posterUrl: null,
    genre: '음악',
  },
]

function filterConcertsByGenre(concerts, genre) {
  if (!genre) {
    return concerts
  }

  return concerts.filter((concert) => concert.genre === genre)
}

export default function ConcertListPage() {
  const [searchParams] = useSearchParams()
  const [concerts, setConcerts] = useState([])
  const [loading, setLoading] = useState(true)

  const queryValue = searchParams.get('genre') || ''
  const category = CATEGORY_BY_QUERY[queryValue] || CATEGORY_BY_QUERY['']
  const apiGenre = category?.genre || undefined

  useEffect(() => {
    setLoading(true)

    getConcerts(apiGenre)
      .then((res) => setConcerts(res.data))
      .catch(() => setConcerts(filterConcertsByGenre(MOCK_CONCERTS, apiGenre)))
      .finally(() => setLoading(false))
  }, [apiGenre])

  const title = useMemo(() => category?.label || '전체', [category])

  return (
    <div className="min-h-screen bg-[#F9F9F9] pb-20 md:pb-0">
      <main>
        <PageContainer className="pt-8 pb-12">
          <section className="mb-10">
            <span className="text-gray-400 font-bold text-[11px] tracking-[0.2em] uppercase block mb-2">
              Genre Collection
            </span>
            <div className="flex items-end justify-between gap-4">
              <div>
                <h1 className="text-3xl md:text-4xl font-black font-jakarta text-gray-900 leading-none mb-2">
                  {title}
                </h1>
                <p className="text-sm text-gray-500">
                  {apiGenre ? `${apiGenre} 장르 공연을 모아봤어요` : '전체 공연을 한 번에 둘러보세요'}
                </p>
              </div>
              {!loading && (
                <span className="text-sm text-gray-400">{concerts.length}개</span>
              )}
            </div>
          </section>

          {loading ? (
            <div className="flex justify-center py-32">
              <LoadingSpinner size="lg" />
            </div>
          ) : concerts.length > 0 ? (
            <section>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-6 md:gap-8">
                {concerts.map((concert) => (
                  <ConcertCard
                    key={concert.id}
                    concert={concert}
                    fallbackImage={getPosterByConcert(concert)}
                  />
                ))}
              </div>
            </section>
          ) : (
            <div className="flex flex-col items-center justify-center py-32 text-center">
              <span className="text-5xl mb-4">🎭</span>
              <p className="text-base font-semibold text-gray-600 mb-1">
                해당 카테고리의 공연이 없습니다
              </p>
              <p className="text-sm text-gray-400">다른 카테고리를 선택해보세요</p>
            </div>
          )}
        </PageContainer>
      </main>
    </div>
  )
}
