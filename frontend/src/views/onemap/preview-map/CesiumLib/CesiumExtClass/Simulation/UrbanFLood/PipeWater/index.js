import {
  CustomPrimitive,
  RenderUtil,
  getNorthPointByDistance,
  angle,
  generateModelMatrix
} from '../../Util'
const Cesium = window.Cesium
import proj4 from 'proj4'
import { cloneDeep } from 'lodash'

import Command from './shaders/Command.glsl?raw'
import BufferA from './shaders/BufferA.glsl?raw'
import BufferB from './shaders/BufferB.glsl?raw'
import BufferC from './shaders/BufferC.glsl?raw'
import BufferD from './shaders/BufferD.glsl?raw'
import BufferVector from './shaders/BufferVector.glsl?raw'
import BufferFlow from './shaders/BufferFlow.glsl?raw'
import BufferDraw from './shaders/BufferDraw.glsl?raw'

import BufferFluid from './shaders/BufferFluid.glsl?raw'
import BufferFluidA from './shaders/BufferFluidA.glsl?raw'
import BufferFluidB from './shaders/BufferFluidB.glsl?raw'

const base64ToOriginalImg = async base64 =>
  new Promise((resolve, reject) => {
    const img = new Image()
    img.src = base64
    img.onload = () => resolve(img)
    img.onerror = reject
  })

class PipeWater {
  constructor(options) {
    this._viewer = options.viewer
    this._width = options.width ?? 1024
    this._height = options.height ?? 1024
    this.center = options.center
    this.level = options.level
    this.cellSize = options.cellSize ?? 30

    this._resolution = new Cesium.Cartesian2(this._width, this._height)

    this._vectorScale = 8
    this._vectorWidth = this._width * this._vectorScale
    this._vectorHeight = this._height * this._vectorScale
    this._vectorResolution = new Cesium.Cartesian2(this._vectorWidth, this._vectorHeight)

    this.dataResolution = new Cesium.Cartesian2(options.dataResolution[0], options.dataResolution[1])

    this._waterAdd = new Cesium.Cartesian4(0, 0, 0, 0)
    this._deepWaterColor = options.deepWaterColor
      ? Cesium.Color.fromCssColorString(options.deepWaterColor)
      : new Cesium.Cartesian4(0.359375, 0.2578125, 0.14453125, 1.0)
    this._lightWaterColor = options.lightWaterColor
      ? Cesium.Color.fromCssColorString(options.lightWaterColor)
      : new Cesium.Cartesian4(0.7176, 0.5508, 0.0703, 1.0)
    // this.deepWaterColor = '#5C4225'
    // this.lightWaterColor = '#B78C12'
    this.deepWaterColor = '#007bff'
    this.lightWaterColor = '#7ccae4'
    this.renderTerrain = options.renderTerrain ?? false
    this.renderHeatMap = options.renderHeatMap ?? false
    this.renderParticle = options.renderParticle ?? false
    this.renderVectorField = options.renderVectorField ?? !false
    this.renderOriginData = options.renderOriginData ?? false
    this.waterHeightTexs = options.waterHeightTexs
    const waterOpacity = Number(options.waterOpacity ?? 1)
    this.waterOpacity = Number.isFinite(waterOpacity) ? Math.min(Math.max(waterOpacity, 0), 1) : 1
    const maxWaterDepth = Number(options.maxWaterDepth)
    this.maxWaterDepth = Number.isFinite(maxWaterDepth) && maxWaterDepth > 0 ? maxWaterDepth : 1
    const minDepthThreshold = Number(options.minDepthThreshold ?? 0)
    this.minDepthThreshold = Number.isFinite(minDepthThreshold) && minDepthThreshold >= 0 ? minDepthThreshold : 0
    const maxDepthThreshold = Number(options.maxDepthThreshold ?? this.maxWaterDepth)
    this.maxDepthThreshold =
      Number.isFinite(maxDepthThreshold) && maxDepthThreshold >= this.minDepthThreshold
        ? maxDepthThreshold
        : this.maxWaterDepth

    this.spawnRate = 0.005
  }

  async initData() {
    const terrainData = await this.initTerrain(this.center, this.level)
    await this.genDemTexture(terrainData)
  }

  async initBox() {
    const terrainData = await this.initTerrain(this.center, this.level)
    await this.genDemTexture(terrainData)
    this.initShader()
    await this.initTexture()
    this.initFrameBuffer()
    this.initRender()
  }

  initTerrain(center, level) {
    const positions = []
    for (let y = 0; y < this._height; y++) {
      for (let x = 0; x < this._width; x++) {
        const offset = [(x - this._width / 2 + 0.5) * this.cellSize, (y - this._height / 2 + 0.5) * this.cellSize]
        const cellCenter = getNorthPointByDistance(center, offset)
        positions.push(Cesium.Cartographic.fromCartesian(cellCenter))
      }
    }
    return Cesium.sampleTerrain(this._viewer.terrainProvider, level, positions)
    // return viewer.scene.sampleHeightMostDetailed(positions)
  }

  loadImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image()
      img.setAttribute('crossOrigin', 'anonymous')
      img.src = url
      img.onload = () => {
        resolve(img)
      }
      img.onerror = () => {
        reject(new Error('图像加载失败'))
      }
    })
  }

  async genDemTexture(data) {
    this.terrainData = data
    const width = this._width
    const height = this._height

    const texData = new Float32Array(width * height * 4)

    const demBuffer = new ArrayBuffer(width * height * 4)
    const demDataView = new DataView(demBuffer)

    let max = 100.01,
      min = 999999
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        let originalIndex = y * width + x
        max = Math.max(data[originalIndex].height ?? 0, max)
        min = Math.min(data[originalIndex].height ?? 0, min)
      }
    }

    function clamp(value, min, max) {
      return Math.min(Math.max(value, min), max)
    }

    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        let originalIndex = y * width + x
        const h = clamp((data[originalIndex].height ?? 0 - min) / (max - min), 0, 1)
        texData[originalIndex * 4] =
          texData[originalIndex * 4 + 1] =
          texData[originalIndex * 4 + 2] =
          texData[originalIndex * 4 + 3] =
            h

        demDataView.setInt32(originalIndex * 4, data[originalIndex].height ?? 0, !true)
      }
    }
    this.demMap = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: texData
    })
    this.waterHeightMap = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: texData
    })

    this.max = max
    this.min = min

    this.texData = texData

    this.demBlob = new Blob([demBuffer], {
      type: 'application/octet-stream'
    })
    this.demBuffer = demBuffer

    // this.demBlob = this.genBlobWithHeader(demDataView)
  }

  genBlobWithHeader(dataView) {
    const left_top = getNorthPointByDistance(this.center, [
      (-this._width / 2 + 0.5) * this.cellSize,
      (this._height / 2 + 0.5) * this.cellSize
    ])
    const left_bottom_cart = Cesium.Cartographic.fromCartesian(
      getNorthPointByDistance(left_top, [0, -this._height * this.cellSize])
    )
    // project
    const wgs84 = '+proj=longlat +ellps=WGS84 +datum=WGS84 +no_defs'
    const zone = Math.floor((Cesium.Math.toDegrees(left_bottom_cart.longitude) + 180) / 6) + 1
    const utm = `+proj=utm +zone=${zone} +ellps=WGS84 +datum=WGS84 +units=m +no_defs`
    const [x, y] = proj4(wgs84, utm, [
      Cesium.Math.toDegrees(left_bottom_cart.longitude),
      Cesium.Math.toDegrees(left_bottom_cart.latitude)
    ])

    const asciiJson = {
      ncols: this._width,
      nrows: this._height,
      xllcorner: x,
      yllcorner: y,
      cellsize: this.cellSize,
      NODATA_value: -9999,
      zone: 'EPSG:326' + zone
    }

    const header = [
      `ncols         ${asciiJson.ncols}`,
      `nrows         ${asciiJson.nrows}`,
      `xllcorner     ${asciiJson.xllcorner}`,
      `yllcorner     ${asciiJson.yllcorner}`,
      `cellsize      ${asciiJson.cellsize}`,
      `NODATA_value  ${asciiJson.NODATA_value}`
    ].join('\n')

    const rowData = []
    const dataRows = []

    for (let y = 0; y < this._height; y++) {
      rowData.length = 0
      for (let x = 0; x < this._width; x++) {
        let flippedIndex = (this._height - 1 - y) * this._width + x

        rowData.push(dataView.getInt32(flippedIndex * 4, false))
      }

      dataRows.push(rowData.join(' '))
    }

    const asciiContent = `${header}\n${dataRows.join('\n')}`

    return new Blob([asciiContent], { type: 'text/plain;charset=utf-8' })
  }

  initShader() {
    this.Command =
      `const float heightRange = ` +
      (this.max - this.min) +
      `;
      
      const vec3 backgroundColor = vec3(0.2);
      const float transitionTime = 5.0;
      const float transitionPercent = 0.3;
      const int octaves = 7;
      const float attenuation = 0.995;
      const float strenght = 0.00525;
      const float minTotalFlow = 0.0001;
      const float initialWaterLevel = 0.0;
      const int radius = 1;` +
      Command

    this.BufferA = BufferA

    this.BufferB = BufferB

    this.BufferC = BufferC

    this.BufferD = BufferD

    this.renderShaderSource = BufferDraw

    this.bufferVector = BufferVector
  }

  async initTexture() {
    this.texA = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._width * this._height * 4)
    })
    this.texB = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._width * this._height * 4)
    })
    this.texC = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._width * this._height * 4)
    })
    this.texD = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._width,
      height: this._height,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._width * this._height * 4)
    })

    this.texV = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })

    this.texV_Temp = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })

    const ground = await Cesium.Resource.fetchImage({
      url: './Simulation/image/ground.png'
    })
    this.groundTex = new Cesium.Texture({
      context: this._viewer.scene.frameState.context,
      source: ground
      // sampler: new Cesium.Sampler({
      //   wrapS: Cesium.TextureWrap.REPEAT,
      //   wrapT: Cesium.TextureWrap.REPEAT,
      //   magnificationFilter: Cesium.TextureMagnificationFilter.LINEAR,
      //   minificationFilter: Cesium.TextureMinificationFilter.LINEAR_MIPMAP_LINEAR
      // })
    })
    this.groundTex.generateMipmap()

    this.texFlow = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })

    this.texFluidA = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })

    this.texFluidB = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })

    this.texFluid = RenderUtil.createTexture({
      context: this._viewer.scene.context,
      width: this._vectorWidth,
      height: this._vectorHeight,
      pixelFormat: Cesium.PixelFormat.RGBA,
      pixelDatatype: Cesium.PixelDatatype.FLOAT,
      arrayBufferView: new Float32Array(this._vectorWidth * this._vectorHeight * 4),
      sampler: Cesium.Sampler.NEAREST
    })
  }

  initFrameBuffer() {
    const _this = this
    // Render Buffers
    this.quadGeometry = RenderUtil.getFullscreenQuad()

    this.Buffer_Flow = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        resolution: () => {
          return this._vectorResolution
        },
        groundMap: () => {
          return this.groundTex
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [BufferFlow]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texFlow,
      preExecute: function () {
        _this.Buffer_Flow.commandToExecute.outputTexture = _this.texFlow
      }
    })

    this.Buffer_Vector = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        iTimeDelta: () => {
          return this.iTimeDelta
        },
        iFrame: () => {
          return this.frame
        },
        resolution: () => {
          return this._vectorResolution
        },
        vectorMap: () => {
          return this.texV_Temp
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        },
        flowResolution: () => {
          return this._resolution
        },
        spawnRate: () => {
          return this.spawnRate
        },
        flowScale: () => {
          return 1
        },
        alphaThreshold: () => {
          return 0.001
        },
        minStepPx: () => {
          return 0.015
        },
        maxSpeedPx: () => {
          return 3
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.bufferVector]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texV,
      preExecute: function () {
        _this.Buffer_Vector.commandToExecute.outputTexture = _this.texV
      }
    })

    this.Buffer_Vector_Temp = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        iTimeDelta: () => {
          return this.iTimeDelta
        },
        iFrame: () => {
          return this.frame
        },
        resolution: () => {
          return this._vectorResolution
        },
        vectorMap: () => {
          return this.texV
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        },
        flowResolution: () => {
          return this._resolution
        },
        spawnRate: () => {
          return this.spawnRate
        },
        flowScale: () => {
          return 1
        },
        alphaThreshold: () => {
          return 0.001
        },
        minStepPx: () => {
          return 0.015
        },
        maxSpeedPx: () => {
          return 3
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.bufferVector]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texV_Temp,
      preExecute: function () {
        _this.Buffer_Vector_Temp.commandToExecute.outputTexture = _this.texV_Temp
      }
    })

    this.Buffer_FluidA = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        iTimeDelta: () => {
          return this.iTimeDelta
        },
        iFrame: () => {
          return this.frame
        },
        iResolution: () => {
          return this._vectorResolution
        },
        iChannel0: () => {
          return this.texFluidB
        },
        vectorMap: () => {
          return this.texV_Temp
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [BufferFluidA]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texFluidA,
      preExecute: function () {
        _this.Buffer_FluidA.commandToExecute.outputTexture = _this.texFluidA
      }
    })

    this.Buffer_FluidB = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        iTimeDelta: () => {
          return this.iTimeDelta
        },
        iFrame: () => {
          return this.frame
        },
        iResolution: () => {
          return this._vectorResolution
        },
        iChannel0: () => {
          return this.texFluidA
        },
        vectorMap: () => {
          return this.texV
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [BufferFluidB]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texFluidB,
      preExecute: function () {
        _this.Buffer_FluidB.commandToExecute.outputTexture = _this.texFluidB
      }
    })
    this.Buffer_Fluid = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.iTime
        },
        iTimeDelta: () => {
          return this.iTimeDelta
        },
        iFrame: () => {
          return this.frame
        },
        iResolution: () => {
          return this._vectorResolution
        },
        iChannel0: () => {
          return this.texFluidA
        },
        vectorMap: () => {
          return this.texV
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [BufferFluid]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texFluid,
      preExecute: function () {
        _this.Buffer_Fluid.commandToExecute.outputTexture = _this.texFluid
      }
    })
    // BufferA
    this.Buffer_A = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.time
        },
        iFrame: () => {
          return this.frame
        },
        waterAdd: () => {
          return this._waterAdd
        },
        resolution: () => {
          return this._resolution
        },
        TerrainWaterMap: () => {
          return this.texC
        },
        OutFlow: () => {
          return this.texD
        },
        heightMap: () => {
          return this.demMap
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.BufferA]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texA,
      preExecute: function () {
        _this.Buffer_A.commandToExecute.outputTexture = _this.texA
      }
    })

    // BufferB
    this.Buffer_B = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.time
        },
        iFrame: () => {
          return this.frame
        },
        resolution: () => {
          return this._resolution
        },
        iChannel0: () => {
          return this.texA
        },
        iChannel1: () => {
          return this.texD
        },
        rangeMap: () => {
          return this.rangeMap
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.BufferB]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texB,
      preExecute: function () {
        _this.Buffer_B.commandToExecute.outputTexture = _this.texB
      }
    })

    // BufferC
    this.Buffer_C = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.time
        },
        iFrame: () => {
          return this.frame
        },
        resolution: () => {
          return this._resolution
        },
        iChannel0: () => {
          return this.texA
        },
        iChannel1: () => {
          return this.texB
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.BufferC]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texC,
      preExecute: function () {
        _this.Buffer_C.commandToExecute.outputTexture = _this.texC
      }
    })

    // BufferD
    this.Buffer_D = new CustomPrimitive({
      commandType: 'Compute',
      uniformMap: {
        iTime: () => {
          return this.time
        },
        iFrame: () => {
          return this.frame
        },
        resolution: () => {
          return this._resolution
        },
        iChannel0: () => {
          return this.texC
        },
        iChannel1: () => {
          return this.texB
        },
        rangeMap: () => {
          return this.rangeMap
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        }
      },
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command, this.BufferD]
      }),
      geometry: this.quadGeometry,
      outputTexture: this.texD,
      preExecute: function () {
        _this.Buffer_D.commandToExecute.outputTexture = _this.texD
      }
    })
  }

  initRender() {
    const cartographicCenter = Cesium.Cartographic.fromCartesian(this.center)
    const degreeCenter = [
      Cesium.Math.toDegrees(cartographicCenter.longitude),
      Cesium.Math.toDegrees(cartographicCenter.latitude),
      // cartographicCenter.height,
      (this.max + this.min) / 2 + 0
      // this.max,
    ]

    // Render Command
    const modelMatrix = generateModelMatrix(
      // [120.20998865783179, 30.13650797533829, 300],
      degreeCenter,
      [90, 0, 0],
      [
        this._width * this.cellSize,
        this.max - this.min,
        // 1600,
        this._height * this.cellSize
      ]
    )
    const boxGeometry = Cesium.BoxGeometry.fromDimensions({
      vertexFormat: Cesium.VertexFormat.POSITION_AND_ST,
      dimensions: new Cesium.Cartesian3(1, 1, 1)
    })
    const geometry = Cesium.BoxGeometry.createGeometry(boxGeometry)
    const attributelocations = Cesium.GeometryPipeline.createAttributeLocations(geometry)
    this.fluidCommand = new CustomPrimitive({
      commandType: 'Draw',
      uniformMap: {
        iTime: () => {
          return this.time
        },
        iFrame: () => {
          return this.frame
        },
        iResolution: () => {
          return this._resolution
        },
        iChannel0: () => {
          return this.texA
        },
        // iChannel1: () => {
        //   return this.terrainMap;
        // },
        heightMap: () => {
          return this.demMap
        },
        waterHeightMap: () => {
          return this.waterHeightMap
        },
        dataResolution: () => {
          return this.dataResolution
        },
        vectorScale: () => {
          return this._vectorScale
        },
        waterVectorMap: () => {
          return this.texV
        },
        waterFlowMap: () => {
          return this.texFlow
        },
        fluidMap: () => {
          return this.texFluid
        },
        waterColor: () => {
          return Cesium.Color.fromCssColorString(_this.deepWaterColor)
        },
        lightWaterColor: () => {
          return Cesium.Color.fromCssColorString(_this.lightWaterColor)
        },
        renderTerrain: () => {
          return this.renderTerrain
        },
        renderHeatMap: () => {
          return this.renderHeatMap
        },
        renderParticle: () => {
          return this.renderParticle
        },
        renderVectorField: () => {
          return this.renderVectorField
        },
        renderOriginData: () => {
          return this.renderOriginData
        },
        waterOpacity: () => {
          return this.waterOpacity
        },
        maxWaterDepth: () => {
          return this.maxWaterDepth
        },
        minDepthThreshold: () => {
          return this.minDepthThreshold
        },
        maxDepthThreshold: () => {
          return this.maxDepthThreshold
        }
      },
      geometry: geometry,
      modelMatrix: modelMatrix,
      attributeLocations: attributelocations,
      vertexShaderSource: new Cesium.ShaderSource({
        sources: [
          `
                   in vec3 position;
                   in vec2 st;
                 
                   out vec3 vo;
                   out vec3 vd;
                   out vec2 v_st;
                   void main()
                   {    
                       vo = czm_encodedCameraPositionMCHigh + czm_encodedCameraPositionMCLow;
                       vd = position - vo;
                       v_st = st;
                       gl_Position = czm_modelViewProjection * vec4(position,1.0);
                   }
                   `
        ]
      }),
      fragmentShaderSource: new Cesium.ShaderSource({
        sources: [this.Command + this.renderShaderSource]
      }),
      pass: Cesium.Pass.TRANSLUCENT,
      rawRenderState: {
        depthTest: {
          enabled: true
          // func: DepthFunction.LESS,
        },
        depthMask: false,
        blending: Cesium.BlendingState.ALPHA_BLEND
      }
    })

    this._viewer.scene.primitives.add(this.Buffer_FluidA)
    this._viewer.scene.primitives.add(this.Buffer_FluidB)
    this._viewer.scene.primitives.add(this.Buffer_Fluid)

    this._viewer.scene.primitives.add(this.Buffer_Flow)
    this._viewer.scene.primitives.add(this.Buffer_Vector)
    this._viewer.scene.primitives.add(this.Buffer_Vector_Temp)

    this._viewer.scene.primitives.add(this.Buffer_A)
    this._viewer.scene.primitives.add(this.Buffer_B)
    this._viewer.scene.primitives.add(this.Buffer_C)
    this._viewer.scene.primitives.add(this.Buffer_D)
    this._viewer.scene.primitives.add(this.fluidCommand)

    // Render Event
    const _this = this
    this.time = 1.0
    this.frame = 0
    this.dataSetIdx = 0

    this.startTime = performance.now()
    this.lastTime = cloneDeep(this.startTime)
    this.iTime = 0.0
    this.iTimeDelta = 0.0
    this._viewer.scene.postRender.addEventListener(() => {
      const now = performance.now()
      _this.time = now / 1000
      _this.frame += 0.01

      _this.iTime = (now - _this.startTime) / 1000.0
      _this.iTimeDelta = (now - _this.lastTime) / 1000.0
      _this.lastTime = now

      // if (_this.waterHeightTexs.length) {
      //   _this.setWaterHeight('data:image/png;base64,' + _this.waterHeightTexs[_this.dataSetIdx])
      //   _this.dataSetIdx = parseInt(_this.frame * 5)
      //   if (_this.dataSetIdx >= _this.waterHeightTexs.length) _this.dataSetIdx = _this.waterHeightTexs.length - 1
      // }
      if (_this.waterHeightTexs.length) {
        if (_this.dataSetIdx >= _this.waterHeightTexs.length) _this.dataSetIdx = _this.waterHeightTexs.length - 1
        const frame = _this.waterHeightTexs[_this.dataSetIdx]
        if (frame) {
          _this.setWaterHeight('data:image/png;base64,' + frame).catch(err => {
            console.warn('[PipeWater] 忽略水体帧加载错误:', err?.message || err)
          })
        }
      }
    })
  }

  addWater(position, strenght) {
    const { theta, distance } = angle(this.center, position)
    const dis_north = distance * Math.cos(Cesium.Math.toRadians(theta))
    const dis_east = distance * Math.sin(Cesium.Math.toRadians(theta))
    if (Math.abs(dis_north) > this._height * this.cellSize || Math.abs(dis_east) > this._width * this.cellSize) {
      this._waterAdd.w = 0
      return
    }
    const x = parseInt(dis_east / this.cellSize) + this._width / 2
    const y = parseInt(dis_north / this.cellSize) + this._height / 2

    this._waterAdd.x = x
    this._waterAdd.y = this._height - 1 - y
    this._waterAdd.z = strenght
    this._waterAdd.w = 1
  }
  StopAddWater() {
    this._waterAdd.w = 0
  }
  async pushFortronData(base64) {
    this.waterHeightTexs.push(base64)
  }

  async setRange(base64) {
    const img = await base64ToOriginalImg(base64)

    const newTex = new Cesium.Texture({
      context: this._viewer.scene.frameState.context,
      source: img,
      sampler: new Cesium.Sampler({
        wrapS: Cesium.TextureWrap.CLAMP_TO_EDGE,
        wrapT: Cesium.TextureWrap.CLAMP_TO_EDGE,
        magnificationFilter: Cesium.TextureMagnificationFilter.LINEAR,
        minificationFilter: Cesium.TextureMinificationFilter.LINEAR
      })
    })
    this.rangeMap = newTex
  }

  async setWaterHeight(base64) {
    if (!base64 || base64 === 'data:image/png;base64,undefined') {
      throw new Error('无效的水体帧base64');
    }
    const img = await base64ToOriginalImg(base64)

    const newTex = new Cesium.Texture({
      context: this._viewer.scene.frameState.context,
      source: img,
      flipY: false,
      sampler: new Cesium.Sampler({
        wrapS: Cesium.TextureWrap.CLAMP_TO_EDGE,
        wrapT: Cesium.TextureWrap.CLAMP_TO_EDGE,
        magnificationFilter: Cesium.TextureMagnificationFilter.LINEAR,
        minificationFilter: Cesium.TextureMinificationFilter.LINEAR
      })
    })
    const oldTex = this.waterHeightMap
    this.waterHeightMap = newTex
    if (oldTex && !oldTex.isDestroyed()) oldTex.destroy()
  }

  setRenderOpts(opt) {
    if (opt.deepWaterColor) this._deepWaterColor = Cesium.Color.fromCssColorString(opt.deepWaterColor)
    if (opt.lightWaterColor) this._lightWaterColor = Cesium.Color.fromCssColorString(opt.lightWaterColor)
    this.renderHeatMap = opt.renderHeatMap
    this.renderOriginData = opt.renderOriginData
  }

  remove() {
    this._viewer.scene.primitives.remove(this.fluidCommand)
    this._viewer.scene.primitives.remove(this.Buffer_D)
    this._viewer.scene.primitives.remove(this.Buffer_C)
    this._viewer.scene.primitives.remove(this.Buffer_B)
    this._viewer.scene.primitives.remove(this.Buffer_A)
    this._viewer.scene.primitives.remove(this.Buffer_Vector_Temp)
    this._viewer.scene.primitives.remove(this.Buffer_Vector)
  }
}

export default PipeWater
