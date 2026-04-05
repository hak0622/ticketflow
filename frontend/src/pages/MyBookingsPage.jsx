import { useState, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { getMyBookings } from '../api/booking'
import useAuthStore from '../store/authStore'
import LoadingSpinner from '../components/common/LoadingSpinner'

/* ── 예매 상태 배지 설정 ──────────────────────────── */
const BOOKING_STATUS_CONFIG = {
  CONFIRMED: {
    label:  '예매 확정',
    cls:    'bg-primary-100 text-primary-700',
  },
  PENDING_PAYMENT: {
    label:  '결제 대기',
    cls:    'bg-yellow-100 text-yellow-700',
  },
  CANCELLED: {
    label:  '취소됨',
    cls:    'bg-gray-100 text-gray-500',
  },
}

/* ── 포맷 유틸 ─────────────────────────────────── */
function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'short',
  })
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleTimeString('ko-KR', {
    hour: '2-digit', minute: '2-digit',
  })
}

function formatBookedAt(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/* ── 예매 카드 ──────────────────────────────────── */
function BookingCard({ booking }) {
  const cfg = BOOKING_STATUS_CONFIG[booking.bookingStatus] ?? BOOKING_STATUS_CONFIG.CANCELLED

  const isPending   = booking.bookingStatus === 'PENDING_PAYMENT'
  const isCancelled = booking.bookingStatus === 'CANCELLED'

  return (
    <div className={`bg-white rounded-2xl border shadow-sm overflow-hidden transition-opacity ${isCancelled ? 'opacity-60 border-gray-100' : 'border-gray-100'}`}>
      {/* 카드 상단 — 공연 정보 */}
      <div className="p-5">
        <div className="flex items-start justify-between gap-3 mb-3">
          <div className="flex-1 min-w-0">
            <p className="text-base font-black text-gray-900 truncate">{booking.concertTitle}</p>
            <p className="text-sm text-gray-500 mt-0.5">
              {formatDate(booking.eventAt)}
              {booking.eventAt && ` ${formatTime(booking.eventAt)}`}
            </p>
          </div>
          {/* 예매 상태 배지 */}
          <span className={`shrink-0 text-xs font-bold px-2.5 py-1 rounded-full ${cfg.cls}`}>
            {cfg.label}
          </span>
        </div>

        <div className="flex items-center justify-between text-sm text-gray-400 border-t border-gray-50 pt-3">
          <span>예매 번호 #{booking.bookingId}</span>
          <span>{formatBookedAt(booking.bookedAt)}</span>
        </div>
      </div>

      {/* 결제 대기 중 안내 + 결제 버튼 */}
      {isPending && (
        <div className="bg-yellow-50 border-t border-yellow-100 px-5 py-3 flex items-center justify-between gap-3">
          <p className="text-xs text-yellow-700 leading-snug">
            30분 이내에 결제하지 않으면 자동 취소됩니다.
          </p>
          <Link
            to={`/concerts/${booking.concertId}/payment`}
            className="shrink-0 text-xs font-bold bg-yellow-400 hover:bg-yellow-500 text-white px-3 py-1.5 rounded-lg transition-colors"
          >
            결제하기
          </Link>
        </div>
      )}
    </div>
  )
}

/* ── Empty State ────────────────────────────────── */
function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <span className="text-6xl">🎫</span>
      <div>
        <p className="text-base font-bold text-gray-700 mb-1">예매 내역이 없습니다</p>
        <p className="text-sm text-gray-400">관심 있는 공연을 찾아 예매해보세요.</p>
      </div>
      <Link
        to="/"
        className="mt-2 px-6 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
      >
        공연 둘러보기
      </Link>
    </div>
  )
}

/* ── 메인 컴포넌트 ──────────────────────────────── */
export default function MyBookingsPage() {
  const navigate       = useNavigate()
  const { token }      = useAuthStore()

  const [bookings, setBookings] = useState([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState(null)

  /* 비로그인 */
  useEffect(() => {
    if (!token) navigate('/login', { state: { from: '/my-bookings' } })
  }, [token, navigate])

  /* 예매 목록 조회 */
  useEffect(() => {
    if (!token) return
    getMyBookings()
      .then((res) => setBookings(res.data))
      .catch(() => setError('예매 목록을 불러올 수 없습니다.'))
      .finally(() => setLoading(false))
  }, [token])

  /* ── 로딩 ── */
  if (loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  /* ── 오류 ── */
  if (error) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        <p className="text-base font-semibold text-gray-700">{error}</p>
        <button
          onClick={() => { setError(null); setLoading(true); getMyBookings().then(r => setBookings(r.data)).catch(() => setError('예매 목록을 불러올 수 없습니다.')).finally(() => setLoading(false)) }}
          className="px-6 py-3 bg-primary-500 text-white font-semibold rounded-xl"
        >
          다시 시도
        </button>
      </div>
    )
  }

  /* ── 탭 분리: 활성 / 취소 ── */
  const active    = bookings.filter((b) => b.bookingStatus !== 'CANCELLED')
  const cancelled = bookings.filter((b) => b.bookingStatus === 'CANCELLED')

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 pb-24 md:pb-8">
      <div className="max-w-lg mx-auto px-4 pt-6">

        <h1 className="text-xl font-black text-gray-900 mb-6">내 예매</h1>

        {bookings.length === 0 ? (
          <EmptyState />
        ) : (
          <>
            {/* 활성 예매 */}
            {active.length > 0 && (
              <section className="mb-6">
                <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-3">
                  예매 내역 ({active.length})
                </p>
                <div className="flex flex-col gap-3">
                  {active.map((b) => (
                    <BookingCard key={b.bookingId} booking={b} />
                  ))}
                </div>
              </section>
            )}

            {/* 취소된 예매 */}
            {cancelled.length > 0 && (
              <section>
                <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-3">
                  취소된 예매 ({cancelled.length})
                </p>
                <div className="flex flex-col gap-3">
                  {cancelled.map((b) => (
                    <BookingCard key={b.bookingId} booking={b} />
                  ))}
                </div>
              </section>
            )}
          </>
        )}
      </div>
    </div>
  )
}
