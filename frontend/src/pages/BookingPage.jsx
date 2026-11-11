import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getConcert } from '../features/concert/api'
import { createBooking, getBookingAttempt } from '../features/booking/api'
import useAuthStore from '../features/auth/store'
import LoadingSpinner from '../shared/ui/LoadingSpinner'

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
  const { token, user }    = useAuthStore()

  const [concert, setConcert]     = useState(null)
  const [loading, setLoading]     = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError]         = useState(null)
  const submittingRef             = useRef(false)   // 이중 클릭 방지

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

  // Keep the same ID across uncertain responses, reloads, and retries for this login/concert.
  const requestStorageKey = `booking-request:${user?.sub}:${concertId}`
  const pollTimer = useRef(null)
  const alive = useRef(true)
  const pollStarted = useRef(0)
  const requestGeneration = useRef(0)
  const [recovering, setRecovering] = useState(false)
  const stopRecovery = () => { clearTimeout(pollTimer.current); setRecovering(false) }
  const inspectRequest = async (requestId, generation = requestGeneration.current) => {
    if (!alive.current || generation !== requestGeneration.current) return
    try {
      const res = await getBookingAttempt(concertId, requestId)
      if (!alive.current || generation !== requestGeneration.current) return
      if (res.status === 202) {
        setRecovering(true)
        setError('예매 요청의 처리 결과를 확인 중입니다. 기존 요청을 유지하며 복구를 진행합니다.')
        if (Date.now() - pollStarted.current < 600000) pollTimer.current = setTimeout(() => inspectRequest(requestId, generation), 5000)
        else { stopRecovery(); setError('자동 확인을 중단했습니다. 버튼을 눌러 같은 요청을 다시 확인해주세요.') }
        return
      }
      stopRecovery()
      sessionStorage.removeItem(requestStorageKey)
      if (res.data.bookingStatus === 'PENDING_PAYMENT') {
        navigate(`/concerts/${concertId}/payment`, { replace: true })
      } else if (res.data.bookingStatus) {
        navigate('/my-bookings', { replace: true })
      } else setError(res.data.status === 'SOLD_OUT' ? '매진된 공연입니다.' : '종료된 요청입니다. 다시 예매하거나 대기열에 등록해주세요.')
    } catch (failure) {
      if (!alive.current || generation !== requestGeneration.current) return
      const status = failure.response?.status
      if (status === 409 && failure.response?.data?.status === 'BOOKING_FAILED') {
        stopRecovery(); sessionStorage.removeItem(requestStorageKey)
        setError('반환이 완료된 요청입니다. 다시 예매하거나 대기열에 등록해주세요.')
      } else if (status === 404) {
        stopRecovery(); setError('요청 접수 여부를 확인하지 못했습니다. 버튼을 누르면 같은 요청을 다시 보냅니다.')
      } else if (status === 401 || status === 400) {
        stopRecovery(); setError('로그인 또는 요청 정보를 확인해주세요.')
      } else if (Date.now() - pollStarted.current < 600000) {
        setRecovering(true); setError('서버 연결을 확인 중입니다. 기존 요청은 유지됩니다.')
        pollTimer.current = setTimeout(() => inspectRequest(requestId, generation), 10000)
      } else { stopRecovery(); setError('결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해주세요.') }
    }
  }
  useEffect(() => {
    alive.current = true
    submittingRef.current = false
    setSubmitting(false)
    setRecovering(false)
    const saved = sessionStorage.getItem(requestStorageKey)
    if (token && saved) { pollStarted.current = Date.now(); inspectRequest(saved) }
    return () => { alive.current = false; requestGeneration.current += 1; clearTimeout(pollTimer.current) }
    // Request identity is scoped to this login and concert.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestStorageKey, token])
  const handleBook = async () => {
    const generation = requestGeneration.current
    if (submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    setError(null)
    try {
      let requestId = sessionStorage.getItem(requestStorageKey)
      if (!requestId) {
        requestId = crypto.randomUUID()
        sessionStorage.setItem(requestStorageKey, requestId)
      }
      await createBooking(concertId, undefined, requestId)
      if (!alive.current || generation !== requestGeneration.current) return
      pollStarted.current = Date.now()
      await inspectRequest(requestId, generation)
      submittingRef.current = false
      setSubmitting(false)
    } catch (err) {
      if (!alive.current || generation !== requestGeneration.current) return
      const status    = err.response?.status
      const serverMsg = err.response?.data?.message ?? ''

      if (status === 409 && err.response?.data?.status === 'BOOKING_FAILED') {
        sessionStorage.removeItem(requestStorageKey)
        setError('이 요청은 종료되었습니다. 예매 버튼을 눌러 새 요청으로 다시 시도해주세요.')
      } else if (status === 409) {
        if (serverMsg.includes('정원') || serverMsg.includes('SOLD_OUT')) {
          // 매진
          setError('매진된 공연입니다. 예매가 불가합니다.')
        } else if (serverMsg.includes('입장 권한')) {
          // 입장권 없음 / 만료
          setError('입장권이 만료되었습니다. 대기열에 다시 등록해주세요.')
        } else {
          setError(serverMsg || '예매 요청을 처리할 수 없습니다.')
        }
      } else if (status === 403) {
        setError(serverMsg || '예매가 불가능한 공연입니다.')
      } else if (status === 401) {
        navigate('/login', { state: { from: `/concerts/${concertId}/booking` } })
        return
      } else {
        pollStarted.current = Date.now()
        await inspectRequest(sessionStorage.getItem(requestStorageKey))
      }
      submittingRef.current = false
      setSubmitting(false)
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
            {concert.discountedPrice != null ? (
              <div className="flex justify-between items-start py-2.5 border-b border-gray-100">
                <span className="text-sm text-gray-400 shrink-0">티켓 가격</span>
                <div className="flex flex-col items-end ml-4">
                  <span className="text-gray-400 line-through text-xs">{formatPrice(concert.price)}</span>
                  <span className="text-primary-600 font-bold text-base">
                    {formatPrice(concert.discountedPrice)}
                    <span className="text-red-500 text-xs font-black ml-1">({concert.discountRate}% 할인)</span>
                  </span>
                </div>
              </div>
            ) : (
              <InfoRow label="티켓 가격" value={formatPrice(concert.price)} highlight />
            )}
            <InfoRow label="잔여석" value={`${remaining}석`} />
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
            disabled={submitting || recovering || (!sessionStorage.getItem(requestStorageKey) && (concert.status === 'SOLD_OUT' || concert.status === 'CLOSED'))}
            className="w-full py-4 bg-primary-500 hover:bg-primary-600 disabled:opacity-60 disabled:cursor-not-allowed text-white font-bold text-lg rounded-2xl transition-colors shadow-md shadow-primary-100"
          >
            {submitting ? (
              <span className="flex items-center justify-center gap-2">
                <LoadingSpinner size="sm" />
                처리 중...
              </span>
            ) : recovering ? '예매 복구 중...' : sessionStorage.getItem(requestStorageKey) ? '기존 요청 확인·재전송' : concert.status === 'SOLD_OUT' ? '매진된 공연입니다' : '예매 확정'}
          </button>
        </div>
      </div>
    </div>
  )
}
