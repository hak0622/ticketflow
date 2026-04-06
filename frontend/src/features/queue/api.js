import axiosInstance from '../../shared/api/axios'

/** 대기 순번 조회 - GET /api/concerts/:id/queue/me */
export const getQueueStatus = (concertId) =>
  axiosInstance.get(`/concerts/${concertId}/queue/me`)
