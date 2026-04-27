import axiosInstance from '../../shared/api/axios'

export const getAdminConcerts = () =>
  axiosInstance.get('/admin/concerts')

export const getAdminConcertBookings = (id) =>
  axiosInstance.get(`/admin/concerts/${id}/bookings`)

export const createAdminConcert = (data) =>
  axiosInstance.post('/admin/concerts', data)

export const updateAdminConcert = (id, data) =>
  axiosInstance.put(`/admin/concerts/${id}`, data)

export const closeAdminConcert = (id) =>
  axiosInstance.patch(`/admin/concerts/${id}/close`)

export const deleteAdminConcert = (id) =>
  axiosInstance.delete(`/admin/concerts/${id}`)
