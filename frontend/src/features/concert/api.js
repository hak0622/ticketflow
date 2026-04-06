import axiosInstance from '../../shared/api/axios'

export const getConcerts = () => axiosInstance.get('/concerts')

export const getConcert = (id) => axiosInstance.get(`/concerts/${id}`)
