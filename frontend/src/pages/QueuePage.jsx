import { useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQueuePolling } from '../hooks/useQueuePolling'
import useAuthStore from '../store/authStore'
import LoadingSpinner from '../components/common/LoadingSpinner'

/* ── 대기열 상태별 설정 ─────────────────────────────── */
const STATUS_CONFIG = {
  QUEUED: {
    color:   'text-primary-600',
    bgColor: 'bg-primary-50',
    ring:    'ring-primary-200',
    icon:    '⏳',
    label:   '대기 중',
  },
  ADMITTED: {
    color:   'text-green-600',
    bgColor: 'bg-green-50',
    ring:    'ring-green-200',
    icon:    '🎉',
    label:   '입장 가능!',
  },
  BOOKED: {
    color:   'text-blue-600',
    bgColor: 'bg-blue-50',
    ring:    'ring-blue-200',
    icon:    '🎫',
    label:   '예매 완료',
  },
  NOT_IN_QUEUE: {
    color:   'text-gray-500',
    bgColor: 'bg-gray-50',
    ring:    'ring-gray-200',
    icon:    '❌',
    label:   '대기열 없음',
  },
}

/* ── 회전 점 애니메이션 ─────────────────────────────── */
function PulsingDots() {
  return (
    <div className="flex items-center justify-center gap-1.5 mt-3">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="w-2 h-2 rounded-full bg-primary-400 animate-bounce"
          style={{ animationDelay: `${i * 0.15}s` }}
        />
      ))}
    </div>
  )
}

/* ── 진행 바 ────────────────────────────────────────── */
function ProgressBar({ position, total }) {
  if (!total || total === 0) return null
  // 앞에 있는 사람 수 = position - 1 (내 앞 사람들)
  const ahead = Math.max(0, position - 1)
  const pct   = Math.round(((total - ahead) / total) * 100)

  return (
    <div className="w-full mt-4">
      <div className="flex justify-between text-xs text-gray-400 mb-1">
        <span>대기 시작</span>
        <span>입장</span>
      </div>
      <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
        <div
          className="h-full bg-primary-400 rounded-full transition-all duration-700"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}

/* ── 메인 컴포넌트 ──────────────────────────────────── */
export default function QueuePage() {
  const { id: concertId } = useParams()
  const navigate          = useNavigate()
  const { token }         = useAuthStore()
  const { data, loading, error } = useQueuePolling(concertId)

  const [countdown, setCountdown] = useState(null) // ADMITTED 후 카운트다운

  /* 비로그인 → 로그인 페이지 */
  useEffect(() => {
    if (!token) navigate('/login', { state: { from: `/concerts/${concertId}/queue` } })
  }, [token, navigate, concertId])

  /* ADMITTED → 1.5초 후 예매 페이지로 이동 */
  useEffect(() => {
    if (data?.status !== 'ADMITTED') return
    setCountdown(3)
    const tick = setInterval(() => {
      setCountdown((c) => {
        if (c <= 1) {
          clearInterval(tick)
          navigate(`/concerts/${concertId}/booking`, { replace: true })
          return 0
        }
        return c - 1
      })
    }, 1000)
    return () => clearInterval(tick)
  }, [data?.status, navigate, concertId])

  /* 페이지 이탈 경고 (QUEUED 상태일 때) */
  useEffect(() => {
    if (data?.status !== 'QUEUED') return
    const handler = (e) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [data?.status])

  /* ── 첫 로딩 ── */
  if (loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  /* ── 네트워크 오류 ── */
  if (error && !data) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        <p className="text-base font-semibold text-gray-700">연결 오류가 발생했습니다.</p>
        <p className="text-sm text-gray-400">잠시 후 자동으로 재시도합니다.</p>
        <LoadingSpinner size="sm" className="mt-2" />
      </div>
    )
  }

  const status = data?.status ?? 'NOT_IN_QUEUE'
  const cfg    = STATUS_CONFIG[status] ?? STATUS_CONFIG.NOT_IN_QUEUE

  /* ── NOT_IN_QUEUE ── */
  if (status === 'NOT_IN_QUEUE') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className={`w-24 h-24 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl`}>
          {cfg.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">대기열에 없습니다</p>
          <p className="text-sm text-gray-400 leading-relaxed">
            대기열이 만료되었거나 등록되지 않은 상태입니다.<br />
            다시 예매하기를 눌러주세요.
          </p>
        </div>
        <Link
          to={`/concerts/${concertId}`}
          className="mt-2 px-6 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
        >
          공연 페이지로 돌아가기
        </Link>
      </div>
    )
  }

  /* ── BOOKED ── */
  if (status === 'BOOKED') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className={`w-24 h-24 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl`}>
          {cfg.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">이미 예매한 공연입니다</p>
          <p className="text-sm text-gray-400">내 예매 목록에서 확인하세요.</p>
        </div>
        <div className="flex gap-3">
          <Link
            to="/my-bookings"
            className="px-5 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
          >
            내 예매 확인
          </Link>
          <Link
            to="/"
            className="px-5 py-3 border-2 border-gray-200 hover:border-primary-300 text-gray-600 font-semibold rounded-xl transition-colors"
          >
            홈으로
          </Link>
        </div>
      </div>
    )
  }

  /* ── ADMITTED ── */
  if (status === 'ADMITTED') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className={`w-28 h-28 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl animate-bounce`}>
          {cfg.icon}
        </div>
        <div>
          <p className="text-2xl font-black text-green-600 mb-2">입장 가능!</p>
          <p className="text-sm text-gray-500">
            {countdown !== null
              ? `${countdown}초 후 예매 페이지로 이동합니다...`
              : '예매 페이지로 이동 중...'}
          </p>
        </div>
        <button
          onClick={() => navigate(`/concerts/${concertId}/booking`, { replace: true })}
          className="px-8 py-3.5 bg-green-500 hover:bg-green-600 text-white font-bold rounded-xl transition-colors shadow-md shadow-green-100"
        >
          지금 바로 예매하기
        </button>
      </div>
    )
  }

  /* ── QUEUED (대기 중 메인 화면) ── */
  const { position, total } = data
  const ahead = Math.max(0, position - 1)

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 flex flex-col items-center justify-center px-4 pb-20 md:pb-0">
      <div className="w-full max-w-sm">

        {/* 상단 공연 링크 */}
        <Link
          to={`/concerts/${concertId}`}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-8"
        >
          ← 공연 페이지
        </Link>

        {/* 카드 */}
        <div className="bg-white rounded-3xl border border-gray-100 shadow-sm p-8 text-center">

          {/* 상태 아이콘 + 레이블 */}
          <div className={`w-20 h-20 mx-auto rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-4xl mb-4`}>
            {cfg.icon}
          </div>
          <p className={`text-sm font-bold ${cfg.color} mb-1`}>{cfg.label}</p>

          {/* 순번 */}
          <div className="my-6">
            <p className="text-xs text-gray-400 mb-1">현재 내 순번</p>
            <p className="text-6xl font-black text-gray-900 tabular-nums leading-none">
              {position.toLocaleString()}
            </p>
            <p className="text-sm text-gray-400 mt-2">
              내 앞에{' '}
              <span className="font-bold text-gray-700">
                {ahead.toLocaleString()}명
              </span>
              {total ? ` / 전체 ${total.toLocaleString()}명 대기 중` : ''}
            </p>
          </div>

          {/* 진행 바 */}
          <ProgressBar position={position} total={total} />

          {/* 안내 문구 */}
          <div className="mt-6 py-3 px-4 bg-primary-50 rounded-xl">
            <p className="text-xs text-primary-700 leading-relaxed">
              5초마다 순번이 업데이트됩니다.<br />
              페이지를 닫으면 대기열에서 이탈됩니다.
            </p>
          </div>

          {/* 애니메이션 */}
          <PulsingDots />
        </div>

        {/* 마지막 업데이트 시각 */}
        <p className="mt-4 text-xs text-center text-gray-300">
          마지막 업데이트: {new Date().toLocaleTimeString('ko-KR')}
        </p>
      </div>
    </div>
  )
}
