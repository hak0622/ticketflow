import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQueuePolling } from '../hooks/useQueuePolling'
import useAuthStore from '../store/authStore'
import LoadingSpinner from '../components/common/LoadingSpinner'

/* ── 대기열 상태별 설정 ─────────────────────────────── */
const STATUS_CONFIG = {
  QUEUED: {
    color: 'text-primary-600',
    bgColor: 'bg-primary-50',
    ring: 'ring-primary-200',
    icon: '⏳',
    label: '대기 중',
  },
  ADMITTED: {
    color: 'text-green-600',
    bgColor: 'bg-green-50',
    ring: 'ring-green-200',
    icon: '🎉',
    label: '입장 가능!',
  },
  BOOKED: {
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    ring: 'ring-blue-200',
    icon: '🎫',
    label: '예매 완료',
  },
  NOT_IN_QUEUE: {
    color: 'text-gray-500',
    bgColor: 'bg-gray-50',
    ring: 'ring-gray-200',
    icon: '❌',
    label: '대기열 없음',
  },
}

/* ── 계속 회전하는 로더 ────────────────────────────── */
function QueueSpinner() {
  return (
    <div className="flex items-center justify-center">
      <div className="relative w-16 h-16 animate-[spin_1.8s_linear_infinite]">
        {Array.from({ length: 8 }).map((_, i) => {
          const angle = i * 45
          return (
            <span
              key={i}
              className="absolute left-1/2 top-1/2 block w-3 h-3 -ml-1.5 -mt-1.5 rounded-full"
              style={{
                transform: `rotate(${angle}deg) translateY(-24px)`,
                backgroundColor: `rgba(59, 130, 246, ${0.18 + i * 0.1})`,
              }}
            />
          )
        })}
      </div>
    </div>
  )
}

/* ── 메인 컴포넌트 ──────────────────────────────────── */
export default function QueuePage() {
  const { id: concertId } = useParams()
  const navigate = useNavigate()
  const { token } = useAuthStore()

  const [countdown, setCountdown] = useState(null)

  useEffect(() => {
    if (!token) {
      navigate('/login', { state: { from: `/concerts/${concertId}/queue` } })
    }
  }, [token, navigate, concertId])

  const handleAdmitted = useCallback(() => {
    setCountdown(3)
  }, [])

  const { data, loading, error } = useQueuePolling(concertId, {
    onAdmitted: handleAdmitted,
  })

  useEffect(() => {
    if (countdown === null) return

    if (countdown <= 0) {
      navigate(`/concerts/${concertId}/booking`, { replace: true })
      return
    }

    const tick = setTimeout(() => setCountdown((c) => c - 1), 1000)
    return () => clearTimeout(tick)
  }, [countdown, navigate, concertId])

  useEffect(() => {
    if (data?.status !== 'QUEUED') return

    const handler = (e) => {
      e.preventDefault()
      e.returnValue = ''
    }

    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [data?.status])

  if (loading) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (error && !data) {
    const isAuthError =
      error?.response?.status === 401 || error?.response?.status === 403

    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        {isAuthError ? (
          <>
            <p className="text-base font-semibold text-gray-700">
              인증이 만료되었습니다.
            </p>
            <p className="text-sm text-gray-400">다시 로그인해주세요.</p>
          </>
        ) : (
          <>
            <p className="text-base font-semibold text-gray-700">
              연결 오류가 발생했습니다.
            </p>
            <p className="text-sm text-gray-400">
              {error?.response
                ? '서버와 통신할 수 없습니다. 잠시 후 다시 시도해주세요.'
                : '네트워크 연결을 확인해주세요.'}
            </p>
            <Link
              to={`/concerts/${concertId}`}
              className="mt-2 px-6 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
            >
              공연 페이지로 돌아가기
            </Link>
          </>
        )}
      </div>
    )
  }

  const status = data?.status ?? 'NOT_IN_QUEUE'
  const cfg = STATUS_CONFIG[status] ?? STATUS_CONFIG.NOT_IN_QUEUE

  if (status === 'NOT_IN_QUEUE') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-24 h-24 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl`}
        >
          {cfg.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">대기열에 없습니다</p>
          <p className="text-sm text-gray-400 leading-relaxed">
            대기열이 만료되었거나 등록되지 않은 상태입니다.
            <br />
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

  if (status === 'BOOKED') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-24 h-24 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl`}
        >
          {cfg.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">
            이미 예매한 공연입니다
          </p>
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

  if (status === 'ADMITTED') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-28 h-28 rounded-full ring-4 ${cfg.ring} ${cfg.bgColor} flex items-center justify-center text-5xl animate-bounce`}
        >
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
          onClick={() =>
            navigate(`/concerts/${concertId}/booking`, { replace: true })
          }
          className="px-8 py-3.5 bg-green-500 hover:bg-green-600 text-white font-bold rounded-xl transition-colors shadow-md shadow-green-100"
        >
          지금 바로 예매하기
        </button>
      </div>
    )
  }

  const { position, total } = data
  const ahead = Math.max(0, position - 1)
  const behind = Math.max(0, (total ?? 0) - position)

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 flex flex-col items-center justify-center px-4 pb-20 md:pb-0">
      <div className="w-full max-w-sm">
        <Link
          to={`/concerts/${concertId}`}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-8"
        >
          ← 공연 페이지
        </Link>

        <div className="bg-white rounded-3xl border border-gray-100 shadow-sm p-8 text-center">
          <p className="text-xl font-bold text-gray-900">
            서비스 접속 대기 중입니다.
          </p>

          <div className="mt-8">
            <QueueSpinner />
          </div>

          <div className="mt-8">
            <p className="text-[56px] font-black text-gray-900 tabular-nums leading-none">
              {position.toLocaleString()}
            </p>
            <p className="mt-2 text-base text-gray-500">나의 대기순서</p>
          </div>

          <div className="mt-8 text-gray-800">
            <p className="text-[15px] font-medium leading-relaxed whitespace-nowrap">
              고객님 앞에{' '}
              <span className="font-extrabold text-blue-600">
                {ahead.toLocaleString()}
              </span>
              명, 뒤에{' '}
              <span className="font-extrabold text-orange-500">
                {behind.toLocaleString()}
              </span>
              명의 대기자가 있습니다.
            </p>

            <div className="mt-5 text-sm leading-relaxed text-gray-600 space-y-1">
              <p className="whitespace-nowrap">
                현재 접속 인원이 많아 대기 중입니다.
              </p>
              <p className="whitespace-nowrap">
                잠시만 기다리시면 예매하기 페이지로 자동 연결됩니다.
              </p>
            </div>

            <div className="mt-6 rounded-2xl bg-primary-50 px-4 py-4">
              <p className="text-sm leading-relaxed text-primary-700">
                새로고침 하거나 재접속하시면
                <br />
                대기순서가 초기화되어 대기시간이 더 길어집니다.
              </p>
            </div>
          </div>
        </div>

        <p className="mt-4 text-xs text-center text-gray-300">
          마지막 업데이트: {new Date().toLocaleTimeString('ko-KR')}
        </p>
      </div>
    </div>
  )
}