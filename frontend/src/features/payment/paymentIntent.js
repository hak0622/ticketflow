const storageKey = orderId => `payment-intent:${orderId}`

export function rememberPaymentIntent({ orderId, concertId, amount, userId }) {
  const existing = readPaymentIntent(orderId)
  if (existing?.attempted) throw new Error('기존 주문의 결제 결과를 먼저 확인해 주세요.')
  sessionStorage.setItem(storageKey(orderId), JSON.stringify({
    concertId: String(concertId), amount, userId, attempted: false,
  }))
}

export function readPaymentIntent(orderId) {
  try {
    return JSON.parse(sessionStorage.getItem(storageKey(orderId)))
  } catch {
    return null
  }
}

export function claimPaymentApproval(orderId, intent, { concertId, amount, user }) {
  if (!intent || intent.attempted || intent.userId !== user ||
      intent.concertId !== String(concertId) || intent.amount !== amount) return false
  try {
    sessionStorage.setItem(storageKey(orderId), JSON.stringify({ ...intent, attempted: true }))
    return true
  } catch {
    return false
  }
}

export function paymentLookupPath(params) {
  const lookup = new URLSearchParams({ mode: 'lookup' })
  for (const key of ['concertId', 'orderId', 'amount']) {
    const value = params.get(key)
    if (value !== null) lookup.set(key, value)
  }
  return '/payment/success?' + lookup
}
