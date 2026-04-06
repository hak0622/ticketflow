import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate, useLocation, Link } from 'react-router-dom'
import { loadTossPayments, ANONYMOUS } from '@tosspayments/tosspayments-sdk'
import { getConcert } from '../features/concert/api'
import { getBookingDetail } from '../features/booking/api'
import useAuthStore from '../features/auth/store'
import LoadingSpinner from '../shared/ui/LoadingSpinner'

const TOSS_CLIENT_KEY = import.meta.env.VITE_TOSS_CLIENT_KEY
const IS_DEV = import.meta.env.DEV

/* ── orderId 생성 ───────────────────────────────── */
function generateOrderId(concertId) {
  const rand = crypto.randomUUID().replace(/-/g, '').slice(0, 16)
  return `TICKETLY-${concertId}-${rand}`
}

/* ── 포맷 유틸 ─────────────────────────────────── */
function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric',
    weekday: 'short', hour: '2-digit', minute: '2-digit',
  })
}

function formatPrice(price) {
  if (price == null) return '-'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

/* ── 요약 행 ────────────────────────────────────── */
function SummaryRow({ label, value, highlight }) {
  return (
    <div className="flex justify-between items-center py-2.5 border-b border-gray-100 last:border-0">
      <span className="text-sm text-gray-400">{label}</span>
      <span className={`text-sm font-semibold ml-4 text-right ${highlight ? 'text-primary-600 text-base' : 'text-gray-800'}`}>
        {value}
      </span>
    </div>
  )
}

/* ── 메인 컴포넌트 ──────────────────────────────── */
export default function PaymentPage() {
  const { id: concertId } = useParams()
  const navigate           = useNavigate()
  const location           = useLocation()
  const { token, user }    = useAuthStore()

  const [concert, setConcert] = useState(location.state?.concert ?? null)
  const [loading, setLoading] = useState(!location.state?.concert)
  const [error, setError]     = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [orderId, setOrderId]       = useState(() => generateOrderId(concertId))
  const submittingRef               = useRef(false)   // 이중 클릭 방지

  /* ── 비로그인 리다이렉트 ── */
  useEffect(() => {
    if (!token) {
      navigate('/login', { state: { from: `/concerts/${concertId}/payment` } })
    }
  }, [token, navigate, concertId])

  /* ── 직접 접근 시 공연·예매 정보 재조회 + 상태 검증 ── */
  useEffect(() => {
    if (!token || concert) return

    const fetchAndValidate = async () => {
      try {
        const [cRes, bRes] = await Promise.all([
          getConcert(concertId),
          getBookingDetail(concertId),
        ])
        const bookingStatus = bRes.data?.status
        if (bookingStatus === 'CONFIRMED') {
          setError('already_confirmed')
        } else if (bookingStatus === 'CANCELLED') {
          setError('already_cancelled')
        } else {
          setConcert(cRes.data)
        }
      } catch (err) {
        const status = err.response?.status
        setError(status === 404 || status === 400 ? 'no_booking' : '예매 정보를 불러올 수 없습니다.')
      } finally {
        setLoading(false)
      }
    }
    fetchAndValidate()
  }, [concertId, token, concert])

  /* ── 결제 요청 ── */
  const handlePay = async () => {
    if (!concert || submittingRef.current) return

    // 개발 안전장치: test_ 키가 아니면 결제 차단
    if (!TOSS_CLIENT_KEY?.startsWith('test_')) {
      console.warn('[결제 안전장치] 테스트 키(test_ck_...)가 아닙니다. 결제를 차단합니다.')
      alert('개발 환경에서는 테스트 클라이언트 키(test_ck_...)만 사용할 수 있습니다.')
      return
    }

    submittingRef.current = true
    setSubmitting(true)
    setError(null)

    // dev 환경에서는 orderId / orderName에 테스트 식별자 추가
    const payOrderId  = IS_DEV ? `dev-order-${orderId}` : orderId
    const payOrderName = IS_DEV ? `[TEST] ${concert.title}` : concert.title

    try {
      const tossPayments = await loadTossPayments(TOSS_CLIENT_KEY)
      const payment = tossPayments.payment({ customerKey: ANONYMOUS })

      await payment.requestPayment({
        method: 'CARD',
        orderId: payOrderId,
        orderName: payOrderName,
        amount: { currency: 'KRW', value: concert.price },
        successUrl: `${window.location.origin}/payment/success?concertId=${concertId}`,
        failUrl:    `${window.location.origin}/payment/fail?concertId=${concertId}`,
        customerEmail: user?.sub ?? '',
        customerName:  user?.sub?.split('@')[0] ?? '고객',
      })
      // requestPayment는 successUrl로 리다이렉트하므로 이 이후 코드는 실행되지 않음
    } catch (err) {
      if (err?.code !== 'USER_CANCEL') {
        setError(err?.message || '결제 요청 중 오류가 발생했습니다.')
      }
      // 다음 시도를 위해 새 orderId 발급
      setOrderId(generateOrderId(concertId))
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  /* ════════════ 예외 화면들 ════════════ */

  if (loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (error === 'already_confirmed') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className="w-24 h-24 rounded-full ring-4 ring-green-200 bg-green-50 flex items-center justify-center text-5xl">🎫</div>
        <div>
          <p className="text-xl font-bold text-green-600 mb-2">이미 결제 완료된 예매입니다</p>
          <p className="text-sm text-gray-400">내 예매 목록에서 확인하세요.</p>
        </div>
        <Link to="/my-bookings" className="px-8 py-3.5 bg-primary-500 hover:bg-primary-600 text-white font-bold rounded-xl transition-colors">
          내 예매 확인하기
        </Link>
      </div>
    )
  }

  if (error === 'already_cancelled') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className="w-24 h-24 rounded-full ring-4 ring-gray-200 bg-gray-50 flex items-center justify-center text-5xl">❌</div>
        <div>
          <p className="text-xl font-bold text-gray-700 mb-2">취소된 예매입니다</p>
          <p className="text-sm text-gray-400">대기열에 다시 등록해 예매해주세요.</p>
        </div>
        <Link to={`/concerts/${concertId}`} className="px-8 py-3.5 bg-primary-500 hover:bg-primary-600 text-white font-bold rounded-xl transition-colors">
          공연 페이지로 돌아가기
        </Link>
      </div>
    )
  }

  if (error === 'no_booking' || (!concert && error)) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        <p className="text-base font-semibold text-gray-700">
          {error === 'no_booking' ? '예매 내역이 없습니다.' : error}
        </p>
        <Link to={`/concerts/${concertId}`} className="px-6 py-3 bg-primary-500 text-white font-semibold rounded-xl">
          공연 페이지로 돌아가기
        </Link>
      </div>
    )
  }

  /* ════════════ 결제 ��력 화면 ════════════ */
  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 px-4 pt-6 pb-32 md:pb-12">
      <div className="max-w-lg mx-auto">

        <Link
          to={`/concerts/${concertId}/booking`}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-6"
        >
          ← 예매 페이지
        </Link>

        <h1 className="text-xl font-black text-gray-900 mb-5">결제</h1>

        {/* 예매 요약 */}
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5 mb-4">
          <p className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-3">예매 정보</p>
          <div className="space-y-0.5">
            <SummaryRow label="공연명"   value={concert?.title ?? '-'} />
            {concert?.artist  && <SummaryRow label="아티스트" value={concert.artist} />}
            {concert?.eventAt && <SummaryRow label="일시"     value={formatDateTime(concert.eventAt)} />}
          </div>
          <div className="mt-3 pt-3 border-t border-gray-100">
            <SummaryRow label="결제 금액" value={formatPrice(concert?.price)} highlight />
          </div>
        </div>

        {/* 개발 환경 안내 배너 */}
        {IS_DEV && (
          <div className="bg-yellow-50 border border-yellow-200 rounded-xl px-4 py-3 mb-4">
            <p className="text-xs text-yellow-700 font-semibold">개발 환경 — 테스트 결제</p>
            <p className="text-xs text-yellow-600 mt-0.5">실제 결제가 발생하지 않습니다. 테스트 카드로 진행하세요.</p>
          </div>
        )}

        {/* 오류 메시지 */}
        {error && !['already_confirmed', 'already_cancelled', 'no_booking'].includes(error) && (
          <div className="bg-red-50 rounded-xl px-4 py-3 mb-4">
            <p className="text-sm text-red-600 text-center">{error}</p>
          </div>
        )}

        {/* CTA */}
        <button
          onClick={handlePay}
          disabled={submitting}
          className="w-full py-4 bg-primary-500 hover:bg-primary-600 disabled:opacity-60 disabled:cursor-not-allowed text-white font-bold text-lg rounded-2xl transition-colors shadow-md shadow-primary-100"
        >
          {submitting ? (
            <span className="flex items-center justify-center gap-2">
              <LoadingSpinner size="sm" />
              결제 처리 중...
            </span>
          ) : (
            `${formatPrice(concert?.price)} 결제하기`
          )}
        </button>
      </div>
    </div>
  )
}
