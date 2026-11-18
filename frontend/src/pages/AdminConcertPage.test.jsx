import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import AdminConcertPage from './AdminConcertPage'
import {
  getAdminConcerts, getAdminConcertBookings, createAdminConcert,
  updateAdminConcert, closeAdminConcert, deleteAdminConcert,
} from '../features/admin/api'

vi.mock('../features/admin/api', () => ({
  getAdminConcerts: vi.fn(), getAdminConcertBookings: vi.fn(), createAdminConcert: vi.fn(),
  updateAdminConcert: vi.fn(), closeAdminConcert: vi.fn(), deleteAdminConcert: vi.fn(),
}))
const auth = vi.hoisted(() => ({ token: 'test-only', user: { role: 'ADMIN' } }))
vi.mock('../features/auth/store', () => ({ default: () => auth }))
const concert = {
  id: 7, title: '기존 공연', totalSeats: 100, price: 1000, discountRate: null,
  eventAt: '2030-01-01T12:00:00', bookingOpenAt: null, status: 'OPEN',
}
async function page() {
  render(<MemoryRouter><AdminConcertPage /></MemoryRouter>)
  await screen.findByText('기존 공연')
}
beforeEach(() => {
  vi.clearAllMocks()
  getAdminConcerts.mockResolvedValue({ data: [concert] })
  getAdminConcertBookings.mockResolvedValue({ data: [] })
  createAdminConcert.mockResolvedValue({})
  updateAdminConcert.mockResolvedValue({})
  closeAdminConcert.mockResolvedValue({})
  deleteAdminConcert.mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

it('creation keeps the quantity editable and sends the entered amount', async () => {
  await page()
  fireEvent.click(screen.getByRole('button', { name: /공연 생성/ }))
  const quantity = screen.getByRole('spinbutton', { name: '총 좌석수' })
  expect(quantity.readOnly).toBe(false)
  const form = quantity.closest('form')
  fireEvent.change(form.querySelector('input'), { target: { value: '신규 공연' } })
  fireEvent.change(form.querySelector('input[type="datetime-local"]'), { target: { value: '2030-01-01T12:00' } })
  fireEvent.change(quantity, { target: { value: '250' } })
  fireEvent.submit(form)
  await waitFor(() => expect(createAdminConcert).toHaveBeenCalledWith(expect.objectContaining({ title: '신규 공연', totalSeats: 250 })))
  expect(updateAdminConcert).not.toHaveBeenCalled()
})

it('edit submits the original quantity even if a synthetic event bypasses read-only', async () => {
  await page()
  fireEvent.click(screen.getByRole('button', { name: '수정' }))
  const quantity = screen.getByRole('spinbutton', { name: '총 좌석수' })
  expect(quantity.readOnly).toBe(true)
  expect(screen.getByText('총 티켓 수량은 생성 후 변경할 수 없습니다.')).toBeTruthy()
  fireEvent.change(quantity, { target: { value: '999' } })
  fireEvent.change(screen.getByDisplayValue('기존 공연'), { target: { value: '새 제목' } })
  fireEvent.submit(quantity.closest('form'))
  await waitFor(() => expect(updateAdminConcert).toHaveBeenCalledWith(7, expect.objectContaining({ title: '새 제목', totalSeats: 100 })))
})

it.each([
  { status: 409, data: { message: '생성된 공연의 총 티켓 수량은 변경할 수 없습니다.' } },
  { status: 400, data: { error: 'title은 필수입니다.' } },
])('save errors are shown without automatic resubmission ($status)', async (response) => {
  updateAdminConcert.mockRejectedValueOnce({ response })
  await page()
  fireEvent.click(screen.getByRole('button', { name: '수정' }))
  fireEvent.click(screen.getByRole('button', { name: '저장' }))
  await screen.findByRole('alert')
  expect(screen.getByRole('alert').textContent).toBe(response.data.message ?? response.data.error)
  expect(updateAdminConcert).toHaveBeenCalledTimes(1)
  expect(getAdminConcerts).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('spinbutton', { name: '총 좌석수' }).value).toBe('100')
  expect(screen.getByRole('button', { name: '저장' }).disabled).toBe(false)
})

it.each([
  ['마감', closeAdminConcert, '마감 작업 실패'],
  ['삭제', deleteAdminConcert, '예매 요청 기록이 있어 삭제할 수 없습니다.'],
])('%s failure keeps the list and allows retry', async (button, action, message) => {
  action.mockRejectedValueOnce({ response: { data: { message } } })
  await page()
  fireEvent.click(screen.getByRole('button', { name: button }))
  expect((await screen.findByRole('alert')).textContent).toBe(message)
  expect(screen.getByText('기존 공연')).toBeTruthy()
  expect(getAdminConcerts).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: button }).disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: button }))
  await waitFor(() => expect(getAdminConcerts).toHaveBeenCalledTimes(2))
  await screen.findByText('기존 공연')
  expect(action).toHaveBeenCalledTimes(2)
  expect(screen.queryByRole('alert')).toBeNull()
})

it('deletion confirmation describes the recovery-record restriction', async () => {
  await page()
  fireEvent.click(screen.getByRole('button', { name: '삭제' }))
  expect(window.confirm).toHaveBeenCalledWith('공연을 삭제하시겠습니까? 예매 요청 기록이 있는 공연은 삭제할 수 없습니다.')
  await waitFor(() => expect(getAdminConcerts).toHaveBeenCalledTimes(2))
})

it('a pending action cannot be submitted twice and failure releases the guard', async () => {
  let reject
  closeAdminConcert.mockImplementationOnce(() => new Promise((_, failure) => { reject = failure }))
  await page()
  const button = screen.getByRole('button', { name: '마감' })
  fireEvent.click(button)
  fireEvent.click(button)
  expect(closeAdminConcert).toHaveBeenCalledTimes(1)
  expect(button.disabled).toBe(true)
  reject(new Error('offline'))
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: '마감' }).disabled).toBe(false)
})
