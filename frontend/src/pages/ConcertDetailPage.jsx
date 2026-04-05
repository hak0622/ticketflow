import { useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { getConcert } from '../api/concert'
import { registerQueue } from '../api/booking'
import useAuthStore from '../store/authStore'
import LoadingSpinner from '../components/common/LoadingSpinner'

/* ─── 상태 설정 ─────────────────────────────── */
const STATUS_CONFIG = {
  OPEN:     { label: '예매 가능', badgeCls: 'bg-primary-100 text-primary-700' },
  SOLD_OUT: { label: '매진',     badgeCls: 'bg-red-100 text-red-600' },
  CLOSED:   { label: '마감',     badgeCls: 'bg-gray-100 text-gray-500' },
}

/* ─── 포맷 유틸 ──────────────────────────────── */
function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleDateString('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  })
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

/* ─── 정보 행 컴포넌트 ───────────────────────── */
function InfoRow({ icon, label, value }) {
  if (!value) return null
  return (
    <div className="flex items-start gap-3 py-3 border-b border-gray-100 last:border-0">
      <span className="text-base w-5 text-center shrink-0 mt-0.5">{icon}</span>
      <div className="flex-1 min-w-0">
        <p className="text-xs text-gray-400 mb-0.5">{label}</p>
        <p className="text-sm font-medium text-gray-800 break-words">{value}</p>
      </div>
    </div>
  )
}

/* ─── 메인 컴포넌트 ──────────────────────────── */
export default function ConcertDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { token } = useAuthStore()

  const [concert, setConcert]     = useState(null)
  const [loading, setLoading]     = useState(true)
  const [notFound, setNotFound]   = useState(false)
  const [queueLoading, setQueueLoading] = useState(false)
  const [queueError, setQueueError]     = useState('')

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
    setQueueLoading(true)
    setQueueError('')
    try {
      await registerQueue(id)
      navigate(`/concerts/${id}/queue`)
    } catch (err) {
      const msg = err.response?.data?.message
      if (err.response?.status === 409) {
        // 이미 대기열에 있는 경우 → 바로 이동
        navigate(`/concerts/${id}/queue`)
      } else {
        setQueueError(msg || '대기열 등록에 실패했습니다. 잠시 후 다시 시도해주세요.')
      }
    } finally {
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
        <p className="text-base font-medium">공연을 찾을 수 없습니다.</p>
        <Link to="/" className="text-sm text-primary-600 underline">
          목록으로 돌아가기
        </Link>
      </div>
    )
  }

  const {
    title, artist, eventAt, totalSeats, bookedCount,
    status, posterUrl, price, genre,
  } = concert
  const remaining = totalSeats - bookedCount
  const cfg = STATUS_CONFIG[status] ?? STATUS_CONFIG.CLOSED
  const canBook = status === 'OPEN'

  /* ── CTA 버튼 텍스트 ── */
  const ctaText = () => {
    if (queueLoading) return '대기열 등록 중...'
    if (status === 'SOLD_OUT') return '매진된 공연입니다'
    if (status === 'CLOSED')   return '예매가 마감되었습니다'
    if (!token)                return '로그인 후 예매하기'
    return '예매하기'
  }

  return (
    /* 하단 고정 CTA 높이(72px)만큼 pb 확보 */
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 pb-24 md:pb-10">

      {/* ── 뒤로가기 ── */}
      <div className="max-w-screen-lg mx-auto px-4 pt-4">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-4"
        >
          ← 목록으로
        </button>
      </div>

      {/* ── 메인 레이아웃 ── */}
      <div className="max-w-screen-lg mx-auto px-4">
        <div className="flex flex-col md:flex-row gap-6 md:gap-10">

          {/* ── 포스터 ── */}
          <div className="w-full md:w-72 shrink-0">
            <div className="relative aspect-[3/4] rounded-2xl overflow-hidden bg-gradient-to-br from-gray-200 to-gray-300 shadow-md">
              {posterUrl ? (
                <img
                  src={posterUrl}
                  alt={title}
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full flex flex-col items-center justify-center gap-3">
                  <span className="text-6xl">🎵</span>
                  <span className="text-sm text-gray-400">포스터 없음</span>
                </div>
              )}
              {/* 상태 배지 */}
              <span className={`absolute top-3 left-3 text-xs font-bold px-3 py-1 rounded-full shadow-sm ${cfg.badgeCls}`}>
                {cfg.label}
              </span>
            </div>
          </div>

          {/* ── 공연 정보 ── */}
          <div className="flex-1 min-w-0">
            {/* 장르 태그 */}
            {genre && (
              <span className="inline-block text-xs font-semibold text-primary-600 bg-primary-50 px-2.5 py-1 rounded-full mb-3">
                {genre}
              </span>
            )}

            {/* 아티스트 */}
            {artist && (
              <p className="text-sm font-semibold text-primary-500 mb-1">{artist}</p>
            )}

            {/* 공연명 */}
            <h1 className="text-2xl md:text-3xl font-black text-gray-900 leading-tight mb-5">
              {title}
            </h1>

            {/* 정보 카드 */}
            <div className="bg-white rounded-2xl border border-gray-100 shadow-sm px-5 py-1 mb-4">
              <InfoRow icon="📅" label="공연 날짜" value={formatDate(eventAt)} />
              <InfoRow icon="🕐" label="공연 시간" value={formatTime(eventAt)} />
              <InfoRow icon="💰" label="티켓 가격" value={formatPrice(price)} />
              <InfoRow
                icon="🪑"
                label="잔여석"
                value={
                  status === 'OPEN'
                    ? `${remaining.toLocaleString()}석 / 전체 ${totalSeats.toLocaleString()}석`
                    : cfg.label
                }
              />
            </div>

            {/* 에러 메시지 */}
            {queueError && (
              <div className="mb-4 px-4 py-3 bg-red-50 border border-red-100 rounded-xl text-sm text-red-600">
                {queueError}
              </div>
            )}

            {/* CTA 버튼 (데스크탑) */}
            <button
              onClick={handleBook}
              disabled={!canBook || queueLoading}
              className={`hidden md:flex w-full items-center justify-center gap-2 py-4 rounded-xl font-bold text-base transition-colors
                ${canBook
                  ? 'bg-primary-500 hover:bg-primary-600 text-white shadow-md shadow-primary-200'
                  : 'bg-gray-100 text-gray-400 cursor-not-allowed'
                }
                disabled:opacity-60
              `}
            >
              {queueLoading && <LoadingSpinner size="sm" />}
              {ctaText()}
            </button>

            {/* 예매 안내 (데스크탑) */}
            {canBook && !queueLoading && (
              <p className="hidden md:block mt-3 text-xs text-gray-400 text-center">
                예매 버튼 클릭 시 대기열에 등록됩니다. 순번이 되면 예매를 진행하세요.
              </p>
            )}
          </div>
        </div>
      </div>

      {/* ── 하단 고정 CTA (모바일) ── */}
      <div className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-gray-100 px-4 py-3 pb-safe">
        <button
          onClick={handleBook}
          disabled={!canBook || queueLoading}
          className={`w-full flex items-center justify-center gap-2 py-4 rounded-xl font-bold text-base transition-colors
            ${canBook
              ? 'bg-primary-500 hover:bg-primary-600 text-white shadow-md shadow-primary-200'
              : 'bg-gray-100 text-gray-400 cursor-not-allowed'
            }
            disabled:opacity-60
          `}
        >
          {queueLoading && <LoadingSpinner size="sm" />}
          {ctaText()}
        </button>
        {canBook && !queueLoading && (
          <p className="mt-1.5 text-xs text-gray-400 text-center">
            순번 대기 후 예매가 진행됩니다
          </p>
        )}
      </div>
    </div>
  )
}
