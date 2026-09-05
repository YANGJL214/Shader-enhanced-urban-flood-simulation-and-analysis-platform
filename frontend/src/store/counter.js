/**
 * @Author: ZC
 * @Description:
 * @Date: 2025/8/6 10:21
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
// import { MapmostAPI } from '@/views/onemap/preview-map/MapMostLib/MapMostAPI'
import { CesiumAPI } from '@/views/onemap/preview-map/CesiumLib/CesiumAPI'

export const useAppStore = defineStore('app', () => {
  let _instance = null
  let mapResolve = null
  let showAgent = ref(false)

  const initMap = elementId => (_instance = new CesiumAPI(elementId, mapResolve))
  const mapPromise = new Promise(resolve => (mapResolve = resolve))
  const mapAPI = () => _instance

  const controlAgent = isShow => {
    showAgent.value = isShow
  }

  return { initMap, mapAPI, mapPromise, showAgent, controlAgent }
})
