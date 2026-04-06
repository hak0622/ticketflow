import { useEffect, useRef, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getConcert } from '../features/concert/api'
import { registerQueue } from '../features/booking/api'
import useAuthStore from '../features/auth/store'
import LoadingSpinner from '../shared/ui/LoadingSpinner'
import StatusBadge from '../shared/ui/StatusBadge'
import PageContainer from '../shared/ui/PageContainer'
import { getPosterByConcert } from '../constants/posterMap'

/* ─── 포맷 유틸 ──────────────────────────────── */
function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  const date = d.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
  const time = d.toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  })
  return `${date} · ${time}`
}

function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

/* ─── CTA 버튼 ───────────────────────────────── */
function CtaButton({ canBook, queueLoading, onClick, text }) {
  return (
    <button
      onClick={onClick}
      disabled={!canBook || queueLoading}
      className={`w-full flex items-center justify-center gap-2 py-4 rounded-xl font-bold text-base transition-colors disabled:opacity-60
        ${
          canBook
            ? 'bg-primary-500 hover:bg-primary-600 text-white shadow-md shadow-primary-100'
            : 'bg-gray-100 text-gray-400 cursor-not-allowed'
        }`}
    >
      {queueLoading && <LoadingSpinner size="sm" />}
      {text}
    </button>
  )
}

/* ─── 잔여석 프로그레스 바 ──────────────────── */
function SeatBar({ remaining, totalSeats }) {
  const pct = Math.max(0, Math.min(100, (remaining / totalSeats) * 100))
  const color =
    pct > 30 ? 'bg-primary-400' : pct > 10 ? 'bg-yellow-400' : 'bg-red-400'

  return (
    <div className="mt-1.5 h-1.5 w-full rounded-full bg-gray-100 overflow-hidden">
      <div
        className={`h-full rounded-full ${color} transition-all`}
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}

/* ─── 메인 컴포넌트 ──────────────────────────── */
export default function ConcertDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { token } = useAuthStore()

  const [concert, setConcert] = useState(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [queueLoading, setQueueLoading] = useState(false)
  const [queueError, setQueueError] = useState('')
  const queueLoadingRef = useRef(false)

  useEffect(() => {
    getConcert(id)
      .then((res) => setConcert(res.data))
      .catch((err) => {
        if (err.response?.status === 404) setNotFound(true)
      })
      .finally(() => setLoading(false))
  }, [id])

  /* 예매하기 클릭 */
  const handleBook = async () => {
    if (!token) {
      navigate('/login', { state: { from: `/concerts/${id}` } })
      return
    }

    if (queueLoadingRef.current) return

    queueLoadingRef.current = true
    setQueueLoading(true)
    setQueueError('')

    try {
      await registerQueue(id)
      navigate(`/concerts/${id}/queue`)
    } catch (err) {
      const status = err.response?.status
      const serverMsg = err.response?.data?.message

      if (status === 409) {
        navigate(`/concerts/${id}/queue`)
      } else if (status === 403) {
        setQueueError(serverMsg || '예매가 불가능한 공연입니다.')
      } else if (status === 401) {
        navigate('/login', { state: { from: `/concerts/${id}` } })
      } else {
        setQueueError(
          serverMsg || '대기열 등록에 실패했습니다. 잠시 후 다시 시도해주세요.',
        )
      }
    } finally {
      queueLoadingRef.current = false
      setQueueLoading(false)
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

  /* ── 404 ── */
  if (notFound || !concert) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 text-gray-400">
        <span className="text-5xl">🎵</span>
        <p className="text-base font-semibold text-gray-600">
          공연을 찾을 수 없습니다.
        </p>
        <Link
          to="/"
          className="text-sm text-primary-600 font-semibold hover:underline"
        >
          목록으로 돌아가기
        </Link>
      </div>
    )
  }

  const {
    title,
    artist,
    eventAt,
    totalSeats,
    bookedCount,
    status,
    price,
    genre,
  } = concert

  const posterSrc = getPosterByConcert(concert)
  const remaining = totalSeats - bookedCount
  const isPast = eventAt && new Date(eventAt) < new Date()
  const effectiveStatus = isPast && status === 'OPEN' ? 'CLOSED' : status
  const canBook = effectiveStatus === 'OPEN'

  const ctaText = () => {
    if (queueLoading) return '대기열 등록 중...'
    if (effectiveStatus === 'SOLD_OUT') return '매진된 공연입니다'
    if (effectiveStatus === 'CLOSED') return '예매가 마감되었습니다'
    if (!token) return '로그인 후 예매하기'
    return '예매하기'
  }

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 pb-28 md:pb-16">
      {/* ── 뒤로가기 ── */}
      <PageContainer className="pt-5 pb-2">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-700 transition-colors"
        >
          ← 목록으로
        </button>
      </PageContainer>

      {/* ── 메인 레이아웃 ── */}
      <PageContainer className="pt-4">
        <div className="flex flex-col md:flex-row gap-0 md:gap-12 max-w-screen-lg">
          {/* ── 포스터 ── */}
          <div className="w-full md:w-64 shrink-0">
            <div className="relative aspect-[3/4] md:rounded-2xl overflow-hidden bg-gray-200 shadow-md">
              {posterSrc ? (
                <img
                  src={posterSrc}
                  alt={title}
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center bg-gradient-to-br from-gray-100 to-gray-300">
                  <span className="text-7xl opacity-20">🎵</span>
                </div>
              )}

              <div className="md:hidden absolute inset-x-0 bottom-0 h-32 bg-gradient-to-t from-black/60 to-transparent" />

              <div className="md:hidden absolute bottom-0 left-0 right-0 px-4 pb-4">
                <div className="flex flex-wrap items-center gap-1.5 mb-1">
                  {genre && (
                    <span className="text-xs font-semibold text-white/90 bg-white/20 backdrop-blur-sm px-2 py-0.5 rounded-full">
                      {genre}
                    </span>
                  )}
                  <StatusBadge status={effectiveStatus} />
                </div>
                <h1 className="text-xl font-black text-white leading-tight drop-shadow">
                  {title}
                </h1>
                {artist && (
                  <p className="text-sm text-white/80 font-semibold mt-0.5">
                    {artist}
                  </p>
                )}
              </div>
            </div>
          </div>

          {/* ── 공연 정보 ── */}
          <div className="flex-1 min-w-0">
            <div className="hidden md:block mb-6">
              <div className="flex flex-wrap items-center gap-2 mb-3">
                {genre && (
                  <span className="text-xs font-bold text-gray-500 bg-gray-100 px-2.5 py-1 rounded-sm tracking-wide">
                    {genre}
                  </span>
                )}
                <StatusBadge status={effectiveStatus} />
              </div>
              <h1 className="text-3xl font-black text-gray-900 leading-tight mb-2">
                {title}
              </h1>
              {artist && (
                <p className="text-base font-semibold text-gray-500">{artist}</p>
              )}
            </div>

            <div className="bg-white md:bg-transparent rounded-t-2xl md:rounded-none px-5 md:px-0 pt-5 md:pt-0 -mt-4 md:mt-0 relative">
              <div className="mb-5">
                <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest mb-1">
                  공연 일시
                </p>
                <p className="text-sm font-semibold text-gray-800">
                  {formatDateTime(eventAt)}
                </p>
              </div>

              <div className="mb-5">
                <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest mb-1">
                  잔여석
                </p>
                {canBook ? (
                  <>
                    <p className="text-sm font-semibold text-gray-800">
                      {remaining.toLocaleString()}석
                      <span className="text-gray-400 font-normal">
                        {' '}
                        / 전체 {totalSeats.toLocaleString()}석
                      </span>
                    </p>
                    <SeatBar remaining={remaining} totalSeats={totalSeats} />
                  </>
                ) : (
                  <p className="text-sm font-semibold text-gray-400">
                    {effectiveStatus === 'SOLD_OUT' ? '매진' : '예매 마감'}
                  </p>
                )}
              </div>

              <hr className="border-gray-100 mb-5" />

              <div className="mb-6">
                <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest mb-1">
                  가격
                </p>
                <p className="text-2xl font-black text-gray-900">
                  {formatPrice(price)}
                </p>
              </div>

              {queueError && (
                <div className="mb-4 px-4 py-3 bg-red-50 border border-red-100 rounded-xl text-sm text-red-600">
                  {queueError}
                </div>
              )}

              <div className="hidden md:block">
                <CtaButton
                  canBook={canBook}
                  queueLoading={queueLoading}
                  onClick={handleBook}
                  text={ctaText()}
                />
                {canBook && !queueLoading && (
                  <p className="mt-2 text-xs text-gray-400 text-center">
                    예매 버튼 클릭 시 대기열에 등록됩니다. 순번이 되면 예매를 진행하세요.
                  </p>
                )}
              </div>

              <div className="md:hidden h-2" />
            </div>
          </div>
        </div>
      </PageContainer>

      {/* ── 하단 고정 CTA (모바일) ── */}
      <div className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-gray-100 px-4 py-3">
        <CtaButton
          canBook={canBook}
          queueLoading={queueLoading}
          onClick={handleBook}
          text={ctaText()}
        />
        {canBook && !queueLoading && (
          <p className="mt-1.5 text-xs text-gray-400 text-center">
            순번 대기 후 예매가 진행됩니다
          </p>
        )}
      </div>
    </div>
  )
}
