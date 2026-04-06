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

export function getQueueViewModel({ data, loading, error, countdown, concertId }) {
  const isAuthError =
    error?.response?.status === 401 || error?.response?.status === 403
  const hasErrorWithoutData = Boolean(error && !data)
  const status = data?.status ?? 'NOT_IN_QUEUE'
  const statusConfig = STATUS_CONFIG[status] ?? STATUS_CONFIG.NOT_IN_QUEUE
  const position = data?.position ?? 0
  const total = data?.total ?? 0
  const ahead = Math.max(0, position - 1)
  const behind = Math.max(0, total - position)

  let viewState = 'queued'
  if (loading) {
    viewState = 'loading'
  } else if (hasErrorWithoutData) {
    viewState = isAuthError ? 'auth_error' : 'network_error'
  } else if (status === 'NOT_IN_QUEUE') {
    viewState = 'not_in_queue'
  } else if (status === 'BOOKED') {
    viewState = 'booked'
  } else if (status === 'ADMITTED') {
    viewState = 'admitted'
  }

  const countdownText = countdown !== null
    ? `${countdown}초 후 예매 페이지로 이동합니다...`
    : '예매 페이지로 이동 중...'

  return {
    viewState,
    status,
    statusConfig,
    isAuthError,
    countdownText,
    queueMetrics: {
      position,
      total,
      ahead,
      behind,
    },
    messages: {
      title:
        viewState === 'not_in_queue'
          ? '대기열에 없습니다'
          : viewState === 'booked'
            ? '이미 예매한 공연입니다'
            : viewState === 'admitted'
              ? '입장 가능!'
              : '',
      description:
        viewState === 'not_in_queue'
          ? '대기열이 만료되었거나 등록되지 않은 상태입니다.'
          : viewState === 'booked'
            ? '내 예매 목록에서 확인하세요.'
            : viewState === 'auth_error'
              ? '다시 로그인해주세요.'
              : viewState === 'network_error'
                ? error?.response
                  ? '서버와 통신할 수 없습니다. 잠시 후 다시 시도해주세요.'
                  : '네트워크 연결을 확인해주세요.'
                : '',
      subDescription:
        viewState === 'not_in_queue' ? '다시 예매하기를 눌러주세요.' : '',
      notice:
        viewState === 'network_error' ? '연결 오류가 발생했습니다.' : '',
      errorTitle:
        viewState === 'auth_error' ? '인증이 만료되었습니다.' : '',
      errorDescription:
        viewState === 'auth_error' ? '다시 로그인해주세요.' : '',
      queueTitle: '서비스 접속 대기 중입니다.',
      queueHint: '나의 대기순서',
      queueLinePrefix: '고객님 앞에',
      queueLineMiddle: '명, 뒤에',
      queueLineSuffix: '명의 대기자가 있습니다.',
      queueNoticeLine1: '현재 접속 인원이 많아 대기 중입니다.',
      queueNoticeLine2: '잠시만 기다리시면 예매하기 페이지로 자동 연결됩니다.',
      queueWarningLine1: '새로고침 하거나 재접속하시면',
      queueWarningLine2: '대기순서가 초기화되어 대기시간이 더 길어집니다.',
      lastUpdatedLabel: '마지막 업데이트:',
    },
    actions: {
      primaryLabel:
        viewState === 'booked'
          ? '내 예매 확인'
          : viewState === 'admitted'
            ? '지금 바로 예매하기'
            : null,
      primaryTo:
        viewState === 'booked'
          ? '/my-bookings'
          : viewState === 'admitted'
            ? `/concerts/${concertId}/booking`
            : null,
      secondaryLabel:
        viewState === 'booked'
          ? '홈으로'
          : null,
      secondaryTo:
        viewState === 'booked'
          ? '/'
          : null,
      backToConcert: `/concerts/${concertId}`,
      backToConcertLabel: '공연 페이지로 돌아가기',
      showPrimary: viewState === 'booked' || viewState === 'admitted',
      showSecondary: viewState === 'booked',
    },
  }
}
