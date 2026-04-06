import { useCallback, useEffect, useRef } from 'react'

export function useQueuePageState({ countdown, setCountdown, status, concertId, navigate }) {
  const countdownStartedRef = useRef(false)

  const startAdmittedCountdown = useCallback(() => {
    if (countdownStartedRef.current) return

    countdownStartedRef.current = true
    setCountdown(3)
  }, [setCountdown])

  useEffect(() => {
    if (countdown === null) return

    if (countdown <= 0) {
      navigate(`/concerts/${concertId}/booking`, { replace: true })
      return
    }

    const tick = setTimeout(() => setCountdown((c) => c - 1), 1000)
    return () => clearTimeout(tick)
  }, [countdown, setCountdown, navigate, concertId])

  useEffect(() => {
    if (countdown === null && status !== 'ADMITTED') {
      countdownStartedRef.current = false
    }
  }, [countdown, status])

  useEffect(() => {
    if (status !== 'QUEUED') return

    const handler = (e) => {
      e.preventDefault()
      e.returnValue = ''
    }

    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [status])

  return { startAdmittedCountdown }
}
