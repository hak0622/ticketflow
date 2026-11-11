import axiosInstance from '../../shared/api/axios'

/** 대기열 등록 - POST /api/concerts/:id/queue */
export const registerQueue = (concertId) =>
  axiosInstance.post(`/concerts/${concertId}/queue`)

/** 예매 생성 - POST /api/concerts/:id/booking */
export const createBooking = (concertId, data, requestId) =>
  axiosInstance.post(`/concerts/${concertId}/booking`, data, {
    headers: requestId ? { 'Idempotency-Key': requestId } : {},
  })

/** 예매 상태 조회 - GET /api/concerts/:id/booking/me */
export const getBookingStatus = (concertId) =>
  axiosInstance.get(`/concerts/${concertId}/booking/me`)

/** 예매 상세 - GET /api/concerts/:id/booking/detail */
export const getBookingDetail = (concertId) =>
  axiosInstance.get(`/concerts/${concertId}/booking/detail`)

/** 결제 - POST /api/concerts/:id/payment */
export const pay = (concertId, data) =>
  axiosInstance.post(`/concerts/${concertId}/payment`, data)

/** 내 예매 목록 - GET /api/me/bookings */
export const getMyBookings = () =>
  axiosInstance.get('/me/bookings')

/** Toss 결제 승인 - POST /api/concerts/:id/payment/toss-confirm */
export const tossConfirm = (concertId, data) =>
  axiosInstance.post(`/concerts/${concertId}/payment/toss-confirm`, data)

/** 결제 결과 조회 */
export const getMyPayment = (concertId) => axiosInstance.get(`/concerts/${concertId}/payment/me`)

export const getBookingAttempt = (concertId, requestId) =>
  axiosInstance.get(`/concerts/${concertId}/booking/attempts/${requestId}`)
