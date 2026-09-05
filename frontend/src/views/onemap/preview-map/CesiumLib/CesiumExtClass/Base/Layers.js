const Cesium = window.Cesium

class LayersManage {
  constructor(viewer) {
    this.viewer = viewer
    this.idMap = new Map()
  }

  addXYZLayer(options) {
    const layer = this.viewer.imageryLayers.addImageryProvider(
      new Cesium.UrlTemplateImageryProvider({ url: options.url }),
      options.index,
    )
    this.idMap.set(options.id, layer)
    return layer
  }

  addWMTSLayer(options) {
    const minimumLevel = Number(Cesium.defaultValue(options.minimumLevel, 0))
    const maximumLevel = Number(Cesium.defaultValue(options.maximumLevel, 18))
    if (maximumLevel < minimumLevel) {
      throw new Cesium.DeveloperError('maximumLevel must be greater than or equal to minimumLevel')
    }

    const matrixLabels = []
    let customTags
    let tilingScheme = options.tilingScheme

    for (let level = minimumLevel; level <= maximumLevel; level += 1) {
      if (options.tileMatrixSetID === 'EPSG:4326') matrixLabels[level] = `EPSG:4326:${level}`
      else if (options.tileMatrixSetID === 'EPSG:4490') matrixLabels[level] = `EPSG:4490:${level}`
      else if (options.tileMatrixSetID === 'c') matrixLabels[level] = String(level + 1)
      else matrixLabels[level] = String(level)
    }

    if (options.tileMatrixSetID === 'EPSG:4490') {
      customTags = { TileMatrix: (_provider, _x, _y, level) => level + 1 }
    } else if (options.tileMatrixSetID === 'c') {
      tilingScheme = new Cesium.GeographicTilingScheme()
    }

    const provider = new Cesium.WebMapTileServiceImageryProvider({
      url: options.url,
      tilingScheme: Cesium.defaultValue(tilingScheme, new Cesium.WebMercatorTilingScheme()),
      layer: options.layer,
      style: Cesium.defaultValue(options.style, ''),
      format: Cesium.defaultValue(options.format, 'image/jpeg'),
      tileMatrixSetID: Cesium.defaultValue(options.tileMatrixSetID, 'EPSG:4326'),
      tileMatrixLabels: Cesium.defaultValue(options.tileMatrixLabels, matrixLabels),
      customTags,
    })
    const layer = this.viewer.imageryLayers.addImageryProvider(provider, options.index)
    this.idMap.set(options.id, layer)
    return layer
  }

  getLayer(id) {
    return this.idMap.get(id)
  }

  remove(id, destroy = false) {
    const layer = this.getLayer(id)
    if (layer && this.viewer.imageryLayers.remove(layer, destroy)) this.idMap.delete(id)
  }

  removeAll(destroy = false) {
    this.viewer.imageryLayers.removeAll(destroy)
    this.idMap.clear()
  }
}

export default LayersManage
