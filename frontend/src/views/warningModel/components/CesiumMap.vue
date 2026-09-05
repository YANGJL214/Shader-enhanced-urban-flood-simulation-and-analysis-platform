<template>
  <div id="cesiumMap">
    <div class="button-container">
      <el-button class="tool-button" style="margin-left: 7px;" circle icon="ZoomIn" @click="mapAPI.zoomIn()" />
      <el-button class="tool-button" circle icon="ZoomOut" @click="mapAPI.zoomOut()" />
      <el-button class="tool-button" circle icon="Position" @click="showFlyTo()" />
      <el-button class="tool-button" circle icon="RefreshRight" @click="mapAPI.toNorth()" />
      <div class="locateForm" v-show="showLocateForm">
        <div class="item">
          <el-text class="mx-1" style="margin-left: 10px;">{{ t('map.longitude') }}:</el-text>
          <el-input v-model="flyLon" style="width: 120px;margin-left: 10px;"></el-input>
        </div>
        <div class="item">
          <el-text class="mx-1" style="margin-left: 10px;">{{ t('map.latitude') }}:</el-text>
          <el-input v-model="flyLat" style="width: 120px;margin-left: 10px;"></el-input>
        </div>
        <div class="item">
          <el-text class="mx-1" style="margin-left: 10px;">{{ t('map.height') }}:</el-text>
          <el-input v-model="flyHeight" style="width: 120px;margin-left: 10px;"></el-input>
        </div>
        <div class="item">
          <el-button @click="flyTo()" style="margin-left: 10px;" type="primary">{{ t('map.locate') }}</el-button>
        </div>
      </div>
    </div>
    <slot></slot>
    <div class="bottom-info">
      <span>{{ t('map.longitude') }}: {{ lonDisplay }}</span>
      <span>|</span>
      <span>{{ t('map.latitude') }}: {{ latDisplay }}</span>
      <span>|</span>
      <span>{{ t('map.height') }}: {{ heightDisplay }} m</span>
      <span>|</span>
      <span>{{ t('map.heading') }}: {{ headingDisplay }}°</span>
      <span>|</span>
      <span>{{ t('map.pitch') }}: {{ pitchDisplay }}°</span>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, onBeforeUnmount, computed, getCurrentInstance } from 'vue';
import { JSONParse } from "@/utils";
import { useAppStore } from "@/store/counter";
import { useI18n } from '@/i18n';

const Cesium = window.Cesium;
const { t } = useI18n();
const props = defineProps({
  publicMode: {
    type: Boolean,
    default: false,
  },
});

const lon = ref(0);
const lat = ref(0);
const height = ref(0);
const heading = ref(0);
const pitch = ref(0);

const lonDisplay = computed(() => lon.value.toFixed(3));
const latDisplay = computed(() => lat.value.toFixed(3));
const heightDisplay = computed(() => height.value.toFixed(2));
const headingDisplay = computed(() => heading.value.toFixed(2));
const pitchDisplay = computed(() => pitch.value.toFixed(2));

const mapAPI = ref();
const showLocateForm = ref(false);
const flyLon = ref(87.777045);
const flyLat = ref(27.928007);
const flyHeight = ref(10000);

let viewer = null;
let mouseHandler = null;
let postRenderListener = null;
let lastMousePosition = null;
let isUpdating = false;

onMounted(() => {
  useAppStore().initMap('cesiumMap');
  mapAPI.value = useAppStore().mapAPI();
  window.mapAPI = mapAPI.value;
  if (import.meta.env.VITE_DEM) {
    mapAPI.value.setTerrain(import.meta.env.VITE_DEM);
  }
  addBaseLayer();

  viewer = mapAPI.value.viewer;
  mouseHandler = new Cesium.ScreenSpaceEventHandler(viewer.canvas);
  mouseHandler.setInputAction((movement) => {
    lastMousePosition = movement.endPosition;
    if (!isUpdating) {
      isUpdating = true;
      requestAnimationFrame(updateCoordinates);
    }
  }, Cesium.ScreenSpaceEventType.MOUSE_MOVE);

  postRenderListener = viewer.scene.postRender.addEventListener(() => {
    const camera = viewer.camera;
    heading.value = Cesium.Math.toDegrees(camera.heading);
    pitch.value = Cesium.Math.toDegrees(camera.pitch);
  });
});

onBeforeUnmount(() => {
  if (mouseHandler) {
    mouseHandler.destroy();
    mouseHandler = null;
  }
  if (postRenderListener) {
    postRenderListener();
    postRenderListener = null;
  }
  lastMousePosition = null;
  viewer = null;
});

function updateCoordinates() {
  if (!lastMousePosition || !viewer) return;

  const cartesian = viewer.camera.pickEllipsoid(lastMousePosition, Cesium.Ellipsoid.WGS84);

  if (Cesium.defined(cartesian)) {
    const cartographic = Cesium.Cartographic.fromCartesian(cartesian);
    lon.value = Cesium.Math.toDegrees(cartographic.longitude);
    lat.value = Cesium.Math.toDegrees(cartographic.latitude);
    height.value = Cesium.Cartographic.fromCartesian(viewer.camera.position).height;
  } else {
    lon.value = 0;
    lat.value = 0;
    height.value = 0;
  }
  isUpdating = false;
}

const addBaseLayer = () => {
  const { proxy } = getCurrentInstance();
  if (!props.publicMode) {
    proxy.API.getLayerTree({ data: {} }).then(res => {
      const { code, data } = res;
      if (code === 200) {
        const layers = [];
        data.forEach(record => {
          if (record.mapLayerName === "默认底图") {
            getLeafs(record, layers);
          }
        });
        layers
          .slice()
          .sort(sortBy('layerResorceSort', -1))
          .forEach(element => {
            addLayer(element);
        });
      }
    });
  } else {
    addConfiguredBaseLayer();
  }
};
const addConfiguredBaseLayer = () => {
  const basemapUrl = import.meta.env.VITE_BASEMAP_URL?.trim();
  if (basemapUrl) {
    const imageryProvider = new Cesium.UrlTemplateImageryProvider({
      url: basemapUrl,
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      maximumLevel: 18,
      minimumLevel: 1,
      credit: t('map.imageryCredit'),
    });
    mapAPI.value.viewer.imageryLayers.addImageryProvider(imageryProvider);
    return;
  }

  addPublicTiandituLayers();
};
const addPublicTiandituLayers = () => {
  const token = import.meta.env.VITE_TIANDITU_TOKEN;
  if (!token) {
    mapAPI.value.layers.addXYZLayer({
      id: 'public-openstreetmap',
      url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    });
    return;
  }
  const createWmtsUrl = (layer) => (
    `https://t0.tianditu.gov.cn/${layer}_w/wmts?service=wmts&request=GetTile&version=1.0.0&LAYER=${layer}&style=default&tileMatrixSet=w&format=tiles&TileMatrix={TileMatrix}&TileRow={TileRow}&TileCol={TileCol}&tk=${token}`
  );
  const baseOptions = {
    style: 'default',
    format: 'tiles',
    tileMatrixSetID: 'w',
    minimumLevel: 1,
    maximumLevel: 18,
  };

  mapAPI.value.layers.addWMTSLayer({
    ...baseOptions,
    id: 'public-tianditu-image',
    url: createWmtsUrl('img'),
    layer: 'img',
  });
  mapAPI.value.layers.addWMTSLayer({
    ...baseOptions,
    id: 'public-tianditu-image-label',
    url: createWmtsUrl('cia'),
    layer: 'cia',
  });
};

const getLeafs = (data, leafs) => {
  if (data.children.length) {
    data.children.forEach(child => getLeafs(child, leafs));
  } else {
    leafs.push(data);
  }
};

const sortBy = (attr, rev) => {
  if (rev === undefined) rev = 1;
  return (a, b) => {
    a = a[attr];
    b = b[attr];
    if (a < b) return rev * -1;
    if (a > b) return rev * 1;
    return 0;
  };
};

const addLayer = (data) => {
  const mapResources = data.geoMapResourceVOList;
  mapResources.forEach((layerInfo, index) => {
    const extendProperties = layerInfo.properties === '{}' ? {} : JSONParse(layerInfo.properties);
    const type = layerInfo.mapLayerType;
    switch (type) {
      case 'raster':
        mapAPI.value.layers.addXYZLayer({ url: layerInfo.serverUrl, id: layerInfo.id + index });
        break;
      case 'wmts':
        mapAPI.value.layers.addWMTSLayer({
          url: layerInfo.serverUrl,
          id: layerInfo.id,
          layer: extendProperties.layer,
          style: extendProperties.style,
          format: extendProperties.format,
          tileMatrixSetID: extendProperties.tileMatrixSetID,
          minimumLevel: extendProperties.minimumLevel,
          maximumLevel: extendProperties.maximumLevel,
          tilingScheme:
            extendProperties.tileMatrixSetID === 'c'
              ? new Cesium.GeographicTilingScheme()
              : undefined,
        });
        break;
      case 'terrain':
        mapAPI.value.setTerrain(layerInfo.serverUrl);
        break;
      default:
        break;
    }
  });
};

const showFlyTo = () => {
  showLocateForm.value = !showLocateForm.value;
};

const flyTo = () => {
  mapAPI.value.flyTo({
    coor: [flyLon.value, flyLat.value, flyHeight.value],
  });
};

defineExpose({
  mapAPI,
});
</script>

<style lang="scss" scoped>
#cesiumMap {
  position: relative;
  width: 100%;
  height: calc(100% - 64px);
  border: 0;
  margin: 0;
  padding: 0;

  .button-container {
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
    position: absolute;
    top: 10px;
    right: 10px;
    z-index: 10;

    .tool-button {
      margin-bottom: 5px;
    }

    .locateForm {
      display: flex;
      flex-direction: column;
      top: 75px;
      right: 40px;
      width: 240px;
      background-color: aliceblue;
      position: absolute;
      padding: 8px;
      border-radius: 4px;
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);

      .item {
        margin: 5px 0;
        display: flex;
        align-items: center;
      }
    }
  }

  .bottom-info {
    position: absolute;
    bottom: 0;
    width: 100%;
    height: 25px;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 10px;
    padding-left: 10px;
    z-index: 20;
    background-color: rgba(26, 32, 46, 0.9);
    color: #ffffff;
    box-sizing: border-box;
    font-size: 12px;
    user-select: none;
  }
}
</style>
