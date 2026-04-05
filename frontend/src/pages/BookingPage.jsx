import { useState, useEffect } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getConcert } from '../api/concert'
import { createBooking } from '../api/booking'
import useAuthStore from '../store/authStore'
import LoadingSpinner from '../components/common/LoadingSpinner'

/* ── 포맷 유틸 ─────────────────────────────────── */
function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleString('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric',
    weekday: 'long', hour: '2-digit', minute: '2-digit',
  })
}

function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

/* ── 정보 행 ────────────────────────────────────── */
function InfoRow({ label, value, highlight }) {
  if (!value) return null
  return (
    <div className="flex justify-between items-start py-2.5 border-b border-gray-100 last:border-0">
      <span className="text-sm text-gray-400 shrink-0">{label}</span>
      <span className={`text-sm font-semibold text-right ml-4 ${highlight ? 'text-primary-600 text-base' : 'text-gray-800'}`}>
        {value}
      </span>
    </div>
  )
}

/* ── 메인 컴포넌트 ──────────────────────────────── */
export default function BookingPage() {
  const { id: concertId } = useParams()
  const navigate           = useNavigate()
  const { token }          = useAuthStore()

  const [concert, setConcert]     = useState(null)
  const [loading, setLoading]     = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError]         = useState(null)

  /* 비로그인 */
  useEffect(() => {
    if (!token) {
      navigate('/login', { state: { from: `/concerts/${concertId}/booking` } })
    }
  }, [token, navigate, concertId])

  /* 공연 정보 조회 */
  useEffect(() => {
    if (!token) return
    getConcert(concertId)
      .then((res) => setConcert(res.data))
      .catch(() => setError('공연 정보를 불러올 수 없습니다.'))
      .finally(() => setLoading(false))
  }, [concertId, token])

  const handleBook = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const res = await createBooking(concertId)
      navigate(`/concerts/${concertId}/payment`, {
        state: { concert, booking: res.data },
        replace: true,
      })
    } catch (err) {
      const status = err.response?.status
      if (status === 409) {
        // 이미 예매됨 → 결제 페이지로 직행
        navigate(`/concerts/${concertId}/payment`, {
          state: { concert },
          replace: true,
        })
      } else {
        const msg = err.response?.data?.message || '예매 처리 중 오류가 발생했습니다.'
        setError(msg)
        setSubmitting(false)
      }
    }
  }

  /* ── 로딩 ── */
  if (loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  /* ── 데이터 없음 ── */
  if (!concert && !loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        <p className="text-base font-semibold text-gray-700">공연 정보를 불러올 수 없습니다.</p>
        <Link
          to={`/concerts/${concertId}`}
          className="px-6 py-3 bg-primary-500 text-white font-semibold rounded-xl"
        >
          공연 페이지로 돌아가기
        </Link>
      </div>
    )
  }

  const remaining = (concert.totalSeats ?? 0) - (concert.bookedCount ?? 0)

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 flex flex-col items-center px-4 pt-6 pb-32 md:justify-center md:pb-0">
      <div className="w-full max-w-sm">

        {/* 뒤로 가기 */}
        <Link
          to={`/concerts/${concertId}`}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-6"
        >
          ← 공연 페이지
        </Link>

        {/* 제목 */}
        <h1 className="text-xl font-black text-gray-900 mb-5">예매 확인</h1>

        {/* 공연 정보 카드 */}
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5 mb-4">
          <div className="space-y-0.5">
            <InfoRow label="공연명"   value={concert.title} />
            <InfoRow label="아티스트" value={concert.artist} />
            <InfoRow label="일시"     value={formatDateTime(concert.eventAt)} />
            {concert.genre && <InfoRow label="장르" value={concert.genre} />}
          </div>

          <div className="mt-3 pt-3 border-t border-gray-100 space-y-0.5">
            <InfoRow label="티켓 가격" value={formatPrice(concert.price)} highlight />
            <InfoRow label="잔여석"    value={`${remaining}석`} />
          </div>
        </div>

        {/* 안내 */}
        <div className="bg-primary-50 rounded-xl px-4 py-3 mb-5">
          <p className="text-xs text-primary-700 leading-relaxed">
            입장권 1매 기준으로 예매됩니다.<br />
            예매 확정 후 <strong>30분 이내</strong>에 결제하지 않으면 자동 취소됩니다.
          </p>
        </div>

        {/* 오류 메시지 */}
        {error && (
          <div className="bg-red-50 rounded-xl px-4 py-3 mb-4">
            <p className="text-sm text-red-600 text-center">{error}</p>
          </div>
        )}

        {/* CTA — 모바일 하단 고정, 데스크탑 인라인 */}
        <div className="fixed bottom-16 left-0 right-0 px-4 md:static md:bottom-auto md:px-0">
          <button
            onClick={handleBook}
            disabled={submitting || concert.status === 'SOLD_OUT' || concert.status === 'CLOSED'}
            className="w-full py-4 bg-primary-500 hover:bg-primary-600 disabled:opacity-60 disabled:cursor-not-allowed text-white font-bold text-lg rounded-2xl transition-colors shadow-md shadow-primary-100"
          >
            {submitting ? (
              <span className="flex items-center justify-center gap-2">
                <LoadingSpinner size="sm" />
                처리 중...
              </span>
            ) : concert.status === 'SOLD_OUT' ? '매진된 공연입니다' : '예매 확정'}
          </button>
        </div>
      </div>
    </div>
  )
}
