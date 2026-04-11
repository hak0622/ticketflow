import axiosInstance from '../../shared/api/axios'

export const getConcerts = (params = {}) =>
  axiosInstance.get('/concerts', {
    params,
  })

export const getConcert = (id) => axiosInstance.get(`/concerts/${id}`)
