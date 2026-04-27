import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import useAuthStore from '../features/auth/store'
import {
  getAdminConcerts,
  getAdminConcertBookings,
  createAdminConcert,
  updateAdminConcert,
  closeAdminConcert,
  deleteAdminConcert,
} from '../features/admin/api'
import LoadingSpinner from '../shared/ui/LoadingSpinner'

const STATUS_LABEL = { OPEN: '예매가능', SOLD_OUT: '매진', CLOSED: '종료' }
const STATUS_CLS = {
  OPEN: 'bg-blue-100 text-blue-700',
  SOLD_OUT: 'bg-red-100 text-red-600',
  CLOSED: 'bg-gray-100 text-gray-500',
}
const BOOKING_STATUS_LABEL = {
  CONFIRMED: '확정',
  PENDING_PAYMENT: '결제대기',
  CANCELLED: '취소',
}

const EMPTY_FORM = {
  title: '', artist: '', venue: '', genre: '',
  eventAt: '', bookingOpenAt: '',
  totalSeats: '', price: '', discountRate: '',
  posterUrl: '', zone: '', status: 'OPEN',
}

function toInputDatetime(isoStr) {
  if (!isoStr) return ''
  return isoStr.slice(0, 16)
}

function toLocalDatetime(inputVal) {
  if (!inputVal) return null
  return inputVal + ':00'
}

function formatDate(str) {
  if (!str) return '-'
  return new Date(str).toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function Field({ label, children, required }) {
  return (
    <div>
      <label className="block text-xs font-semibold text-gray-500 mb-1">
        {label}{required && <span className="text-red-500 ml-0.5">*</span>}
      </label>
      {children}
    </div>
  )
}

const inputCls = 'w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-300'

function ConcertFormModal({ initial, onClose, onSaved }) {
  const [form, setForm] = useState(
    initial
      ? {
          ...initial,
          eventAt: toInputDatetime(initial.eventAt),
          bookingOpenAt: toInputDatetime(initial.bookingOpenAt),
          totalSeats: initial.totalSeats ?? '',
          price: initial.price ?? '',
          discountRate: initial.discountRate ?? '',
        }
      : EMPTY_FORM
  )
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)
  const isEdit = !!initial

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  const handleSubmit = async (e) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    const payload = {
      ...form,
      totalSeats: Number(form.totalSeats),
      price: form.price !== '' ? Number(form.price) : null,
      discountRate: form.discountRate !== '' ? Number(form.discountRate) : null,
      eventAt: toLocalDatetime(form.eventAt),
      bookingOpenAt: toLocalDatetime(form.bookingOpenAt),
    }
    try {
      if (isEdit) await updateAdminConcert(initial.id, payload)
      else await createAdminConcert(payload)
      onSaved()
    } catch (err) {
      setError(err.response?.data?.message || '저장 실패')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 className="text-base font-black text-gray-900">{isEdit ? '공연 수정' : '공연 생성'}</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-xl leading-none">×</button>
        </div>

        <form onSubmit={handleSubmit} className="px-6 py-5 space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <Field label="공연명" required>
              <input className={inputCls} value={form.title} onChange={set('title')} required />
            </Field>
            <Field label="아티스트">
              <input className={inputCls} value={form.artist} onChange={set('artist')} />
            </Field>
            <Field label="장소">
              <input className={inputCls} value={form.venue} onChange={set('venue')} />
            </Field>
            <Field label="장르">
              <input className={inputCls} value={form.genre} onChange={set('genre')} />
            </Field>
            <Field label="공연 일시" required>
              <input type="datetime-local" className={inputCls} value={form.eventAt} onChange={set('eventAt')} required />
            </Field>
            <Field label="예매 오픈 일시">
              <input type="datetime-local" className={inputCls} value={form.bookingOpenAt} onChange={set('bookingOpenAt')} />
            </Field>
            <Field label="총 좌석수" required>
              <input type="number" min="1" className={inputCls} value={form.totalSeats} onChange={set('totalSeats')} required />
            </Field>
            <Field label="가격 (원)">
              <input type="number" min="0" className={inputCls} value={form.price} onChange={set('price')} />
            </Field>
            <Field label="할인율 (0-100)">
              <input type="number" min="0" max="100" className={inputCls} value={form.discountRate} onChange={set('discountRate')} />
            </Field>
            <Field label="구역 (Zone)">
              <input className={inputCls} value={form.zone} onChange={set('zone')} />
            </Field>
          </div>

          <Field label="포스터 URL">
            <input className={inputCls} value={form.posterUrl} onChange={set('posterUrl')} placeholder="https://..." />
          </Field>

          {isEdit && (
            <Field label="상태">
              <select className={inputCls} value={form.status} onChange={set('status')}>
                <option value="OPEN">예매가능</option>
                <option value="SOLD_OUT">매진</option>
                <option value="CLOSED">종료</option>
              </select>
            </Field>
          )}

          {error && <p className="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{error}</p>}

          <div className="flex gap-2 pt-2">
            <button type="button" onClick={onClose}
              className="flex-1 py-2.5 rounded-xl border border-gray-200 text-sm font-semibold text-gray-600 hover:bg-gray-50">
              취소
            </button>
            <button type="submit" disabled={submitting}
              className="flex-1 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-700 disabled:opacity-60 text-white text-sm font-semibold">
              {submitting ? '저장 중...' : '저장'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function BookingsModal({ concert, onClose }) {
  const [bookings, setBookings] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getAdminConcertBookings(concert.id)
      .then((r) => setBookings(r.data))
      .finally(() => setLoading(false))
  }, [concert.id])

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 shrink-0">
          <h2 className="text-base font-black text-gray-900">{concert.title} — 예매 목록</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-xl leading-none">×</button>
        </div>

        <div className="overflow-y-auto flex-1">
          {loading ? (
            <div className="flex justify-center py-12"><LoadingSpinner /></div>
          ) : bookings.length === 0 ? (
            <p className="text-center text-sm text-gray-400 py-12">예매 내역이 없습니다.</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                <tr>
                  <th className="px-4 py-3 text-left">예매 ID</th>
                  <th className="px-4 py-3 text-left">이메일</th>
                  <th className="px-4 py-3 text-left">닉네임</th>
                  <th className="px-4 py-3 text-left">상태</th>
                  <th className="px-4 py-3 text-left">예매일시</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {bookings.map((b) => (
                  <tr key={b.bookingId} className="hover:bg-gray-50">
                    <td className="px-4 py-3 text-gray-700">#{b.bookingId}</td>
                    <td className="px-4 py-3 text-gray-500">{b.email}</td>
                    <td className="px-4 py-3 text-gray-500">{b.nickname}</td>
                    <td className="px-4 py-3">
                      <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">
                        {BOOKING_STATUS_LABEL[b.status] ?? b.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500">{formatDate(b.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}

export default function AdminConcertPage() {
  const navigate = useNavigate()
  const { token, user } = useAuthStore()

  const [concerts, setConcerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [formTarget, setFormTarget] = useState(null) // null=닫힘, {}=생성, concert=수정
  const [bookingTarget, setBookingTarget] = useState(null)

  useEffect(() => {
    if (!token) { navigate('/login'); return }
    if (user?.role !== 'ADMIN') { navigate('/'); return }
  }, [token, user, navigate])

  const load = () => {
    setLoading(true)
    getAdminConcerts()
      .then((r) => setConcerts(r.data))
      .catch(() => setError('목록 조회 실패'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { if (token && user?.role === 'ADMIN') load() }, [token, user])

  const handleClose = async (id) => {
    if (!window.confirm('공연을 마감하시겠습니까?')) return
    await closeAdminConcert(id)
    load()
  }

  const handleDelete = async (id) => {
    if (!window.confirm('공연을 삭제하시겠습니까? 관련 예매도 모두 삭제됩니다.')) return
    await deleteAdminConcert(id)
    load()
  }

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center text-red-500">{error}</div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 py-8">
        <div className="flex items-center justify-between mb-6">
          <div>
            <p className="text-xs font-bold text-gray-400 uppercase tracking-widest mb-1">Admin</p>
            <h1 className="text-2xl font-black text-gray-900">공연 관리</h1>
          </div>
          <button
            onClick={() => setFormTarget({})}
            className="px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white text-sm font-semibold rounded-xl transition-colors"
          >
            + 공연 생성
          </button>
        </div>

        {concerts.length === 0 ? (
          <div className="text-center py-24 text-gray-400">등록된 공연이 없습니다.</div>
        ) : (
          <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                <tr>
                  <th className="px-4 py-3 text-left">공연명</th>
                  <th className="px-4 py-3 text-left">아티스트</th>
                  <th className="px-4 py-3 text-left">공연일시</th>
                  <th className="px-4 py-3 text-center">좌석</th>
                  <th className="px-4 py-3 text-center">상태</th>
                  <th className="px-4 py-3 text-center">작업</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {concerts.map((c) => (
                  <tr key={c.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-semibold text-gray-800 max-w-[200px] truncate">{c.title}</td>
                    <td className="px-4 py-3 text-gray-500">{c.artist || '-'}</td>
                    <td className="px-4 py-3 text-gray-500 whitespace-nowrap">{formatDate(c.eventAt)}</td>
                    <td className="px-4 py-3 text-center text-gray-600">{c.totalSeats}</td>
                    <td className="px-4 py-3 text-center">
                      <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${STATUS_CLS[c.status] ?? STATUS_CLS.CLOSED}`}>
                        {STATUS_LABEL[c.status] ?? c.status}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-center gap-1.5 flex-wrap">
                        <button onClick={() => setBookingTarget(c)}
                          className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-600">
                          예매조회
                        </button>
                        <button onClick={() => setFormTarget(c)}
                          className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-blue-50 hover:bg-blue-100 text-blue-700">
                          수정
                        </button>
                        {c.status === 'OPEN' && (
                          <button onClick={() => handleClose(c.id)}
                            className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-yellow-50 hover:bg-yellow-100 text-yellow-700">
                            마감
                          </button>
                        )}
                        <button onClick={() => handleDelete(c.id)}
                          className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-red-50 hover:bg-red-100 text-red-600">
                          삭제
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {formTarget !== null && (
        <ConcertFormModal
          initial={formTarget.id ? formTarget : null}
          onClose={() => setFormTarget(null)}
          onSaved={() => { setFormTarget(null); load() }}
        />
      )}

      {bookingTarget && (
        <BookingsModal concert={bookingTarget} onClose={() => setBookingTarget(null)} />
      )}
    </div>
  )
}
