import axiosInstance from '../../shared/api/axios'

export const getConcerts = (genre) =>
  axiosInstance.get('/concerts', {
    params: genre ? { genre } : {},
  })

export const getConcert = (id) => axiosInstance.get(`/concerts/${id}`)
