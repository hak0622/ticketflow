import { useEffect } from 'react'
import { useSearchParams, useNavigate, Link } from 'react-router-dom'
import useAuthStore from '../features/auth/store'
import { usePaymentRecovery } from '../features/payment/usePaymentRecovery'
import { paymentLookupPath } from '../features/payment/paymentIntent'

export default function PaymentSuccessPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { token, user } = useAuthStore()
  const authenticated = Boolean(token)
  const result = usePaymentRecovery({ concertId: params.get('concertId'), orderId: params.get('orderId'),
    paymentKey: params.get('paymentKey'), amountText: params.get('amount'), authenticated, user: user?.id,
    lookupOnly: params.get('mode') === 'lookup' })
  useEffect(() => {
    if (!authenticated) navigate('/login', { replace: true, state: { from: paymentLookupPath(params) } })
  }, [authenticated, navigate, params])
  const { payment, booking, checking, paused, reason, lastUpdatedAt, resume } = result
  let title = '결제 상태를 확인하고 있습니다.'
  if (payment?.status === 'COMPLETED') title = '결제 완료! 예매가 확정되었습니다.'
  else if (payment?.status === 'CANCELED') title = '결제 취소가 확인되었습니다.'
  else if (payment?.status === 'FAILED') title = '결제 실패가 확인되었습니다.'
  else if (payment?.status === 'PENDING') title = ['CANCEL_REQUIRED','CANCEL_CHECK'].includes(payment.recoveryPhase)
    ? '결제 취소 결과를 확인 중입니다.' : ['APPROVAL_CHECK','FINALIZE'].includes(payment.recoveryPhase)
      ? '결제 승인 결과를 확인 중입니다.' : '결제 결과를 확인 중입니다.'
  else if (paused || reason) title = '결제 결과 확인이 필요합니다.'
  const messages = {
    network: '연결 오류로 결과를 확인하지 못했습니다.', absent: '결제 접수 여부를 확인하지 못했습니다.',
    deadline: '자동 확인 시간이 끝났습니다. 서버의 복구 종료나 티켓 만료를 뜻하지 않습니다.',
    mismatch: '현재 주문과 응답이 일치하지 않거나 상태를 확인할 수 없습니다.',
    invalid: '결제 요청 정보가 올바르지 않습니다.', request: '요청을 확인할 수 없습니다.',
    auth: '다시 로그인해주세요.', forbidden: '결제 조회 권한이 없습니다.', identity: '로그인 정보가 변경되어 처리를 중단했습니다.',
  }
  const blocked = ['auth','forbidden','identity','invalid','request'].includes(reason)
  const afterPayment = ['CANCELED','FAILED'].includes(payment?.status)
  return (
    <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 pb-24 text-center">
      <h1 className="text-xl font-bold" aria-live="polite">{title}</h1>
      {afterPayment && <p>{booking?.status === 'CANCELLED' ? '예매 취소가 확인되었습니다.'
        : reason ? '예매 후처리 상태는 아직 확인하지 못했습니다.' : '예매 취소 처리가 진행 중입니다.'}</p>}
      {reason && <p role="status">{messages[reason]}</p>}
      {paused && <p>자동 확인을 중단했습니다.</p>}
      {lastUpdatedAt && <p>마지막 확인: {new Date(lastUpdatedAt).toLocaleTimeString('ko-KR')}</p>}
      {(paused || checking) && !blocked && <button onClick={resume} disabled={!paused || checking}
        className="rounded-xl bg-primary-500 px-6 py-3 text-white disabled:opacity-50">
        {checking ? '확인 중...' : afterPayment ? '예매 취소 상태 다시 확인' : '결제 상태 다시 확인'}
      </button>}
      <Link to="/my-bookings">내 예매 상태 확인</Link>
      <Link to="/">홈으로</Link>
    </div>
  )
}
