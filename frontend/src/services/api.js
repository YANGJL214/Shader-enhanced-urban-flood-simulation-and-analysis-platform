import axios from 'axios'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 120000
})

client.interceptors.response.use(
  response => response,
  error => Promise.reject(error)
)

const call = (method, url) => async ({ data = {} } = {}) => {
  const config = { method, url }
  if (method === 'get' || method === 'delete') config.params = data
  else config.data = data
  const response = await client.request(config)
  return response.data
}

const API = {
  publicFloodRegionListInitialized: call('get', '/public/flood_region/list/initialized'),
  publicFloodRegionList: call('get', '/public/flood_region/list'),
  publicFloodRegionInitialize: call('post', '/public/flood_region/initialize'),
  publicFloodRegionDelete: call('delete', '/public/flood_region/delete'),
  publicFloodRegionSimulate: call('post', '/public/flood_region/simulate'),
  publicSimulationHistory: call('post', '/public/simulation/history/list'),
  publicSimulationHistoryDetail: call('get', '/public/simulation/history/detail'),
  publicSimulationHistoryStop: call('get', '/public/simulation/history/stop'),
  publicUrbanFloodLoadHistory: call('get', '/public/urban_flood/load_history_result')
}

API.floodRegionList = API.publicFloodRegionListInitialized
API.floodRegionInitialize = API.publicFloodRegionInitialize
API.floodRegionDelete = API.publicFloodRegionDelete
API.floodRegionSimulate = API.publicFloodRegionSimulate
API.simulationHistory = API.publicSimulationHistory
API.simulationHistoryDetail = API.publicSimulationHistoryDetail
API.simulationHistoryStop = API.publicSimulationHistoryStop
API.urbanFloodLoadHistory = API.publicUrbanFloodLoadHistory

export default API
