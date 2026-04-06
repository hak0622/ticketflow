import { useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQueuePolling } from '../features/queue/hooks'
import { useQueuePageState } from '../features/queue/page-state'
import { getQueueViewModel } from '../features/queue/view-model'
import useAuthStore from '../features/auth/store'
import LoadingSpinner from '../shared/ui/LoadingSpinner'

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

  const handleAdmitted = () => {
    startAdmittedCountdown()
  }

  const { data, loading, error } = useQueuePolling(concertId, {
    onAdmitted: handleAdmitted,
  })

  const { startAdmittedCountdown } = useQueuePageState({
    countdown,
    setCountdown,
    status: data?.status,
    concertId,
    navigate,
  })

  const vm = getQueueViewModel({
    data,
    loading,
    error,
    countdown,
    concertId,
  })

  if (vm.viewState === 'loading') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (vm.viewState === 'auth_error' || vm.viewState === 'network_error') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4 px-4 text-center">
        <span className="text-5xl">⚠️</span>
        {vm.isAuthError ? (
          <>
            <p className="text-base font-semibold text-gray-700">
              {vm.messages.errorTitle}
            </p>
            <p className="text-sm text-gray-400">{vm.messages.errorDescription}</p>
          </>
        ) : (
          <>
            <p className="text-base font-semibold text-gray-700">
              {vm.messages.notice}
            </p>
            <p className="text-sm text-gray-400">
              {vm.messages.description}
            </p>
            <Link
              to={vm.actions.backToConcert}
              className="mt-2 px-6 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
            >
              {vm.actions.backToConcertLabel}
            </Link>
          </>
        )}
      </div>
    )
  }

  if (vm.viewState === 'not_in_queue') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-24 h-24 rounded-full ring-4 ${vm.statusConfig.ring} ${vm.statusConfig.bgColor} flex items-center justify-center text-5xl`}
        >
          {vm.statusConfig.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">{vm.messages.title}</p>
          <p className="text-sm text-gray-400 leading-relaxed">
            {vm.messages.description}
            <br />
            {vm.messages.subDescription}
          </p>
        </div>
        <Link
          to={vm.actions.backToConcert}
          className="mt-2 px-6 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
        >
          {vm.actions.backToConcertLabel}
        </Link>
      </div>
    )
  }

  if (vm.viewState === 'booked') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-24 h-24 rounded-full ring-4 ${vm.statusConfig.ring} ${vm.statusConfig.bgColor} flex items-center justify-center text-5xl`}
        >
          {vm.statusConfig.icon}
        </div>
        <div>
          <p className="text-xl font-bold text-gray-800 mb-2">
            {vm.messages.title}
          </p>
          <p className="text-sm text-gray-400">{vm.messages.description}</p>
        </div>
        <div className="flex gap-3">
          <Link
            to={vm.actions.primaryTo}
            className="px-5 py-3 bg-primary-500 hover:bg-primary-600 text-white font-semibold rounded-xl transition-colors"
          >
            {vm.actions.primaryLabel}
          </Link>
          <Link
            to={vm.actions.secondaryTo}
            className="px-5 py-3 border-2 border-gray-200 hover:border-primary-300 text-gray-600 font-semibold rounded-xl transition-colors"
          >
            {vm.actions.secondaryLabel}
          </Link>
        </div>
      </div>
    )
  }

  if (vm.viewState === 'admitted') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div
          className={`w-28 h-28 rounded-full ring-4 ${vm.statusConfig.ring} ${vm.statusConfig.bgColor} flex items-center justify-center text-5xl animate-bounce`}
        >
          {vm.statusConfig.icon}
        </div>
        <div>
          <p className="text-2xl font-black text-green-600 mb-2">{vm.messages.title}</p>
          <p className="text-sm text-gray-500">{vm.countdownText}</p>
        </div>
        <button
          onClick={() => navigate(vm.actions.primaryTo, { replace: true })}
          className="px-8 py-3.5 bg-green-500 hover:bg-green-600 text-white font-bold rounded-xl transition-colors shadow-md shadow-green-100"
        >
          {vm.actions.primaryLabel}
        </button>
      </div>
    )
  }

  return (
    <div className="min-h-[calc(100svh-56px)] bg-gray-50 flex flex-col items-center justify-center px-4 pb-20 md:pb-0">
      <div className="w-full max-w-sm">
        <Link
          to={vm.actions.backToConcert}
          className="flex items-center gap-1 text-sm text-gray-400 hover:text-gray-600 transition-colors mb-8"
        >
          ← 공연 페이지
        </Link>

        <div className="bg-white rounded-3xl border border-gray-100 shadow-sm p-8 text-center">
          <p className="text-xl font-bold text-gray-900">
            {vm.messages.queueTitle}
          </p>

          <div className="mt-8">
            <QueueSpinner />
          </div>

          <div className="mt-8">
            <p className="text-[56px] font-black text-gray-900 tabular-nums leading-none">
              {vm.queueMetrics.position.toLocaleString()}
            </p>
            <p className="mt-2 text-base text-gray-500">{vm.messages.queueHint}</p>
          </div>

          <div className="mt-8 text-gray-800">
            <p className="text-[15px] font-medium leading-relaxed whitespace-nowrap">
              {vm.messages.queueLinePrefix}{' '}
              <span className="font-extrabold text-blue-600">
                {vm.queueMetrics.ahead.toLocaleString()}
              </span>
              {vm.messages.queueLineMiddle}{' '}
              <span className="font-extrabold text-orange-500">
                {vm.queueMetrics.behind.toLocaleString()}
              </span>
              {vm.messages.queueLineSuffix}
            </p>

            <div className="mt-5 text-sm leading-relaxed text-gray-600 space-y-1">
              <p className="whitespace-nowrap">
                {vm.messages.queueNoticeLine1}
              </p>
              <p className="whitespace-nowrap">
                {vm.messages.queueNoticeLine2}
              </p>
            </div>

            <div className="mt-6 rounded-2xl bg-primary-50 px-4 py-4">
              <p className="text-sm leading-relaxed text-primary-700">
                {vm.messages.queueWarningLine1}
                <br />
                {vm.messages.queueWarningLine2}
              </p>
            </div>
          </div>
        </div>

        <p className="mt-4 text-xs text-center text-gray-300">
          {vm.messages.lastUpdatedLabel} {new Date().toLocaleTimeString('ko-KR')}
        </p>
      </div>
    </div>
  )
}
