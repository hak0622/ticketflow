import { useEffect, useState } from 'react'
import { getConcerts } from '../features/concert/api'
import HeroCarousel from '../components/concert/HeroCarousel'
import DiscountSection from '../components/concert/DiscountSection'
import OpeningSection from '../components/concert/OpeningSection'
import LoadingSpinner from '../shared/ui/LoadingSpinner'
import PageContainer from '../shared/ui/PageContainer'

/* ─── 메인 ────────────────────────────────────── */
export default function HomePage() {
  const [concerts, setConcerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    setLoading(true)
    setError(false)

    getConcerts()
      .then((res) => setConcerts(res.data))
      .catch(() => {
        setConcerts([])
        setError(true)
      })
      .finally(() => setLoading(false))
  }, [])

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

          {/* ── 할인중 섹션 ── */}
          {!loading && !error && <DiscountSection concerts={concerts} />}

          {/* ── 오픈 예정 섹션 ── */}
          {!loading && !error && <OpeningSection concerts={concerts} />}

          {/* ── 에러 상태 ── */}
          {!loading && error && (
            <div className="flex flex-col items-center justify-center py-32 text-center">
              <span className="text-5xl mb-4">⚠️</span>
              <p className="text-base font-semibold text-gray-600 mb-1">
                공연 정보를 불러오지 못했습니다
              </p>
              <p className="text-sm text-gray-400">네트워크 상태를 확인하고 다시 시도해주세요</p>
            </div>
          )}

          {/* ── 빈 상태 ── */}
          {!loading && !error && concerts.length === 0 && (
            <div className="flex flex-col items-center justify-center py-32 text-center">
              <span className="text-5xl mb-4">🎵</span>
              <p className="text-base font-semibold text-gray-600 mb-1">
                등록된 공연이 없습니다
              </p>
              <p className="text-sm text-gray-400">곧 새로운 공연이 업데이트될 예정입니다</p>
            </div>
          )}
        </PageContainer>
      </main>
    </div>
  )
}
