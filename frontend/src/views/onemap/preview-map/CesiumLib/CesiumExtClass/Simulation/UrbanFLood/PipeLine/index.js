/* eslint-disable no-undef */
import { interpolateCesiumColor } from '../../Util'
import proj4 from 'proj4'

import PipeWater from '../PipeWater/index'

import vertexShader from './shaders/vertexShader.glsl?raw'
import fragmentShader from './shaders/fragmentShader.glsl?raw'

class PipeLine {
  constructor(options) {
    this._options = options
    this._viewer = options.viewer

    this.pipeJSON = options.pipeJSON

    if (!options.skipDefaultLoad) {
      this.init()
    }
  }

  static async parseSWMM(rawFile, zone) {
    let content
    if (typeof rawFile === 'string') {
      content = rawFile
    } else if (rawFile && typeof rawFile.text === 'function') {
      content = await rawFile.text()
    } else {
      throw new Error('无法解析INP: 不支持的输入类型')
    }

    // Parse INP topology first; coordinate projection is selected after reading all COORDINATES.
    const lines = content.split(/\r?\n/)

    let section = null
    const junctions = {}
    const inflows = {}
    const outfalls = {}
    const conduits = []
    const subcatchments = []
    const xsections = {}
    const coordinates = {}

    for (let line of lines) {
      line = line.trim()
      const commentIndex = line.indexOf(';')
      if (commentIndex >= 0) {
        line = line.slice(0, commentIndex).trim()
      }
      if (!line || line.startsWith(';')) continue

      if (line.startsWith('[')) {
        section = line.toUpperCase()
        continue
      }

      const parts = line.split(/\s+/)

      switch (section) {
        case '[JUNCTIONS]':
          junctions[parts[0]] = {
            id: parts[0],
            elevation: parseFloat(parts[1] || 0),
            maxDepth: parseFloat(parts[2] || 0),
            initDepth: parseFloat(parts[3] || 0),
            surDepth: parseFloat(parts[4] || 0),
            aponded: parseFloat(parts[5] || 0),
            x: null,
            y: null
          }
          break

        case '[INFLOWS]':
          // Node Constituent TimeSeries Type Mfactor Sfactor Baseline Pattern
          inflows[parts[0]] = {
            node: parts[0],
            constituent: parts[1] || null,
            timeSeries: parts[2] || null,
            type: parts[3] || null,
            mFactor: parseFloat(parts[4] || 1),
            sFactor: parseFloat(parts[5] || 1),
            baseline: parseFloat(parts[6] || 0),
            pattern: parts[7] || null
          }
          break

        case '[OUTFALLS]':
          outfalls[parts[0]] = {
            id: parts[0],
            elevation: parseFloat(parts[1] || 0),
            type: parts[2] || 'FREE',
            stageData: parts[3] || null,
            gated: parts[4] || null,
            x: null,
            y: null
          }
          break

        case '[CONDUITS]':
          conduits.push({
            id: parts[0],
            from: parts[1],
            to: parts[2],
            length: parseFloat(parts[3] || 0),
            roughness: parseFloat(parts[4] || 0),
            offsetIn: parseFloat(parts[5] || 0),
            offsetOut: parseFloat(parts[6] || 0),
            initFlow: parseFloat(parts[7] || 0),
            maxFlow: parseFloat(parts[8] || 0),
            xsection: null
          })
          break

        case '[XSECTIONS]':
          xsections[parts[0]] = {
            shape: parts[1],
            geom1: parseFloat(parts[2] || 0),
            geom2: parseFloat(parts[3] || 0),
            geom3: parseFloat(parts[4] || 0),
            geom4: parseFloat(parts[5] || 0),
            barrels: parseInt(parts[6] || 1, 10)
          }
          break

        case '[COORDINATES]':
          coordinates[parts[0]] = {
            x: parseFloat(parts[1] || 0),
            y: parseFloat(parts[2] || 0)
          }
          break

        case '[SUBCATCHMENTS]':
          // SUBCATCHMENTS: ID RainGage Outlet Area% ...
          subcatchments.push({
            id: parts[0],
            rainGage: parts[1],
            outlet: parts[2],
            area: parseFloat(parts[3] || 0),
            pctImperv: parseFloat(parts[4] || 0),
            width: parseFloat(parts[5] || 0),
            slope: parseFloat(parts[6] || 0),
            curbLen: parseFloat(parts[7] || 0),
            snowPack: parts[8] || null
          })

          if (junctions[parts[2]]) {
            junctions[parts[2]].Outlet = true
          }
          break
      }
    }

    for (let conduit of conduits) {
      if (xsections[conduit.id]) {
        conduit.xsection = xsections[conduit.id]
      }
    }

    const coordinateProjector = createInpCoordinateProjector(coordinates, zone)

    for (let id in junctions) {
      if (coordinates[id]) {
        junctions[id].x = coordinates[id].x
        junctions[id].y = coordinates[id].y

        const { longitude, latitude } = coordinateProjector.project(junctions[id].x, junctions[id].y)
        // const { longitude, latitude } = mercatorToWGS84(junctions[id].x, junctions[id].y, zone)
        junctions[id].longitude = longitude
        junctions[id].latitude = latitude
      }
    }
    for (let id in inflows) {
      if (coordinates[id]) {
        inflows[id].x = coordinates[id].x
        inflows[id].y = coordinates[id].y

        const { longitude, latitude } = coordinateProjector.project(inflows[id].x, inflows[id].y)
        inflows[id].longitude = longitude
        inflows[id].latitude = latitude
      }
    }
    for (let id in outfalls) {
      if (coordinates[id]) {
        outfalls[id].x = coordinates[id].x
        outfalls[id].y = coordinates[id].y

        const { longitude, latitude } = coordinateProjector.project(outfalls[id].x, outfalls[id].y)
        // const { longitude, latitude } = mercatorToWGS84(outfalls[id].x, outfalls[id].y, zone)
        outfalls[id].longitude = longitude
        outfalls[id].latitude = latitude
      }
    }

    return { junctions, inflows, outfalls, conduits }
  }

  async init() {
    if (this._options.waterInfo) {
      this.waterInfo = this._options.waterInfo
    } else {
      this.waterInfo = await Cesium.Resource.fetchJson({
        url: import.meta.env.VITE_NGINX_URL + '/Simulation/Imgs.json'
      })
    }

    if (this._options.pipeInfo) {
      this.pipeInfo = this._options.pipeInfo
    } else {
      this.pipeInfo = await Cesium.Resource.fetchJson({
        url: import.meta.env.VITE_NGINX_URL + '/PipeLine/25.11.11/extracted_data_time_based.json'
      })
    }

    if (this.waterInfo) {
      await this.initWater()
    }

    if (this.pipeJSON && this.pipeInfo) {
      this.PipelineRendererIns = this._viewer.scene.primitives.add(
        new PipelineRenderer(this._viewer, { pipeJSON: this.pipeJSON, pipeInfo: this.pipeInfo })
      )
    }

    this._options.callback && this._options.callback()
  }
  async loadWaterData(waterData) {

    if (!waterData || !waterData.imageFrames || waterData.imageFrames.length === 0) {
      console.error('[PipeLine] 水体数据无效或为空')
      throw new Error('水体数据无效: imageFrames为空')
    }

    const imageFrames = waterData.imageFrames || []

    const imgs = imageFrames
      .map(
        frame => frame && (frame.base64_data || frame.base64 || frame.dataUrl?.replace(/^data:image\/png;base64,/, ''))
      )
      .filter(v => !!v)
    if (imgs.length === 0) {
      console.error('[PipeLine] 水体帧均无有效base64数据')
      throw new Error('水体数据无有效帧')
    }


    let centerCoord, gridWidth, gridHeight, grid_size, squard

    if (waterData.metadata && waterData.metadata.grid_info) {
      const gridInfoRaw = waterData.metadata.grid_info

      const parseRange = val => {
        if (Array.isArray(val)) return [parseFloat(val[0]), parseFloat(val[1])]
        if (typeof val === 'string') {
          const parts = val.replace(/\[|\]/g, '').split(',')
          return [parseFloat(parts[0]), parseFloat(parts[1])]
        }
        if (val && typeof val === 'object' && 'min' in val && 'max' in val) {
          return [parseFloat(val.min), parseFloat(val.max)]
        }
        return [0, 0]
      }

      const x_range = parseRange(gridInfoRaw.x_range)
      const y_range = parseRange(gridInfoRaw.y_range)

      const gridSizeVal = gridInfoRaw.grid_size
      if (typeof gridSizeVal === 'number') grid_size = gridSizeVal
      else grid_size = parseFloat(gridSizeVal?.toString().split(' ')[0] || 10)

      const rangexy = Math.max(x_range[1] - x_range[0], y_range[1] - y_range[0])
      squard = parseInt(rangexy / grid_size)
      if (!Number.isFinite(squard) || squard <= 0) {
        console.warn('[PipeLine] 计算网格尺寸失败, 使用默认参数')
        squard = 200
        grid_size = grid_size || 5
      }

      const placement = createWaterGridPlacement(
        gridInfoRaw,
        waterData?.crs ||
          waterData?.metadata?.crs ||
          waterData?.metadata?.epsg ||
          waterData?.metadata?.projection ||
          waterData?.metadata?.grid_info?.crs
      )
      centerCoord = placement.center
      gridWidth = placement.width
      gridHeight = placement.height
      grid_size = placement.cellSize
      squard = `${gridWidth}x${gridHeight}`
    } else {
      console.warn('[PipeLine] 未找到metadata,使用默认参数(江北区)')
      centerCoord = Cesium.Cartesian3.fromDegrees(118.68, 32.8)
      squard = 200
      gridWidth = 200
      gridHeight = 200
      grid_size = 5
    }

    const maxWaterDepth = getMaxWaterDepth(waterData)
    this.waterIns = new PipeWater({
      viewer: this._viewer,
      center: centerCoord,
      level: 13,
      width: gridWidth,
      height: gridHeight,
      cellSize: grid_size,
      waterHeightTexs: imgs,
      dataResolution: [gridWidth, gridHeight],
      maxWaterDepth
    })

    await this.waterIns.initBox()

    this.flyToSimulationArea(centerCoord)
  }
  async loadPipelineData(pipelineData) {

    if (!pipelineData) {
      console.error('[PipeLine] 管网数据为空')
      throw new Error('管网数据无效')
    }

    let pipeInfo = pipelineData.timeSeriesData || pipelineData.timeBasedData

    if (!pipeInfo || Object.keys(pipeInfo).length === 0) {
      console.warn('[PipeLine] 未找到timeSeriesData,尝试使用objectData')

      if (pipelineData.objectData || pipelineData.objectBasedData) {
        pipeInfo = this.convertObjectDataToTimeData(pipelineData.objectData || pipelineData.objectBasedData)
      } else {
        throw new Error('未找到有效的管网数据(timeSeriesData或objectData)')
      }
    }


    this.pipeInfo = pipeInfo

    if (this.PipelineRendererIns) {
      this.PipelineRendererIns.updatePipeInfo(pipeInfo)
    }
    else if (this.pipeJSON) {
      this.PipelineRendererIns = this._viewer.scene.primitives.add(
        new PipelineRenderer(this._viewer, {
          pipeJSON: this.pipeJSON,
          pipeInfo: pipeInfo
        })
      )
    } else {
      console.warn('[PipeLine] 缺少pipeJSON,无法创建管网渲染器')
    }

  }

  /**
   * objectData: {objId: {time: {Flow_rate, ...}}}
   * timeData: {time: {objId: {Flow_rate, ...}}}
   */
  convertObjectDataToTimeData(objectData) {
    const timeData = {}

    Object.keys(objectData).forEach(objId => {
      const objTimeSeries = objectData[objId]

      Object.keys(objTimeSeries).forEach(time => {
        if (!timeData[time]) {
          timeData[time] = {}
        }
        timeData[time][objId] = objTimeSeries[time]
      })
    })

    return timeData
  }
  flyToSimulationArea(center) {
    if (!center) {
      console.warn('[PipeLine] 无法飞行: 中心坐标为空')
      return
    }

    this._viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(
        Cesium.Cartographic.fromCartesian(center).longitude * (180 / Math.PI),
        Cesium.Cartographic.fromCartesian(center).latitude * (180 / Math.PI),
        5000
      ),
      duration: 2.0
    })
  }

  async initPipes() {
    const { junctions, outfalls, conduits } = this.pipeJSON

    const divideNum = 36

    function computeCircle(radius) {
      const positions = []
      for (let i = 0; i < divideNum; i++) {
        const radians = Cesium.Math.toRadians(i * (360 / divideNum))
        positions.push(new Cesium.Cartesian2(radius * Math.cos(radians), radius * Math.sin(radians)))
      }
      return positions
    }

    function computeRect(WH) {
      const positions = []
      positions.push(new Cesium.Cartesian2(WH.x * 0.5, WH.y * 0.5))
      positions.push(new Cesium.Cartesian2(WH.x * 0.5, WH.y * -0.5))
      positions.push(new Cesium.Cartesian2(WH.x * -0.5, WH.y * -0.5))
      positions.push(new Cesium.Cartesian2(WH.x * -0.5, WH.y * 0.5))
      return positions
    }

    conduits.forEach(conduit => {
      const from = junctions[conduit.from] ?? outfalls[conduit.from]
      const to = junctions[conduit.to] ?? outfalls[conduit.to]

      if (!to || !from) {
        return
      }
      if (conduit.xsection.shape === 'CIRCULAR')
        if (!isNaN(conduit.xsection.geom1))
          instanceArray.push(
            new Cesium.GeometryInstance({
              geometry: new Cesium.PolylineVolumeGeometry({
                vertexFormat: Cesium.VertexFormat.POSITION_ONLY,
                polylinePositions: Cesium.Cartesian3.fromDegreesArrayHeights([
                  from.longitude,
                  from.latitude,
                  from.elevation,
                  to.longitude,
                  to.latitude,
                  to.elevation
                ]),
                shapePositions: computeCircle(conduit.xsection.geom1 * 1)
              }),
              // id: 'conduits' + conduit.id,
              id: conduit.id,
              attributes: {
                // color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.RED)
                color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.fromRandom({ alpha: 0.7 }))
              }
            })
          )
        else {
          return
        }

      if (conduit.xsection.shape === 'RECT_CLOSED')
        if (!isNaN(conduit.xsection.geom1) && !isNaN(conduit.xsection.geom2))
          instanceArray.push(
            new Cesium.GeometryInstance({
              geometry: new Cesium.PolylineVolumeGeometry({
                vertexFormat: Cesium.VertexFormat.POSITION_ONLY,
                polylinePositions: Cesium.Cartesian3.fromDegreesArrayHeights([
                  from.longitude,
                  from.latitude,
                  from.elevation,
                  to.longitude,
                  to.latitude,
                  to.elevation
                ]),
                shapePositions: computeRect(new Cesium.Cartesian2(conduit.xsection.geom2, conduit.xsection.geom1))
              }),
              // id: 'conduits' + conduit.id,
              id: conduit.id,
              attributes: {
                // color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.RED)
                color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.fromRandom({ alpha: 0.7 }))
              }
            })
          )
        else {
          return
        }

    })
    this.pipePrimitive = this._viewer.scene.primitives.add(
      new Cesium.Primitive({
        geometryInstances: instanceArray,
        appearance: new Cesium.PerInstanceColorAppearance(),
        releaseGeometryInstances: false,
        asynchronous: false
      })
    )

    const junctionInstanceArray = []
    Object.values(junctions).forEach(junction => {
      if (
        !isNaN(junction.longitude) &&
        !isNaN(junction.latitude) &&
        !isNaN(junction.elevation) &&
        !isNaN(junction.maxDepth) &&
        junction.Outlet
      ) {
        const instancePosition = Cesium.Cartesian3.fromDegrees(
          junction.longitude,
          junction.latitude,
          junction.elevation
        )
        const modelMatrix = Cesium.Transforms.eastNorthUpToFixedFrame(instancePosition)

        junctionInstanceArray.push(
          new Cesium.GeometryInstance({
            geometry: new Cesium.BoxGeometry({
              vertexFormat: Cesium.VertexFormat.POSITION_ONLY,
              maximum: new Cesium.Cartesian3(0.5, 0.5, junction.maxDepth),
              minimum: new Cesium.Cartesian3(-0.5, -0.5, -0)
            }),
            modelMatrix: modelMatrix,
            // id: 'junction' + junction.id,
            id: junction.id,
            attributes: {
              color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.WHITE)
            }
          })
        )
      }
    })
    this._viewer.scene.primitives.add(
      new Cesium.Primitive({
        geometryInstances: junctionInstanceArray,
        appearance: new Cesium.PerInstanceColorAppearance(),
        asynchronous: false
      })
    )

    const outfallInstanceArray = []
    Object.values(outfalls).forEach(outfall => {
      if (!isNaN(outfall.longitude) && !isNaN(outfall.latitude) && !isNaN(outfall.elevation)) {
        const instancePosition = Cesium.Cartesian3.fromDegrees(outfall.longitude, outfall.latitude, outfall.elevation)
        const modelMatrix = Cesium.Transforms.eastNorthUpToFixedFrame(instancePosition)

        const scale = 1
        outfallInstanceArray.push(
          new Cesium.GeometryInstance({
            geometry: new Cesium.BoxGeometry({
              vertexFormat: Cesium.VertexFormat.POSITION_ONLY,
              maximum: new Cesium.Cartesian3(0.5 * scale, 0.5 * scale, 0.5 * scale),
              minimum: new Cesium.Cartesian3(-0.5 * scale, -0.5 * scale, -0.5 * scale)
            }),
            modelMatrix: modelMatrix,
            // id: 'outfall' + outfall.id,
            id: outfall.id,
            attributes: {
              color: Cesium.ColorGeometryInstanceAttribute.fromColor(Cesium.Color.RED)
            }
          })
        )
      }
    })
    this._viewer.scene.primitives.add(
      new Cesium.Primitive({
        geometryInstances: outfallInstanceArray,
        appearance: new Cesium.PerInstanceColorAppearance(),
        asynchronous: false
      })
    )


    // this.pipeJSON = null
  }
  async initWater() {
    const waterInfo = this.waterInfo

    // const rangeMin = utm2wgs84(waterInfo.metadata.x_range[0], waterInfo.metadata.y_range[0], 48)
    // const rangeMax = utm2wgs84(waterInfo.metadata.x_range[1], waterInfo.metadata.y_range[1], 48)

    // const center = Cesium.Cartesian3.fromDegrees(
    //   (rangeMin.longitude + rangeMax.longitude) / 2,
    //   (rangeMin.latitude + rangeMax.latitude) / 2
    // )
    // const ncols = parseInt(
    //   (waterInfo.metadata.x_range[1] - waterInfo.metadata.x_range[0]) / waterInfo.metadata.resolution
    // )
    // const nrows = parseInt(
    //   (waterInfo.metadata.y_range[1] - waterInfo.metadata.y_range[0]) / waterInfo.metadata.resolution
    // )

    const sourceCrs = (
      waterInfo?.crs ||
        waterInfo?.metadata?.crs ||
        waterInfo?.metadata?.epsg ||
        waterInfo?.metadata?.projection ||
        waterInfo?.metadata?.grid_info?.crs
    )

    const placement = createWaterGridPlacement(waterInfo.metadata.grid_info, sourceCrs)
    const center = placement.center

    const imgs = []
    waterInfo.images.forEach(element => {
      imgs.push(element.base64_data)
    })

    this.waterIns = new PipeWater({
      viewer: this._viewer,
      center: center,
      level: 13,
      width: placement.width,
      height: placement.height,
      cellSize: placement.cellSize,
      waterHeightTexs: imgs,
      dataResolution: [placement.width, placement.height],
      maxWaterDepth: getMaxWaterDepth(waterInfo)
    })

    await this.waterIns.initBox()
  }
  async updatePipeColor(testIdx) {
    const pipeInfo = this.pipeInfo
    // let index = parseInt(this.waterIns.frame * 5)
    // if (index > 23) index = 23
    const index = testIdx
    const curPipeInfo = Object.values(pipeInfo)[index]
    Object.keys(curPipeInfo).forEach(key => {
      const attributes = this.pipePrimitive.getGeometryInstanceAttributes(key)
      const colors = [Cesium.Color.GREEN, Cesium.Color.BLUE, Cesium.Color.YELLOW, Cesium.Color.RED]
      let color
      const curWater = Math.abs(curPipeInfo[key])
      // if (curWater < 0.01) color = colors[0]
      // else if (curWater < 0.05) color = colors[1]
      // else if (curWater < 0.1) color = colors[2]
      // else color = colors[3]
      color = interpolateCesiumColor(colors, Math.min(Math.max(0, curWater / 0.2), 1))
      if (attributes) attributes.color = Cesium.ColorGeometryInstanceAttribute.toValue(color)
    })
  }
}

function getMaxWaterDepth(waterData) {
  const depth = Number(
    waterData?.metadata?.max_values?.depth ??
      waterData?.metadata?.maxValues?.depth ??
      waterData?.max_values?.depth ??
      waterData?.maxValues?.depth ??
      waterData?.maxDepth
  )
  return Number.isFinite(depth) && depth > 0 ? depth : 1
}

function createWaterGridPlacement(gridInfoRaw, requestedCrs) {
  const xRange = parseGridRange(gridInfoRaw.x_range)
  const yRange = parseGridRange(gridInfoRaw.y_range)
  const sourceCellSize = parseGridSize(gridInfoRaw.grid_size)
  const width = parseGridDimension(gridInfoRaw.raster_width || gridInfoRaw.width || gridInfoRaw.ncols, xRange, sourceCellSize)
  const height = parseGridDimension(gridInfoRaw.raster_height || gridInfoRaw.height || gridInfoRaw.nrows, yRange, sourceCellSize)
  const centerX = xRange[0] + ((width - 1) * sourceCellSize) / 2
  const centerY = yRange[0] + ((height - 1) * sourceCellSize) / 2

  const projector = createInpCoordinateProjector(
    {
      lowerLeft: { x: xRange[0], y: yRange[0] },
      upperRight: { x: xRange[1], y: yRange[1] },
      center: { x: centerX, y: centerY }
    },
    requestedCrs
  )
  const { longitude, latitude } = projector.project(centerX, centerY)
  const cellSize = calculateProjectedCellSize(centerX, centerY, sourceCellSize, projector) || sourceCellSize

  return {
    xRange,
    yRange,
    width,
    height,
    sourceCellSize,
    cellSize,
    longitude,
    latitude,
    center: Cesium.Cartesian3.fromDegrees(longitude, latitude),
    projection: projector.summary
  }
}

function parseGridRange(value) {
  if (Array.isArray(value)) return [parseFloat(value[0]), parseFloat(value[1])]
  if (typeof value === 'string') {
    const parts = value.replace(/\[|\]/g, '').split(',')
    return [parseFloat(parts[0]), parseFloat(parts[1])]
  }
  if (value && typeof value === 'object' && 'min' in value && 'max' in value) {
    return [parseFloat(value.min), parseFloat(value.max)]
  }
  return [0, 0]
}

function parseGridSize(value) {
  const size = typeof value === 'number' ? value : parseFloat(value?.toString().split(' ')[0] || 10)
  return Number.isFinite(size) && size > 0 ? size : 10
}

function parseGridDimension(value, range, cellSize) {
  const dimension = parseInt(value, 10)
  if (Number.isFinite(dimension) && dimension > 0) return dimension

  const rangeSize = Math.abs(range[1] - range[0])
  const fallback = Math.ceil(rangeSize / cellSize) + 1
  return Number.isFinite(fallback) && fallback > 0 ? fallback : 200
}

function calculateProjectedCellSize(centerX, centerY, sourceCellSize, projector) {
  const halfCell = sourceCellSize / 2
  const eastWest = calculateProjectedDistance(
    projector.project(centerX - halfCell, centerY),
    projector.project(centerX + halfCell, centerY)
  )
  const northSouth = calculateProjectedDistance(
    projector.project(centerX, centerY - halfCell),
    projector.project(centerX, centerY + halfCell)
  )
  const distances = [eastWest, northSouth].filter(distance => Number.isFinite(distance) && distance > 0)
  if (!distances.length) return null

  return distances.reduce((sum, distance) => sum + distance, 0) / distances.length
}

function calculateProjectedDistance(start, end) {
  if (!isValidLonLat(start.longitude, start.latitude) || !isValidLonLat(end.longitude, end.latitude)) return NaN

  return Cesium.Cartesian3.distance(
    Cesium.Cartesian3.fromDegrees(start.longitude, start.latitude),
    Cesium.Cartesian3.fromDegrees(end.longitude, end.latitude)
  )
}

function createInpCoordinateProjector(coordinates, requestedCrs) {
  const points = Object.values(coordinates)
    .filter(point => Number.isFinite(point.x) && Number.isFinite(point.y))
    .map(point => [point.x, point.y])
  const bounds = getCoordinateBounds(points)
  const crsCode = normalizeCrsCode(requestedCrs)

  const createProjector = (mode, reason, project) => ({
    summary: { requestedCrs: requestedCrs || null, mode, reason, bounds },
    project(x, y) {
      if (!Number.isFinite(x) || !Number.isFinite(y)) {
        return { longitude: NaN, latitude: NaN }
      }
      try {
        const result = project(x, y)
        if (isValidLonLat(result.longitude, result.latitude)) return result
      } catch (error) {
        console.warn('[PipeLine] INP coordinate projection failed:', error)
      }
      return { longitude: NaN, latitude: NaN }
    }
  })

  if (!bounds) {
    return createProjector('unknown', 'no finite coordinates', () => ({ longitude: NaN, latitude: NaN }))
  }

  if (looksLikeLonLat(bounds)) {
    return createProjector('EPSG:4326', 'coordinate range is already longitude/latitude', (x, y) => ({
      longitude: x,
      latitude: y
    }))
  }

  if (isWebMercatorCrs(crsCode)) {
    return createProjector('EPSG:3857', 'requested CRS is Web Mercator', mercatorToWGS84)
  }

  if (isGeographicCrs(crsCode)) {
    return createProjector('EPSG:4326', 'requested CRS is geographic', (x, y) => ({
      longitude: x,
      latitude: y
    }))
  }

  if (isUtmCrs(crsCode) && looksLikeUtm(bounds)) {
    const zone = normalizeCrsToZone(crsCode)
    const southernHemisphere = isSouthernUtmCrs(crsCode)
    return createProjector(`UTM zone ${zone}${southernHemisphere ? 'S' : 'N'}`, 'requested CRS and coordinate range match UTM', (x, y) =>
      utm2wgs84(x, y, zone, southernHemisphere)
    )
  }

  if (looksLikeUtm(bounds)) {
    const zone = normalizeCrsToZone(crsCode)
    return createProjector(`UTM zone ${zone}N`, 'coordinate range looks like UTM', (x, y) => utm2wgs84(x, y, zone))
  }

  if (looksLikeWebMercator(bounds)) {
    return createProjector('EPSG:3857', 'coordinate range looks like Web Mercator', mercatorToWGS84)
  }

  const proj4Projector = createProj4Projector(crsCode)
  if (proj4Projector) {
    return createProjector(crsCode, 'requested CRS handled by proj4', proj4Projector)
  }

  const zone = normalizeCrsToZone(crsCode)
  return createProjector(`UTM zone ${zone}N`, 'fallback to UTM zone from CRS/default', (x, y) => utm2wgs84(x, y, zone))
}

function getCoordinateBounds(points) {
  if (!points.length) return null

  return points.reduce(
    (bounds, [x, y]) => ({
      minX: Math.min(bounds.minX, x),
      maxX: Math.max(bounds.maxX, x),
      minY: Math.min(bounds.minY, y),
      maxY: Math.max(bounds.maxY, y)
    }),
    { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity }
  )
}

function normalizeCrsCode(crs) {
  if (crs === null || crs === undefined) return ''
  if (typeof crs === 'number') return `EPSG:${crs}`
  const value = String(crs).trim().toUpperCase()
  if (!value) return ''
  if (/^\d{4,5}$/.test(value)) return `EPSG:${value}`
  return value
}

function isValidLonLat(longitude, latitude) {
  return Number.isFinite(longitude) && Number.isFinite(latitude) && longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90
}

function looksLikeLonLat(bounds) {
  return bounds.minX >= -180 && bounds.maxX <= 180 && bounds.minY >= -90 && bounds.maxY <= 90
}

function looksLikeUtm(bounds) {
  return bounds.minX >= 100000 && bounds.maxX <= 900000 && bounds.minY >= 0 && bounds.maxY <= 10000000
}

function looksLikeWebMercator(bounds) {
  const max = 20037508.342789244
  return (
    bounds.minX >= -max &&
    bounds.maxX <= max &&
    bounds.minY >= -max &&
    bounds.maxY <= max &&
    !looksLikeLonLat(bounds)
  )
}

function isWebMercatorCrs(crsCode) {
  return crsCode === 'EPSG:3857' || crsCode === 'EPSG:900913'
}

function isGeographicCrs(crsCode) {
  return crsCode === 'EPSG:4326' || crsCode === 'EPSG:4490'
}

function isUtmCrs(crsCode) {
  const epsgMatch = crsCode.match(/^EPSG:(326|327)(\d{2})$/)
  return Boolean(epsgMatch)
}

function isSouthernUtmCrs(crsCode) {
  return /^EPSG:327\d{2}$/.test(crsCode)
}

function createProj4Projector(crsCode) {
  if (!crsCode) return null
  return (x, y) => {
    const [longitude, latitude] = proj4(crsCode, 'EPSG:4326', [x, y])
    return { longitude, latitude }
  }
}

function utm2wgs84(x, y, zone, southernHemisphere = false) {
  const wgs84 = '+proj=longlat +ellps=WGS84 +datum=WGS84 +no_defs'
  const south = southernHemisphere ? ' +south' : ''
  const utm = `+proj=utm +zone=${zone}${south} +ellps=WGS84 +datum=WGS84 +units=m +no_defs`
  const [longitude, latitude] = proj4(utm, wgs84, [x, y])
  return { longitude, latitude }
}

function normalizeCrsToZone(crs) {
  if (!crs && crs !== 0) return 50

  if (typeof crs === 'number') {
    if (crs >= 32601 && crs <= 32660) return crs - 32600
    if (crs >= 32701 && crs <= 32760) return crs - 32700
    if (crs >= 1 && crs <= 60) return crs
  }

  if (typeof crs === 'string') {
    const epsgMatch = crs.match(/(\d{4,5})$/)
    if (epsgMatch) {
      const epsg = parseInt(epsgMatch[1], 10)
      if (!Number.isNaN(epsg)) {
        if (epsg >= 32601 && epsg <= 32660) return epsg - 32600
        if (epsg >= 32701 && epsg <= 32760) return epsg - 32700
      }
    }

    const zoneMatch = crs.match(/(\d{1,2})$/)
    if (zoneMatch) {
      const zone = parseInt(zoneMatch[1], 10)
      if (zone >= 1 && zone <= 60) return zone
    }
  }

  return 50
}
function mercatorToWGS84(x, y) {
  const epsg3857 = 'EPSG:3857'
  const epsg4326 = 'EPSG:4326'

  const [longitude, latitude] = proj4(epsg3857, epsg4326, [x, y])

  return { longitude, latitude }
}

function calculatePipeJsonCenter(pipeJSON) {
  if (!pipeJSON) return null

  const nodes = [...Object.values(pipeJSON.junctions || {}), ...Object.values(pipeJSON.outfalls || {})]
  const coordinates = nodes
    .filter(node => isValidLonLat(node.longitude, node.latitude))
    .map(node => [node.longitude, node.latitude])

  if (!coordinates.length) return null

  const bounds = getCoordinateBounds(coordinates)
  const longitude = (bounds.minX + bounds.maxX) / 2
  const latitude = (bounds.minY + bounds.maxY) / 2
  return Cesium.Cartesian3.fromDegrees(longitude, latitude, 0)
}
class PipelineRenderer {
  constructor(viewer, options = {}) {
    this.viewer = viewer
    this.scene = viewer.scene
    this.context = this.scene.context

    this.pipeJSON = options.pipeJSON
    this.pipeInfo = options.pipeInfo

    this.center = options.center ?? calculatePipeJsonCenter(this.pipeJSON) ?? Cesium.Cartesian3.fromDegrees(118.69, 32.0806, 0)

    this.timeIdx = 0
    this.renderComponent = 0
    this.showFlow = true
    this.renderAttr = true

    this.roughness = 0

    this.init()
  }

  init() {
    this.initGeometry()
    this.initRender()
  }

  initGeometry() {
    this.transform = Cesium.Transforms.eastNorthUpToFixedFrame(this.center)
    this._modelMatrix = Cesium.Transforms.eastNorthUpToFixedFrame(this.center)

    const sortIfNumeric = keys => {
      const allNumeric = keys.every(k => !isNaN(Number(k)))
      return allNumeric ? keys.map(k => k.toString()).sort((a, b) => Number(a) - Number(b)) : keys
    }

    this.timeKeys = sortIfNumeric(Object.keys(this.pipeInfo))
    this.objKeys = sortIfNumeric(Object.keys(this.pipeInfo[this.timeKeys[0]]))

    this.base = 0
    this.geometry = {
      vertices: [],
      positionHigh: [],
      positionLow: [],
      normals: [],
      uvs: [],
      indices: [],
      pipeTypes: [],
      attributeIndexs: [],
      pipeDirs: [],
      boxMax: [],
      boxMin: []
    }

    let index = 0
    const { junctions, inflows, outfalls, conduits } = this.pipeJSON
    conduits.forEach(conduit => {
      const from = junctions[conduit.from] ?? outfalls[conduit.from]
      const to = junctions[conduit.to] ?? outfalls[conduit.to]

      if (!to || !from || !conduit.xsection) {
        return
      }
      let attributeIndex = this.objKeys.indexOf(conduit.id)
      const objKeysAreNumeric = this.objKeys.every(k => !isNaN(Number(k)))
      if (attributeIndex === -1 && objKeysAreNumeric && index < this.objKeys.length) {
        attributeIndex = index
      }
      if (attributeIndex < 0) attributeIndex = 0

      if (conduit.xsection.shape == 'RECT_CLOSED') return

      this.createPipeline({
        attributeIndex,
        startLon: from.longitude,
        startLat: from.latitude,
        startEle: from.elevation,
        endLon: to.longitude,
        endLat: to.latitude,
        endEle: to.elevation,
        type: conduit.xsection.shape,
        geom1: conduit.xsection.geom1,
        geom2: conduit.xsection.geom2,
        length: conduit.length
      })
      index++
    })

    Object.values(inflows).forEach(inflow => {
      const junction = junctions[inflow.node] ?? outfalls[inflow.node]
      if (junction && !isNaN(inflow.longitude) && !isNaN(inflow.latitude) && !isNaN(junction.elevation)) {
        let attributeIndex = this.objKeys.indexOf(inflow.node)
        if (attributeIndex < 0) attributeIndex = 0
        this.createPipeline({
          attributeIndex,
          lon: inflow.longitude,
          lat: inflow.latitude,
          topElevation: junction.elevation + (junction.maxDepth || 0),
          depth: junction.maxDepth || 1,
          width: 1.5,
          height: 1.5,
          // width: 4,
          // height: 4,
          type: 'INFLOW'
        })
      }
    })
    Object.values(outfalls).forEach(outfall => {
      if (!isNaN(outfall.longitude) && !isNaN(outfall.latitude) && !isNaN(outfall.elevation)) {
        let attributeIndex = this.objKeys.indexOf(outfall.id)
        if (attributeIndex < 0) attributeIndex = 0
        this.createPipeline({
          attributeIndex,
          lon: outfall.longitude,
          lat: outfall.latitude,
          topElevation: outfall.elevation,
          depth: 5,
          width: 2,
          height: 2,
          // width: 6,
          // height: 6,
          type: 'OUTFALL'
        })
      }
    })

    const positionBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.vertices),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const positionHighBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.positionHigh),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const positionLowBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.positionLow),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const normalBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.normals),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const uvBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.uvs),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const pipeTypeBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.pipeTypes),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const attributeIndexBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.attributeIndexs),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const pipeDirBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.pipeDirs),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const boxMaxBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.boxMax),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const boxMinBuffer = Cesium.Buffer.createVertexBuffer({
      context: this.context,
      typedArray: new Float32Array(this.geometry.boxMin),
      usage: Cesium.BufferUsage.STATIC_DRAW
    })

    const vertexCount = this.geometry.vertices.length / 3
    const useUint32Index = vertexCount > 65535
    const indexTypedArray = useUint32Index ? new Uint32Array(this.geometry.indices) : new Uint16Array(this.geometry.indices)
    const indexDatatype = useUint32Index ? Cesium.IndexDatatype.UNSIGNED_INT : Cesium.IndexDatatype.UNSIGNED_SHORT

    const indexBuffer = Cesium.Buffer.createIndexBuffer({
      context: this.context,
      typedArray: indexTypedArray,
      usage: Cesium.BufferUsage.STATIC_DRAW,
      indexDatatype
    })

    const attributes = [
      {
        index: 0,
        vertexBuffer: positionBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 1,
        vertexBuffer: positionHighBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 2,
        vertexBuffer: positionLowBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 3,
        vertexBuffer: normalBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 4,
        vertexBuffer: uvBuffer,
        componentsPerAttribute: 2,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 5,
        vertexBuffer: pipeTypeBuffer,
        componentsPerAttribute: 1,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 6,
        vertexBuffer: attributeIndexBuffer,
        componentsPerAttribute: 1,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 7,
        vertexBuffer: pipeDirBuffer,
        componentsPerAttribute: 1,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 8,
        vertexBuffer: boxMaxBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      },
      {
        index: 9,
        vertexBuffer: boxMinBuffer,
        componentsPerAttribute: 3,
        componentDatatype: Cesium.ComponentDatatype.FLOAT
      }
    ]

    this.vertexArray = new Cesium.VertexArray({
      context: this.context,
      attributes: attributes,
      indexBuffer: indexBuffer
    })
  }

  async initRender() {
    this.createAttributeTexture()
    this.initTexs()

    this.vertexShaderText = vertexShader
    this.fragmentShaderText = fragmentShader
  }

  /**
   * @param {FrameState} frameState
   */
  update(frameState) {
    const command = this.createCommand(frameState, this._modelMatrix)
    frameState.commandList.push(command)
  }
  createCommand(frameState, matrix) {
    const _this = this
    const attributeLocations = {
      position: 0
    }
    const uniformMap = {
      u_time() {
        return _this.timeIdx
      },
      u_component() {
        return _this.renderComponent
      },
      u_renderAttr() {
        return _this.renderAttr
      },
      u_showFlow() {
        return _this.showFlow
      },
      u_attrMap() {
        return _this.attrTexture
      },
      u_arrowTexture() {
        return _this.arrowMap
      },
      u_roughness() {
        return _this.roughness
      }
    }

    const program = Cesium.ShaderProgram.fromCache({
      context: frameState.context,
      vertexShaderSource: this.vertexShaderText,
      fragmentShaderSource: this.fragmentShaderText,
      attributeLocations: attributeLocations
    })
    const renderState = Cesium.RenderState.fromCache({
      depthTest: {
        enabled: true
      },
      blending: Cesium.BlendingState.ALPHA_BLEND
    })
    return new Cesium.DrawCommand({
      modelMatrix: matrix,
      vertexArray: this.vertexArray,
      shaderProgram: program,
      uniformMap: uniformMap,
      renderState: renderState,
      pass: Cesium.Pass.OPAQUE
    })
  }
  worldToRelative(worldPosition) {
    if (!this.center) {
      return worldPosition
    }
    // return Cesium.Cartesian3.subtract(worldPosition, this.center, new Cesium.Cartesian3())

    const inverseTransform = Cesium.Matrix4.inverse(this.transform, new Cesium.Matrix4())
    const localPosition = Cesium.Matrix4.multiplyByPoint(inverseTransform, worldPosition, new Cesium.Cartesian3())
    return localPosition
  }
  createCircleGeometry(pipeline, segments = 32) {
    const { startLon, startLat, startEle, endLon, endLat, endEle, geom1, length, attributeIndex } =
      pipeline
    const radius = geom1

    const startPos = Cesium.Cartesian3.fromDegrees(startLon, startLat, startEle)
    const endPos = Cesium.Cartesian3.fromDegrees(endLon, endLat, endEle)

    const relativeStartPos = this.worldToRelative(startPos)
    const relativeEndPos = this.worldToRelative(endPos)

    const direction = Cesium.Cartesian3.subtract(relativeEndPos, relativeStartPos, new Cesium.Cartesian3())
    // const length = Cesium.Cartesian3.magnitude(direction)
    Cesium.Cartesian3.normalize(direction, direction)

    const up = Cesium.Matrix4.multiplyByPoint(this.transform, Cesium.Cartesian3.UNIT_Z, new Cesium.Cartesian3())
    const right = Cesium.Cartesian3.cross(direction, up, new Cesium.Cartesian3())
    Cesium.Cartesian3.normalize(right, right)
    const forward = Cesium.Cartesian3.cross(right, direction, new Cesium.Cartesian3())

    const vertices = []

    const positionHigh = []
    const positionLow = []

    const normals = []
    const uvs = []
    const indices = []

    const attributeIndexs = []
    const pipeDirs = []

    const boxMax = []
    const boxMin = []

    for (let i = 0; i <= segments; i++) {
      const angle = (i / segments) * Math.PI * 2
      const x = Math.cos(angle) * radius
      const y = Math.sin(angle) * radius

      const sectionOffset = Cesium.Cartesian3.multiplyByScalar(right, x, new Cesium.Cartesian3())
      Cesium.Cartesian3.add(
        sectionOffset,
        Cesium.Cartesian3.multiplyByScalar(forward, y, new Cesium.Cartesian3()),
        sectionOffset
      )

      const startVertex = Cesium.Cartesian3.add(relativeStartPos, sectionOffset, new Cesium.Cartesian3())
      const endVertex = Cesium.Cartesian3.add(relativeEndPos, sectionOffset, new Cesium.Cartesian3())

      const normal = Cesium.Cartesian3.normalize(sectionOffset, new Cesium.Cartesian3())

      vertices.push(startVertex.x, startVertex.y, startVertex.z)
      vertices.push(endVertex.x, endVertex.y, endVertex.z)

      const startVertexEncodeX = Cesium.EncodedCartesian3.encode(startVertex.x)
      const startVertexEncodeY = Cesium.EncodedCartesian3.encode(startVertex.y)
      const startVertexEncodeZ = Cesium.EncodedCartesian3.encode(startVertex.z)
      const endVertexEncodeX = Cesium.EncodedCartesian3.encode(endVertex.x)
      const endVertexEncodeY = Cesium.EncodedCartesian3.encode(endVertex.y)
      const endVertexEncodeZ = Cesium.EncodedCartesian3.encode(endVertex.z)
      positionHigh.push(startVertexEncodeX.high, startVertexEncodeY.high, startVertexEncodeZ.high)
      positionHigh.push(endVertexEncodeX.high, endVertexEncodeY.high, endVertexEncodeZ.high)
      positionLow.push(startVertexEncodeX.low, startVertexEncodeY.low, startVertexEncodeZ.low)
      positionLow.push(endVertexEncodeX.low, endVertexEncodeY.low, endVertexEncodeZ.low)

      normals.push(normal.x, normal.y, normal.z)
      normals.push(normal.x, normal.y, normal.z)

      uvs.push(i / segments, 0)
      uvs.push(i / segments, 1)

      attributeIndexs.push(attributeIndex)
      attributeIndexs.push(attributeIndex)

      pipeDirs.push(0)
      pipeDirs.push(length)
    }

    for (let i = 0; i < segments; i++) {
      const base = this.base + i * 2

      if (i == segments - 1) {
        indices.push(base, base + 1, this.base)
        indices.push(base + 1, this.base + 1, this.base)
      } else {
        indices.push(base, base + 1, base + 2)
        indices.push(base + 1, base + 3, base + 2)
      }
    }
    this.base += vertices.length / 3

    for (let i = 0; i < vertices.length / 3; i++) {
      boxMax.push(...[0, 0, 0])
      boxMin.push(...[0, 0, 0])
    }

    return { vertices, positionHigh, positionLow, normals, uvs, indices, attributeIndexs, pipeDirs, boxMax, boxMin }
  }
  createRectangleGeometry(pipeline) {
    const { startLon, startLat, startEle, endLon, endLat, endEle, geom1, geom2, attributeIndex } = pipeline
    const startPos = Cesium.Cartesian3.fromDegrees(startLon, startLat, startEle)
    const endPos = Cesium.Cartesian3.fromDegrees(endLon, endLat, endEle)

    const relativeStartPos = this.worldToRelative(startPos)
    const relativeEndPos = this.worldToRelative(endPos)

    const direction = Cesium.Cartesian3.subtract(relativeEndPos, relativeStartPos, new Cesium.Cartesian3())
    Cesium.Cartesian3.normalize(direction, direction)

    const up = Cesium.Matrix4.multiplyByPoint(this.transform, Cesium.Cartesian3.UNIT_Z, new Cesium.Cartesian3())
    const right = Cesium.Cartesian3.cross(direction, up, new Cesium.Cartesian3())
    Cesium.Cartesian3.normalize(right, right)
    const forward = Cesium.Cartesian3.cross(right, direction, new Cesium.Cartesian3())

    const halfWidth = geom2 / 2
    const halfHeight = geom1 / 2

    const vertices = []
    const positionHigh = []
    const positionLow = []
    const normals = []
    const uvs = []
    const indices = []

    const attributeIndexs = []
    const pipeDirs = []
    const boxMax = []
    const boxMin = []

    const corners = [
      { x: -halfWidth, y: -halfHeight },
      { x: halfWidth, y: -halfHeight },
      { x: halfWidth, y: halfHeight },
      { x: -halfWidth, y: halfHeight }
    ]

    corners.forEach((corner, i) => {
      const offset = Cesium.Cartesian3.multiplyByScalar(right, corner.x, new Cesium.Cartesian3())
      Cesium.Cartesian3.add(
        offset,
        Cesium.Cartesian3.multiplyByScalar(forward, corner.y, new Cesium.Cartesian3()),
        offset
      )

      const startVertex = Cesium.Cartesian3.add(relativeStartPos, offset, new Cesium.Cartesian3())
      const endVertex = Cesium.Cartesian3.add(relativeEndPos, offset, new Cesium.Cartesian3())

      vertices.push(startVertex.x, startVertex.y, startVertex.z)
      vertices.push(endVertex.x, endVertex.y, endVertex.z)

      const startVertexEncodeX = Cesium.EncodedCartesian3.encode(startVertex.x)
      const startVertexEncodeY = Cesium.EncodedCartesian3.encode(startVertex.y)
      const startVertexEncodeZ = Cesium.EncodedCartesian3.encode(startVertex.z)
      const endVertexEncodeX = Cesium.EncodedCartesian3.encode(endVertex.x)
      const endVertexEncodeY = Cesium.EncodedCartesian3.encode(endVertex.y)
      const endVertexEncodeZ = Cesium.EncodedCartesian3.encode(endVertex.z)
      
      positionHigh.push(startVertexEncodeX.high, startVertexEncodeY.high, startVertexEncodeZ.high)
      positionHigh.push(endVertexEncodeX.high, endVertexEncodeY.high, endVertexEncodeZ.high)
      positionLow.push(startVertexEncodeX.low, startVertexEncodeY.low, startVertexEncodeZ.low)
      positionLow.push(endVertexEncodeX.low, endVertexEncodeY.low, endVertexEncodeZ.low)

      const sideNormal = Cesium.Cartesian3.normalize(offset, new Cesium.Cartesian3())
      normals.push(sideNormal.x, sideNormal.y, sideNormal.z)
      normals.push(sideNormal.x, sideNormal.y, sideNormal.z)

      uvs.push(i / 4, 0)
      uvs.push(i / 4, 1)

      attributeIndexs.push(attributeIndex)
      attributeIndexs.push(attributeIndex)

      pipeDirs.push(0)
      pipeDirs.push(1)
    })

    const faceIndices = [
      [0, 2, 4, 6],
      [1, 3, 5, 7],
      [0, 1, 3, 2],
      [2, 3, 5, 4],
      [4, 5, 7, 6],
      [6, 7, 1, 0]
    ]

    const base = this.base
    faceIndices.forEach(face => {
      indices.push(base + face[0], base + face[1], base + face[2])
      indices.push(base + face[0], base + face[2], base + face[3])
    })
    this.base += vertices.length / 3

    for (let i = 0; i < vertices.length / 3; i++) {
      boxMax.push(...[0, 0, 0])
      boxMin.push(...[0, 0, 0])
    }

    return { vertices, positionHigh, positionLow, normals, uvs, indices, attributeIndexs, pipeDirs, boxMax, boxMin }
  }
  createVerticalRectangleGeometry(pipeline) {
    const { lon, lat, topElevation, depth, width, height, attributeIndex } = pipeline
    const topPos = Cesium.Cartesian3.fromDegrees(lon, lat, topElevation)
    const bottomPos = Cesium.Cartesian3.fromDegrees(lon, lat, topElevation - depth)

    const relativeTopPos = this.worldToRelative(topPos)
    const relativeBottomPos = this.worldToRelative(bottomPos)

    const localUp = Cesium.Cartesian3.UNIT_Z
    Cesium.Cartesian3.normalize(localUp, localUp)

    const east = Cesium.Cartesian3.UNIT_Y
    Cesium.Cartesian3.normalize(east, east)
    const north = Cesium.Cartesian3.cross(localUp, east, new Cesium.Cartesian3())
    // const north = Cesium.Matrix4.multiplyByPoint(this.transform, Cesium.Cartesian3.UNIT_X, new Cesium.Cartesian3())
    Cesium.Cartesian3.normalize(north, north)

    const halfWidth = width / 2
    const halfHeight = height / 2

    const vertices = []

    const positionHigh = []
    const positionLow = []

    const tempMax = [-9999, -9999, -9999]
    const tempMin = [9999, 9999, 9999]
    const updateMaxMin = vertex => {
      tempMax[0] = Math.max(tempMax[0], vertex.x)
      tempMax[1] = Math.max(tempMax[1], vertex.y)
      tempMax[2] = Math.max(tempMax[2], vertex.z)
      tempMin[0] = Math.min(tempMin[0], vertex.x)
      tempMin[1] = Math.min(tempMin[1], vertex.y)
      tempMin[2] = Math.min(tempMin[2], vertex.z)
    }
    const boxMax = []
    const boxMin = []

    const normals = []
    const uvs = []
    const indices = []

    const attributeIndexs = []
    const pipeDirs = []

    const corners = [
      { x: -halfWidth, y: -halfHeight },
      { x: halfWidth, y: -halfHeight },
      { x: halfWidth, y: halfHeight },
      { x: -halfWidth, y: halfHeight }
    ]

    corners.forEach((corner, i) => {
      const horizontalOffset = Cesium.Cartesian3.multiplyByScalar(east, corner.x, new Cesium.Cartesian3())
      Cesium.Cartesian3.add(
        horizontalOffset,
        Cesium.Cartesian3.multiplyByScalar(north, corner.y, new Cesium.Cartesian3()),
        horizontalOffset
      )

      const topVertex = Cesium.Cartesian3.add(relativeTopPos, horizontalOffset, new Cesium.Cartesian3())
      const bottomVertex = Cesium.Cartesian3.add(relativeBottomPos, horizontalOffset, new Cesium.Cartesian3())

      vertices.push(topVertex.x, topVertex.y, topVertex.z)
      vertices.push(bottomVertex.x, bottomVertex.y, bottomVertex.z)

      updateMaxMin(topVertex)
      updateMaxMin(bottomVertex)

      const startVertexEncodeX = Cesium.EncodedCartesian3.encode(topVertex.x)
      const startVertexEncodeY = Cesium.EncodedCartesian3.encode(topVertex.y)
      const startVertexEncodeZ = Cesium.EncodedCartesian3.encode(topVertex.z)
      const endVertexEncodeX = Cesium.EncodedCartesian3.encode(bottomVertex.x)
      const endVertexEncodeY = Cesium.EncodedCartesian3.encode(bottomVertex.y)
      const endVertexEncodeZ = Cesium.EncodedCartesian3.encode(bottomVertex.z)
      positionHigh.push(startVertexEncodeX.high, startVertexEncodeY.high, startVertexEncodeZ.high)
      positionHigh.push(endVertexEncodeX.high, endVertexEncodeY.high, endVertexEncodeZ.high)
      positionLow.push(startVertexEncodeX.low, startVertexEncodeY.low, startVertexEncodeZ.low)
      positionLow.push(endVertexEncodeX.low, endVertexEncodeY.low, endVertexEncodeZ.low)

      const sideNormal = Cesium.Cartesian3.normalize(horizontalOffset, new Cesium.Cartesian3())
      normals.push(sideNormal.x, sideNormal.y, sideNormal.z)
      normals.push(sideNormal.x, sideNormal.y, sideNormal.z)

      uvs.push(i / 4, 0)
      uvs.push(i / 4, 1)

      attributeIndexs.push(attributeIndex)
      attributeIndexs.push(attributeIndex)

      pipeDirs.push(depth)
      pipeDirs.push(0)
    })

    const topCenterIndex = this.base + vertices.length / 3
    vertices.push(relativeTopPos.x, relativeTopPos.y, relativeTopPos.z)

    const startVertexEncodeX = Cesium.EncodedCartesian3.encode(relativeTopPos.x)
    const startVertexEncodeY = Cesium.EncodedCartesian3.encode(relativeTopPos.y)
    const startVertexEncodeZ = Cesium.EncodedCartesian3.encode(relativeTopPos.z)
    positionHigh.push(startVertexEncodeX.high, startVertexEncodeY.high, startVertexEncodeZ.high)
    positionLow.push(startVertexEncodeX.low, startVertexEncodeY.low, startVertexEncodeZ.low)

    normals.push(localUp.x, localUp.y, localUp.z)
    uvs.push(0.5, 0.5)
    attributeIndexs.push(attributeIndex)
    pipeDirs.push(depth)

    const bottomCenterIndex = topCenterIndex + 1
    vertices.push(relativeBottomPos.x, relativeBottomPos.y, relativeBottomPos.z)

    const endVertexEncodeX = Cesium.EncodedCartesian3.encode(relativeBottomPos.x)
    const endVertexEncodeY = Cesium.EncodedCartesian3.encode(relativeBottomPos.y)
    const endVertexEncodeZ = Cesium.EncodedCartesian3.encode(relativeBottomPos.z)
    positionHigh.push(endVertexEncodeX.high, endVertexEncodeY.high, endVertexEncodeZ.high)
    positionLow.push(endVertexEncodeX.low, endVertexEncodeY.low, endVertexEncodeZ.low)

    normals.push(-localUp.x, -localUp.y, -localUp.z)
    uvs.push(0.5, 0.5)
    attributeIndexs.push(attributeIndex)
    pipeDirs.push(0)


    for (let i = 0; i < 4; i++) {
      const current = this.base + i * 2
      const next = this.base + ((i + 1) % 4) * 2

      indices.push(current, current + 1, next)
      indices.push(current + 1, next + 1, next)
    }

    // for (let i = 0; i < 4; i++) {
    //   const current = this.base + i * 2
    //   const next = this.base + ((i + 1) % 4) * 2
    //   indices.push(topCenterIndex, current, next)
    // }

    // for (let i = 0; i < 4; i++) {
    //   const current = this.base + i * 2 + 1
    //   const next = this.base + ((i + 1) % 4) * 2 + 1
    //   indices.push(bottomCenterIndex, next, current)
    // }


    const capTopBase = this.base + vertices.length / 3

    for (let i = 0; i < 4; i++) {
      const corner = corners[i]

      const horizontalOffset = Cesium.Cartesian3.add(
        Cesium.Cartesian3.multiplyByScalar(east, corner.x, new Cesium.Cartesian3()),
        Cesium.Cartesian3.multiplyByScalar(north, corner.y, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      )
      const topCap = Cesium.Cartesian3.add(relativeTopPos, horizontalOffset, new Cesium.Cartesian3())

      vertices.push(topCap.x, topCap.y, topCap.z)

      const encX = Cesium.EncodedCartesian3.encode(topCap.x)
      const encY = Cesium.EncodedCartesian3.encode(topCap.y)
      const encZ = Cesium.EncodedCartesian3.encode(topCap.z)
      positionHigh.push(encX.high, encY.high, encZ.high)
      positionLow.push(encX.low, encY.low, encZ.low)

      normals.push(localUp.x, localUp.y, localUp.z)

      const u = (corner.x + halfWidth) / (2 * halfWidth)
      const v = (corner.y + halfHeight) / (2 * halfHeight)
      uvs.push(u, v)

      attributeIndexs.push(attributeIndex)
      pipeDirs.push(depth)
    }

    const capBottomBase = this.base + vertices.length / 3

    for (let i = 0; i < 4; i++) {
      const corner = corners[i]

      const horizontalOffset = Cesium.Cartesian3.add(
        Cesium.Cartesian3.multiplyByScalar(east, corner.x, new Cesium.Cartesian3()),
        Cesium.Cartesian3.multiplyByScalar(north, corner.y, new Cesium.Cartesian3()),
        new Cesium.Cartesian3()
      )
      const bottomCap = Cesium.Cartesian3.add(relativeBottomPos, horizontalOffset, new Cesium.Cartesian3())

      vertices.push(bottomCap.x, bottomCap.y, bottomCap.z)

      const encX = Cesium.EncodedCartesian3.encode(bottomCap.x)
      const encY = Cesium.EncodedCartesian3.encode(bottomCap.y)
      const encZ = Cesium.EncodedCartesian3.encode(bottomCap.z)
      positionHigh.push(encX.high, encY.high, encZ.high)
      positionLow.push(encX.low, encY.low, encZ.low)

      normals.push(-localUp.x, -localUp.y, -localUp.z)

      const u = (corner.x + halfWidth) / (2 * halfWidth)
      const v = (corner.y + halfHeight) / (2 * halfHeight)
      uvs.push(u, v)

      attributeIndexs.push(attributeIndex)
      pipeDirs.push(0)
    }

    for (let i = 0; i < 4; i++) {
      const ci = capTopBase + i
      const ni = capTopBase + ((i + 1) % 4)
      indices.push(topCenterIndex, ci, ni)
    }
    for (let i = 0; i < 4; i++) {
      const ci = capBottomBase + i
      const ni = capBottomBase + ((i + 1) % 4)
      indices.push(bottomCenterIndex, ni, ci)
    }

    this.base += vertices.length / 3

    for (let i = 0; i < vertices.length / 3; i++) {
      boxMax.push(...tempMax)
      boxMin.push(...tempMin)
    }

    return { vertices, positionHigh, positionLow, normals, uvs, indices, attributeIndexs, pipeDirs, boxMax, boxMin }
  }
  createPipeline(pipeline) {
    let geometry
    let pipeType

    if (pipeline.type === 'CIRCULAR') {
      geometry = this.createCircleGeometry(pipeline, 32)
      pipeType = 0.0
      } else if (pipeline.type === 'RECT_CLOSED') {
        geometry = this.createRectangleGeometry(pipeline)
        pipeType = 1.0
    } else if (pipeline.type === 'INFLOW') {
      geometry = this.createVerticalRectangleGeometry(pipeline)
      pipeType = 1.0
    } else if (pipeline.type === 'OUTFALL') {
      geometry = this.createVerticalRectangleGeometry(pipeline)
      pipeType = 2.0
    } else {
      console.warn('不支持的管道类型:', type)
      return
    }

    const pipeTypes = new Array(geometry.vertices.length / 3).fill(pipeType)
    geometry.pipeTypes = pipeTypes

    const _this = this

    Object.keys(this.geometry).forEach(key => {
      _this.geometry[key].push(...geometry[key])
    })
  }

  createAttributeTexture() {
    const _this = this
    const width = this.timeKeys.length
    const height = this.objKeys.length

    const data = new Float32Array(width * height * 4) // RGBA
    _this.objKeys.forEach((objKey, ObjIdx) => {
      _this.timeKeys.forEach((timeKey, timeIdx) => {
        const attrs = this.pipeInfo[timeKey][objKey]
        const index = (timeIdx + ObjIdx * width) * 4
        data[index] = attrs.Capacity ?? attrs.Depth_above_invert ?? 0
        data[index + 1] = attrs.Flow_velocity ?? attrs.xmomentum ?? attrs.Hydraulic_head ?? 0
        data[index + 2] = attrs.Flow_rate ?? attrs.ymomentum ?? attrs.Total_inflow ?? 0
        data[index + 3] = attrs.Flow_depth ?? attrs.Flow_lost_flooding ?? 0
      })
    })

    this.attrTexture = new Cesium.Texture({
      context: this.viewer.scene.context,
      flipY: false,
      width: width,
      height: height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      source: {
        arrayBufferView: data
      }
    })

  }

  async loadTextureFromUrl(url) {
    const image = await Cesium.Resource.fetchImage({
      url: url
    })
    return new Cesium.Texture({
      context: this.viewer.scene.frameState.context,
      source: image,
      sampler: new Cesium.Sampler({
        wrapS: Cesium.TextureWrap.REPEAT,
        wrapT: Cesium.TextureWrap.REPEAT,
        magnificationFilter: Cesium.TextureMagnificationFilter.LINEAR,
        minificationFilter: Cesium.TextureMinificationFilter.LINEAR_MIPMAP_LINEAR
      })
    })
  }

  async initTexs() {
    this.arrowMap = this.viewer.scene.frameState.context.defaultTexture
    this.arrowMap = await this.loadTextureFromUrl(import.meta.env.VITE_NGINX_URL + '/Textures/arrow5.png')
    this.arrowMap.generateMipmap()
  }
  updatePipeInfo(newPipeInfo) {

    if (!newPipeInfo || Object.keys(newPipeInfo).length === 0) {
      console.error('[PipelineRenderer] 新的管网数据无效')
      return
    }

    this.pipeInfo = newPipeInfo
    const sortIfNumeric = keys => {
      const allNumeric = keys.every(k => !isNaN(Number(k)))
      return allNumeric ? keys.map(k => k.toString()).sort((a, b) => Number(a) - Number(b)) : keys
    }

    this.timeKeys = sortIfNumeric(Object.keys(this.pipeInfo))
    this.objKeys = sortIfNumeric(Object.keys(this.pipeInfo[this.timeKeys[0]]))


    try {
      this.createAttributeTexture()
    } catch (error) {
      console.error('[PipelineRenderer] 重新创建属性纹理失败:', error)
    }
  }
  updateObjectData(objectData) {
    this.objectData = objectData
  }

  isDestroyed() {
    return false
  }
}
export default PipeLine
