/* eslint-disable no-undef */
import LayersManage from './CesiumExtClass/Base/Layers'

class CesiumAPI {
  constructor(elementId, mapResolve) {
    Cesium.Ion.defaultAccessToken = import.meta.env.VITE_CESIUM_ION_TOKEN || ''

    const viewer = new Cesium.Viewer(elementId, {
      terrainProvider: new Cesium.EllipsoidTerrainProvider({}),
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      baseLayerPicker: false,
      navigationHelpButton: false,
      animation: false,
      timeline: false,
      fullscreenButton: false,
      selectionIndicator: false,
      infoBox: false,
    })

    viewer.scene.screenSpaceCameraController._zoomFactor = 2.5
    viewer.scene.globe.depthTestAgainstTerrain = true
    viewer.scene.screenSpaceCameraController.zoomEventTypes = [
      Cesium.CameraEventType.WHEEL,
      Cesium.CameraEventType.PINCH,
    ]
    viewer.scene.screenSpaceCameraController.tiltEventTypes = [
      Cesium.CameraEventType.PINCH,
      Cesium.CameraEventType.RIGHT_DRAG,
    ]
    viewer.canvas.addEventListener('contextmenu', event => event.preventDefault())

    this.viewer = viewer
    this.scene = viewer.scene
    this.layers = new LayersManage(viewer)

    window.viewer = viewer
    this.initView()
    if (mapResolve) mapResolve(this)
  }

  initView() {
    this.viewer.camera.setView({
      destination: {
        x: -3087744.29665094,
        y: 11092011.788721725,
        z: 8495925.131024584,
      },
      orientation: {
        heading: 2 * Math.PI,
        pitch: -Math.PI / 2,
        roll: 0,
      },
    })
  }

  flyTo({ coor, heading = 0, pitch = -90, roll = 0, duration = 2 }) {
    this.viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(coor[0], coor[1], coor[2]),
      orientation: {
        heading: Cesium.Math.toRadians(heading),
        pitch: Cesium.Math.toRadians(pitch),
        roll: Cesium.Math.toRadians(roll),
      },
      duration,
    })
  }

  zoomIn() {
    this.moveAlongViewDirection(0.7)
  }

  zoomOut() {
    this.moveAlongViewDirection(-0.7)
  }

  moveAlongViewDirection(scale) {
    const camera = this.viewer.camera
    camera.flyTo({
      destination: Cesium.Cartesian3.add(
        camera.position,
        Cesium.Cartesian3.multiplyByScalar(
          camera.direction,
          camera.positionCartographic.height * scale,
          new Cesium.Cartesian3(),
        ),
        new Cesium.Cartesian3(),
      ),
      orientation: {
        heading: camera.heading,
        pitch: camera.pitch,
        roll: camera.roll,
      },
      duration: 1,
    })
  }

  toNorth() {
    const camera = this.viewer.camera
    const viewCenter = camera.pickEllipsoid(
      new Cesium.Cartesian2(this.viewer.canvas.clientWidth / 2, this.viewer.canvas.clientHeight / 2),
    )
    let destination = camera.position

    if (viewCenter) {
      const cartographic = Cesium.Cartographic.fromCartesian(viewCenter)
      destination = Cesium.Cartesian3.fromRadians(
        cartographic.longitude,
        cartographic.latitude,
        camera.positionCartographic.height,
      )
    }

    camera.flyTo({
      destination,
      orientation: {
        heading: 0,
        pitch: -Math.PI / 2,
        roll: 0,
      },
      duration: 1,
    })
  }

  async setTerrain(url) {
    if (!url) return
    this.viewer.terrainProvider = await Cesium.CesiumTerrainProvider.fromUrl(url)
  }

  removeTerrain() {
    this.viewer.terrainProvider = new Cesium.EllipsoidTerrainProvider({})
  }
}

export { CesiumAPI }
