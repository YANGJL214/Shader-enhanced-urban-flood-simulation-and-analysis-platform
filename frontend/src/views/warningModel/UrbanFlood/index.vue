<template>
  <div class="container mx-container">
    <div class="mx-table-wrapper">
      <div class="mx-table-operation">
        <span class="mx-table-operation-left-btns">
          <MqttIndicator />
          <el-text class="mx-1" style="margin-left: 10px; margin-right: 10px">{{ t('toolbar.title') }}</el-text>
          <el-button @click="openRegionDialog">{{ t('toolbar.regionManagement') }}</el-button>
          <el-button @click="openParamDialog">{{ t('toolbar.parameterSettings') }}</el-button>
          <el-button @click="debouncedStartSimulation" type="primary" :disabled="isRunning">{{ t('toolbar.run') }}</el-button>
          <el-button @click="remove" type="danger">{{ t('toolbar.remove') }}</el-button>
          <el-button @click="history">{{ t('toolbar.history') }}</el-button>
          <el-dropdown style="margin-left: 7px">
            <el-button type="primary">
              {{ t('toolbar.userGuide') }}<el-icon class="el-icon--right"><arrow-down /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleDownload">{{ t('toolbar.downloadExample') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <SimulationTaskProgress
            v-if="showTaskProgress"
            :status="taskStatus"
            :stage="taskStage"
            :progress="taskProgress"
            :message="taskMessage"
            :model-elapsed-seconds="modelElapsedSeconds"
            :model-total-seconds="modelTotalSeconds"
          />
        </span>
        <LanguageSwitcher class="mx-table-operation-language" />
      </div>
    </div>
    <div class="guiWrapper" id="guiWrapper"></div>
    <CesiumMap ref="activeMap" :publicMode="publicMode">
      <BottomRightContainer>
        <Legend v-if="showLegend" ref="LegendRef" :colors="legendColor" :range="legnedRange" :unit="t('toolbar.legendUnit')" />
      </BottomRightContainer>
    </CesiumMap>
    <RegionManageDialog ref="regionRef" :publicMode="publicMode" :title="t('region.title')" />
    <ModelParamDialog ref="paramRef" :publicMode="publicMode" :title="title" :params="param.defaultParam" @updateParam="updateModelParam" />
    <UrbanFloodHistoryDialog ref="historyRef" :publicMode="publicMode" @loadHistoryResult="handleHistoryResultLoad" />
  </div>
</template>

<script setup lang="js">
import CesiumMap from "@/views/warningModel/components/CesiumMap.vue";
import RegionManageDialog from "./components/RegionManageDialog.vue";
import ModelParamDialog from "./components/ModelParamDialog.vue";
import UrbanFloodHistoryDialog from "./components/UrbanFloodHistoryDialog.vue";
import { onMounted, onUnmounted, ref, getCurrentInstance, reactive, watch, nextTick, computed } from 'vue';
import { useRoute } from 'vue-router';
import GUI from 'lil-gui';
import { JSONParse } from '@/utils';
import debounce from "lodash/debounce";
import useModel from "@/mixins/useModel";
const Cesium = window.Cesium;
import BottomRightContainer from "@/components/common/bottom-right-container/index.vue";
import Legend from "@/components/common/simulate-legend/index.vue";

import PipeLine from "@/views/onemap/preview-map/CesiumLib/CesiumExtClass/Simulation/UrbanFLood/PipeLine"
import {simulateMqttStore} from "@/store/MqttStore";
import { ensureSessionId } from '@/utils/public-session';
import MqttIndicator from "@/components/common/mqtt-indicator/index.vue";
import SimulationTaskProgress from "@/components/common/simulation-task-progress/index.vue";
import LanguageSwitcher from "@/components/common/language-switcher/index.vue";
import { useI18n } from '@/i18n';

const { proxy } = getCurrentInstance();
const { API, MxMsg } = proxy;
const { locale, t } = useI18n();
const { downloadExample } = useModel({ proxy: proxy });
const route = useRoute();
const publicMode = computed(() => route.meta?.publicEntry === true);

const mapAPI = ref();

const PipeLineIns = ref()
const currentWaterCrs = ref(null)
const buildingWhiteModelSource = ref(null)

const param = reactive({
  defaultParam: {
    'simulationName': {
      type: 'other',
      label: t('parameter.simulationName'),
      value: ''
    },
    'simulationDuration': {
      type: 'number',
      label: t('parameter.durationMinutes'),
      value: 60
    },
    "crs": {
      type: 'other',
      label: t('parameter.crs'),
      value: "EPSG:32650"
    },
  },
  curParam: {},
});
const title = computed(() => t('parameter.dialogTitle'));

const applyLocalizedParameterLabels = () => {
  param.defaultParam.simulationName.label = t('parameter.simulationName');
  param.defaultParam.simulationDuration.label = t('parameter.durationMinutes');
  param.defaultParam.crs.label = t('parameter.crs');
};

const localizedErrorDetail = (error) => {
  const detail = error?.message || '';
  return detail && !/[\u3400-\u9fff]/.test(detail) ? detail : t('common.unknown');
};

const localizedServerMessage = (message, stage) => {
  if (locale.value === 'zh-CN' && message) return message;
  return t(`task.stage.${stage || 'default'}`);
};

let unsubscribe = null;
const showLegend = ref(false);
const legnedRange = ref([0, 1])
const legendColor = ref(['#FF0000', '#FFCC00', '#33FF00', '#00FF66', '#0066FF'])

const normalizeCrsValue = (value, fallback = 'EPSG:32650') => {
  if (value === null || value === undefined) return fallback;

  if (typeof value === 'number') {
    const code = value.toString();
    return code.startsWith('EPSG:') ? code : `EPSG:${code}`;
  }

  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return fallback;
    if (/^EPSG:\d+$/i.test(trimmed)) return trimmed.toUpperCase();
    if (/^\d{4,5}$/.test(trimmed)) return `EPSG:${trimmed}`;
    return trimmed;
  }

  return fallback;
};

const extractWaterCrs = (metadataOrPayload) => {
  return normalizeCrsValue(
    metadataOrPayload?.crs ||
    metadataOrPayload?.metadata?.crs ||
    metadataOrPayload?.metadata?.epsg ||
    metadataOrPayload?.metadata?.projection ||
    metadataOrPayload?.metadata?.grid_info?.crs,
    param.defaultParam.crs.value || 'EPSG:32650'
  );
};

onMounted(() => {
  nextTick(async () => {
    mapAPI.value = proxy.$refs.activeMap.mapAPI

    initView()

    try {
      PipeLineIns.value = new PipeLine({
        viewer: mapAPI.value.viewer,
        pipeJSON: null,
        skipDefaultLoad: true
      });
    } catch (error) {
      console.error('PipeLine实例初始化失败:', error);
    }

    const buildingTilesetUrl = import.meta.env.VITE_BUILDING_TILESET_URL;
    if (buildingTilesetUrl) {
      try {
        const tileset = await Cesium.Cesium3DTileset.fromUrl(buildingTilesetUrl);
        mapAPI.value.viewer.scene.primitives.add(tileset);
      } catch (error) {
        console.warn('可选建筑 3D Tiles 加载失败，继续使用基础地图', error);
      }
    }

  })
  const wsStore = simulateMqttStore();
  wsStore.ensureConnected();
  unsubscribe = wsStore.subscribe((message) => {
    try {
      handleSimulationEvent(JSON.parse(message));
    } catch (error) {
      console.error('解析 MQTT 模拟消息失败:', error);
    }
  });
});

onUnmounted(() => {
  if (unsubscribe) {
    unsubscribe();
    unsubscribe = null;
  }
  removeBuildingWhiteModels();
});

const initView = () => {
  window.addEventListener('message', (event) => {
    if (typeof event.data !== 'string') return
    const viewParam = JSONParse(event.data)
    mapAPI.value.viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(viewParam.lon, viewParam.lat, viewParam.height),
      orientation: {
        heading: Cesium.Math.toRadians(viewParam.heading),
        pitch: Cesium.Math.toRadians(viewParam.pitch),
        roll: Cesium.Math.toRadians(viewParam.roll),
      },
    })
  });
}

function openRegionDialog() {
  proxy.$refs.regionRef.dialogVisible = true;
  proxy.$refs.regionRef.refreshRegionList();
}

function openParamDialog() {
  proxy.$refs.paramRef.dialogVisible = true;
}

function updateModelParam(newParam) {
  Object.keys(newParam).forEach(key => {
    param.curParam[key] = newParam[key].value;
  });
}

const simulationId = ref('');
const lastPipeJSON = ref(null);
const isRunning = ref(false);
const isStartingSimulation = ref(false);
const showTaskProgress = ref(false);
const taskStatus = ref('idle');
const taskStage = ref('PREPARING');
const taskProgress = ref(0);
const taskMessage = ref(t('task.status.idle'));
const modelElapsedSeconds = ref(null);
const modelTotalSeconds = ref(null);

const taskStageRank = {
  PREPARING: 1,
  MODEL_STARTING: 2,
  MODEL_RUNNING: 3,
  POST_PROCESSING: 4,
  SWMM_PROCESSING: 5,
  SWW_PROCESSING: 6,
  RESULT_LOADING: 7,
  RESULT_RENDERING: 8,
  RESULT_READY: 9,
  FAILED: 10,
};
let currentTaskStageRank = 0;

const resetTaskProgress = ({ visible = false } = {}) => {
  showTaskProgress.value = visible;
  taskStatus.value = 'idle';
  taskStage.value = 'PREPARING';
  taskProgress.value = 0;
  taskMessage.value = t('task.status.idle');
  modelElapsedSeconds.value = null;
  modelTotalSeconds.value = null;
  currentTaskStageRank = 0;
};

const updateTaskProgress = ({ stage = 'PREPARING', progress, message, status = 'running', elapsedSeconds, totalSeconds }) => {
  const nextStageRank = taskStageRank[stage] || 0;
  if (nextStageRank < currentTaskStageRank) return;

  const nextProgress = Number(progress);
  if (nextStageRank > currentTaskStageRank) {
    currentTaskStageRank = nextStageRank;
    taskStage.value = stage;
  }

  if (Number.isFinite(nextProgress)) {
    taskProgress.value = Math.max(taskProgress.value, Math.min(100, Math.max(0, nextProgress)));
  }
  if (message) taskMessage.value = message;
  if (status) taskStatus.value = status;
  if (Number.isFinite(Number(elapsedSeconds))) modelElapsedSeconds.value = Number(elapsedSeconds);
  if (Number.isFinite(Number(totalSeconds)) && Number(totalSeconds) > 0) modelTotalSeconds.value = Number(totalSeconds);
  showTaskProgress.value = true;
};

const updateProgressFromModelOutput = (content) => {
  const match = String(content || '').match(/Time\s*=\s*([0-9]+(?:\.[0-9]+)?)/);
  if (!match || !Number.isFinite(modelTotalSeconds.value) || modelTotalSeconds.value <= 0) return;

  const elapsedSeconds = Number(match[1]);
  const modelProgress = Math.min(100, elapsedSeconds / modelTotalSeconds.value * 100);
  updateTaskProgress({
    stage: 'MODEL_RUNNING',
    progress: 5 + modelProgress * 0.8,
    message: t('task.modelRunning'),
    elapsedSeconds,
    totalSeconds: modelTotalSeconds.value,
  });
};

const isActiveSimulationMessage = (data) => {
  if (!data?.simulationId) return false;
  if (simulationId.value) return data.simulationId === simulationId.value;
  return isStartingSimulation.value;
};

const handleSimulationEvent = (data) => {
  if (data?.type === 'heartbeat_pong' || !isActiveSimulationMessage(data)) return;

  if (!simulationId.value && data.simulationId) simulationId.value = data.simulationId;

  if (data.buildingModelData) {
    renderBuildingWhiteModels(data.buildingModelData);
  }

  if (data.type === 'STATUS') {
    updateTaskProgress({
      stage: data.stage || 'MODEL_STARTING',
      progress: data.progress,
      message: localizedServerMessage(data.message, data.stage || 'MODEL_STARTING'),
      elapsedSeconds: data.modelElapsedSeconds,
      totalSeconds: data.modelTotalSeconds,
    });
    return;
  }

  if (data.type === 'PROGRESS') {
    updateTaskProgress({
      stage: data.stage || 'MODEL_RUNNING',
      progress: data.progress,
      message: localizedServerMessage(data.message, data.stage || 'MODEL_RUNNING'),
      elapsedSeconds: data.modelElapsedSeconds,
      totalSeconds: data.modelTotalSeconds,
    });
    return;
  }

  if (data.type === 'OUTPUT' || data.type === 'MODEL_OUTPUT') {
    const output = data.content || data.message;
    if (String(output || '').trim() === '[SUCCESS] Model execution completed successfully.') {
      updateTaskProgress({ stage: 'RESULT_LOADING', progress: 97, message: t('task.successDetected') });
    } else {
      updateProgressFromModelOutput(output);
    }
    return;
  }

  if (data.type === 'COMPLETION' && data.status === 'SUCCESS') {
    updateTaskProgress({ stage: 'RESULT_RENDERING', progress: 98, message: t('task.loadingVisualization') });
    void handleSimulationComplete(data);
    return;
  }

  if (data.type === 'ERROR') {
    isRunning.value = false;
    updateTaskProgress({
      stage: 'FAILED',
      progress: data.progress,
      message: locale.value === 'zh-CN' && data.message ? data.message : t('task.taskFailed'),
      status: 'failed',
    });
    MxMsg.error(locale.value === 'zh-CN' && data.message ? data.message : t('message.simulationFailed'));
  }
};

const debouncedStartSimulation = debounce(async () => {
  if (isRunning.value) {
    MxMsg.warning(t('message.alreadyRunning'));
    return;
  }

  try {
    isRunning.value = true;
    await simulation();
  } catch (error) {
    MxMsg.error(t('message.startFailed'));
    isRunning.value = false;
  }
}, 200, { leading: true, trailing: false });

async function simulation() {
  const regionId = proxy.$refs.paramRef.getSelectedRegionId();
  if (!regionId) {
    MxMsg.warning(t('message.selectInitializedRegion'));
    isRunning.value = false;
    return;
  }

  const rainfallFile = proxy.$refs.paramRef.getRainfallFile();
  if (!rainfallFile) {
    MxMsg.warning(t('message.uploadRainfall'));
    isRunning.value = false;
    return;
  }

  const formData = new FormData();
  removeBuildingWhiteModels();

  formData.append('csvFile', rainfallFile);

  const simulationParams = {
    regionId: regionId,
    simulationName: param.defaultParam.simulationName.value || t('toolbar.title').replace(/[：:]$/, ''),
    saveData: true,
    simulationDuration: param.defaultParam.simulationDuration.value || 60.0,
    sessionId: ensureSessionId(),
  };
  formData.append('bo', new Blob([JSON.stringify(simulationParams)], { type: 'application/json' }));

  simulationId.value = '';
  isStartingSimulation.value = true;
  resetTaskProgress({ visible: true });
  updateTaskProgress({
    stage: 'PREPARING',
    progress: 1,
    message: t('task.submitting'),
    totalSeconds: Number(simulationParams.simulationDuration) * 60,
  });

  try {
    const res = await (publicMode.value ? API.publicFloodRegionSimulate : API.floodRegionSimulate)({ data: formData });
    simulationId.value = res.data;
    isStartingSimulation.value = false;
    MxMsg.success(t('message.simulationStarted'));

  } catch (error) {
    console.error('模拟启动失败:', error);
    MxMsg.error(t('message.startFailed'));
    isStartingSimulation.value = false;
    isRunning.value = false;
    updateTaskProgress({ stage: 'FAILED', message: t('task.startFailed'), status: 'failed' });
  }
}


let gui
let guiTimeControl
const callBackValues = reactive({
  autoPlay: !true,
  time: 0,
  alpha: 0.0,
  select: 'pipeCapacity',
  renderSpeed: 1
})
const timer = ref()
const timerIdx = ref(0)
// const components = ["Capacity", "Flow_velocity", "Flow_rate", "Flow_depth"]
const renderComponentOptions = computed(() => ({
  [t('gui.pipeCapacity')]: 'pipeCapacity',
  [t('gui.averageVelocity')]: 'averageVelocity',
  [t('gui.flowRate')]: 'flowRate',
  [t('gui.waterDepth')]: 'waterDepth',
}))
const renderComponentValues = ['pipeCapacity', 'averageVelocity', 'flowRate', 'waterDepth']

const timelineState = reactive({
  waterFrames: 0,
  swmmSteps: 0,
  stepRatio: 5,
  maxMinuteIdx: 46
})
const timeControlState = reactive({
  minuteIdx: 0
})

const clampIdx = (val, min = 0, max = 0) => {
  if (!Number.isFinite(val)) return min
  if (!Number.isFinite(max)) return min
  return Math.min(Math.max(Math.round(val), min), max)
}

const deriveStepRatio = (waterFrames, swmmSteps, timeKeys = []) => {
  if (waterFrames > 0 && swmmSteps > 0) {
    const ratio = waterFrames / swmmSteps
    if (Number.isFinite(ratio) && ratio > 0) return Math.max(1, Math.round(ratio))
  }

  const numericKeys = (timeKeys || []).map(k => Number(k)).filter(n => Number.isFinite(n))
  if (numericKeys.length >= 2) {
    const diffs = numericKeys
      .slice(1)
      .map(n => Math.abs(n - numericKeys[0]))
      .filter(d => d > 0)
    if (diffs.length) {
      const minDiff = Math.min(...diffs)
      if (minDiff >= 60 && Number.isFinite(minDiff / 60)) return Math.max(1, Math.round(minDiff / 60))
      if (Number.isFinite(minDiff)) return Math.max(1, Math.round(minDiff))
    }
  }

  return 5
}

const recalcTimelineState = () => {
  const waterFrames = PipeLineIns.value?.waterIns?.waterHeightTexs?.length || 0
  const swmmSteps = PipeLineIns.value?.PipelineRendererIns?.timeKeys?.length || 0
  const stepRatio = deriveStepRatio(waterFrames, swmmSteps, PipeLineIns.value?.PipelineRendererIns?.timeKeys)

  const maxMinuteIdx =
    waterFrames > 0
      ? waterFrames - 1
      : swmmSteps > 0
        ? swmmSteps * stepRatio - 1
        : timelineState.maxMinuteIdx

  timelineState.waterFrames = waterFrames
  timelineState.swmmSteps = swmmSteps
  timelineState.stepRatio = stepRatio
  timelineState.maxMinuteIdx = maxMinuteIdx
}

const applyTimeIndex = (nextMinuteIdx = 0) => {
  recalcTimelineState()

  const safeMax = Math.max(0, timelineState.maxMinuteIdx)
  const clampedMinute = clampIdx(nextMinuteIdx, 0, safeMax)
  timeControlState.minuteIdx = clampedMinute

  if (PipeLineIns.value?.waterIns) {
    const waterIdx = timelineState.waterFrames > 0
      ? Math.min(timelineState.waterFrames - 1, clampedMinute)
      : clampedMinute
    PipeLineIns.value.waterIns.dataSetIdx = waterIdx
  }

  if (PipeLineIns.value?.PipelineRendererIns) {
    const stepRatio = timelineState.stepRatio || 1
    const swmmIdx = timelineState.swmmSteps > 0
      ? Math.min(timelineState.swmmSteps - 1, Math.floor(clampedMinute / stepRatio))
      : clampedMinute
    PipeLineIns.value.PipelineRendererIns.timeIdx = swmmIdx
  }
}

const refreshGuiTimeControl = () => {
  if (!guiTimeControl) return
  recalcTimelineState()
  const safeMax = Math.max(0, timelineState.maxMinuteIdx)
  guiTimeControl.max(safeMax)
  const clamped = clampIdx(timeControlState.minuteIdx, 0, safeMax)
  guiTimeControl.setValue(clamped)
}
const initGUI = () => {
  if (!PipeLineIns.value || !PipeLineIns.value.PipelineRendererIns || !PipeLineIns.value.waterIns) {
    console.warn('PipeLine实例未初始化，跳过GUI初始化');
    return;
  }

  destroyGUI()
  gui = new GUI({ container: document.getElementById('guiWrapper') });
  gui.title(t('gui.title'))
  recalcTimelineState()
  const maxTimeIdx = Math.max(0, timelineState.maxMinuteIdx)
  guiTimeControl = gui.add(timeControlState, 'minuteIdx', 0, maxTimeIdx, 1).name(t('gui.time')).onChange(value => {
    applyTimeIndex(value)
  })
  applyTimeIndex(timeControlState.minuteIdx)
  gui.add(callBackValues, 'autoPlay').name(t('gui.autoplay')).onChange(value => {
    if (value) {
      if (!timer.value) {
        recalcTimelineState()
        timerIdx.value = timeControlState.minuteIdx
        timer.value = setInterval(() => {
          const loopMax = Math.max(0, timelineState.maxMinuteIdx)
          timerIdx.value++
          if (timerIdx.value > loopMax) timerIdx.value = 0
          guiTimeControl.setValue(timerIdx.value)
        }, 500 / callBackValues.renderSpeed);
      }
    }
    else {
      clearInterval(timer.value)
      timer.value = null
    }
  })
  gui.add(callBackValues, "renderSpeed", 0, 5, 0.5).name(t('gui.speed')).onChange(value => {
    if (value) {
      if (callBackValues.autoPlay) {
        if (timer.value)
          clearInterval(timer.value)
        recalcTimelineState()
        timerIdx.value = timeControlState.minuteIdx
        timer.value = setInterval(() => {
          const loopMax = Math.max(0, timelineState.maxMinuteIdx)
          timerIdx.value++
          if (timerIdx.value > loopMax) timerIdx.value = 0
          guiTimeControl.setValue(timerIdx.value)
        }, 500 / callBackValues.renderSpeed);
      }
    }
    else {
      clearInterval(timer.value)
      timer.value = null
    }
  })
  // .onChange(value => {
  //   mapAPI.value.viewer.clock.currentTime = Cesium.JulianDate.addHours(Cesium.JulianDate.now(new Date('2025-01-01T00:00:00Z')), value, new Cesium.JulianDate());
  // });
  gui.add(PipeLineIns.value.PipelineRendererIns, 'renderAttr').name(t('gui.overlayColor'))
  gui.add(callBackValues, 'select', renderComponentOptions.value).name(t('gui.dataType')).onChange(value => {
    PipeLineIns.value.PipelineRendererIns.renderComponent = renderComponentValues.indexOf(value)
  })

  gui.add(PipeLineIns.value.PipelineRendererIns, 'showFlow').name(t('gui.flowEffect'))
  gui.add(PipeLineIns.value.PipelineRendererIns, 'roughness', 0, 1, 0.1).name(t('gui.roughness'))

  const waterIns = PipeLineIns.value.waterIns
  gui.add(waterIns, 'renderParticle').name(t('gui.waterParticles'))
  gui.add(waterIns, 'renderHeatMap').name(t('gui.waterHeatmap'))

  gui.add(waterIns, "renderVectorField").name(t('gui.vectorField'))
  gui.add(waterIns, "spawnRate", 0, 0.4, 0.005).name(t('gui.vectorDensity'))
  gui.add(waterIns, 'renderOriginData').name(t('gui.rawWaterData'))

  const maxWaterDepth = Math.max(Number(waterIns.maxWaterDepth) || 1, 0.01)
  if (!Number.isFinite(Number(waterIns.maxDepthThreshold)) || waterIns.maxDepthThreshold <= 0) {
    waterIns.maxDepthThreshold = maxWaterDepth
  }
  const minDepthController = gui.add(waterIns, 'minDepthThreshold', 0, maxWaterDepth, 0.01).name(t('gui.minDepth'))
  const maxDepthController = gui.add(waterIns, 'maxDepthThreshold', 0, maxWaterDepth, 0.01).name(t('gui.maxDepth'))
  minDepthController.onChange(value => {
    if (value > waterIns.maxDepthThreshold) {
      waterIns.maxDepthThreshold = value
      maxDepthController.updateDisplay()
    }
  })
  maxDepthController.onChange(value => {
    if (value < waterIns.minDepthThreshold) {
      waterIns.minDepthThreshold = value
      minDepthController.updateDisplay()
    }
  })

  mapAPI.value.viewer.scene.globe.translucency.enabled = true

  mapAPI.value.viewer.scene.globe.translucency.frontFaceAlphaByDistance = new Cesium.NearFarScalar(
    400.0,
    0.0,
    16800.0,
    1.0,
  );
  mapAPI.value.viewer.scene.globe.translucency.frontFaceAlphaByDistance.nearValue = 0.0;
  mapAPI.value.viewer.scene.globe.translucency.frontFaceAlphaByDistance.farValue = 0.0


  gui.add(callBackValues, 'alpha', 0, 1, 0.1).name(t('gui.surfaceOpacity')).onChange(value => {
    const globe = mapAPI.value.viewer.scene.globe;
    if (!globe.translucency) return;
    globe.translucency.enabled = true;
    if (!globe.translucency.frontFaceAlphaByDistance) {
      globe.translucency.frontFaceAlphaByDistance = new Cesium.NearFarScalar(0.0, value, 1.0e7, value);
    } else {
      globe.translucency.frontFaceAlphaByDistance.nearValue = value;
      globe.translucency.frontFaceAlphaByDistance.farValue = value;
    }
  });

}

const destroyGUI = () => {
  gui && gui.destroy()
}

const rendering = ref(false)

const ensurePipeLineInstance = () => {
  if (PipeLineIns.value) return true;

  if (!mapAPI.value?.viewer) {
    console.error('Cesium viewer未初始化，无法创建城市洪水渲染器');
    return false;
  }

  try {
    PipeLineIns.value = new PipeLine({
      viewer: mapAPI.value.viewer,
      pipeJSON: null,
      skipDefaultLoad: true
    });
    return true;
  } catch (error) {
    console.error('创建PipeLine实例失败:', error);
    MxMsg.error(t('message.rendererInitFailed'));
    return false;
  }
}

const getCesiumPropertyValue = (properties, key, fallback = undefined) => {
  if (!properties || !key) return fallback;
  if (!properties[key] && typeof properties.getValue === 'function') {
    const values = properties.getValue(Cesium.JulianDate.now());
    return values?.[key] === undefined || values?.[key] === null ? fallback : values[key];
  }
  if (!properties[key]) return fallback;
  const prop = properties[key];
  if (typeof prop.getValue === 'function') {
    const value = prop.getValue(Cesium.JulianDate.now());
    return value === undefined || value === null ? fallback : value;
  }
  return prop === undefined || prop === null ? fallback : prop;
}

const removeBuildingWhiteModels = () => {
  const viewer = mapAPI.value?.viewer;
  const dataSource = buildingWhiteModelSource.value;
  if (!viewer || !dataSource) {
    buildingWhiteModelSource.value = null;
    return;
  }

  try {
    if (viewer.dataSources.contains(dataSource)) {
      viewer.dataSources.remove(dataSource, true);
    }
  } catch (error) {
    console.warn('移除建筑白模失败:', error);
  } finally {
    buildingWhiteModelSource.value = null;
  }
}

const renderBuildingWhiteModels = async (buildingModelData) => {
  const viewer = mapAPI.value?.viewer;
  if (!viewer) return;

  const features = buildingModelData?.features;
  removeBuildingWhiteModels();
  if (!Array.isArray(features) || features.length === 0) return;

  try {
    const dataSource = await Cesium.GeoJsonDataSource.load(buildingModelData, {
      clampToGround: false
    });
    dataSource.name = 'urban-flood-building-white-models';

    const defaultHeight = Number(buildingModelData.defaultHeight ?? 18);
    const entities = dataSource.entities.values || [];
    entities.forEach(entity => {
      if (!entity.polygon) return;
      const rawHeight = Number(getCesiumPropertyValue(entity.properties, 'height', defaultHeight));
      const height = Number.isFinite(rawHeight) && rawHeight > 0 ? rawHeight : defaultHeight;
      entity.polygon.height = 0;
      entity.polygon.heightReference = Cesium.HeightReference.CLAMP_TO_GROUND;
      entity.polygon.extrudedHeight = height;
      entity.polygon.extrudedHeightReference = Cesium.HeightReference.RELATIVE_TO_GROUND;
      entity.polygon.material = Cesium.Color.WHITE.withAlpha(1);
      entity.polygon.outline = true;
      entity.polygon.outlineColor = Cesium.Color.WHITE.withAlpha(1);
      entity.polygon.outlineWidth = 1;
      entity.polygon.shadows = Cesium.ShadowMode.DISABLED;
    });

    await viewer.dataSources.add(dataSource);
    buildingWhiteModelSource.value = dataSource;
    viewer.scene.requestRender?.();
  } catch (error) {
    console.warn('加载建筑白模失败:', error);
  }
}
const handleSimulationComplete = async (data) => {
  try {
    updateTaskProgress({ stage: 'RESULT_RENDERING', progress: 98, message: t('task.loadingVisualization') });

    const resultData = data.resultData;
    if (!resultData) {
      console.warn('完成消息中没有结果数据');
      MxMsg.warning(t('message.noResultData'));
      isRunning.value = false;
      updateTaskProgress({ stage: 'FAILED', message: t('message.noResultData'), status: 'failed' });
      return;
    }

    let hasSww = false;
    let hasSwmm = false;

    await renderBuildingWhiteModels(resultData.buildingModelData);

    if (resultData.swwData) {
      updateTaskProgress({ stage: 'RESULT_RENDERING', progress: 98.3, message: t('task.loadingWater') });
      try {
        hasSww = await handleSWWData(resultData.swwData);
      } catch (error) {
        console.error('处理SWW数据失败:', error);
        MxMsg.error(t('message.waterLoadFailed'));
      }
    }

    if (resultData.simulationResults) {
      updateTaskProgress({ stage: 'RESULT_RENDERING', progress: 98.8, message: t('task.loadingPipe') });
      try {
        const swmmPayload = {
          ...resultData.simulationResults,
          inpContent: resultData.inpContent || resultData.simulationResults?.inpContent,
          inpFileName: resultData.inpFileName || resultData.simulationResults?.inpFileName,
          inpFilePath: resultData.inpFilePath || resultData.simulationResults?.inpFilePath,
          crs: resultData.crs || resultData.simulationResults?.crs
        };
        hasSwmm = await handleSWMMResults(swmmPayload);
      } catch (error) {
        console.error('========== 管网数据处理失败 ==========');
        console.error('错误详情:', error);
        console.error('堆栈:', error.stack);
        MxMsg.error(t('message.pipeLoadFailed', { error: localizedErrorDetail(error) }));
      }
    }

    if (hasSww && hasSwmm) {
      MxMsg.success(t('message.bothLoaded'));
    } else if (hasSww) {
      MxMsg.success(t('message.waterLoaded'));
    } else if (hasSwmm) {
      MxMsg.success(t('message.pipeLoaded'));
    } else {
      MxMsg.warning(t('message.noVisualization'));
    }

    if (hasSww || hasSwmm) {
      await flyToSimulationArea();
    }

    const swwData = resultData.swwData;
    const maxValues = swwData?.metadata?.max_values;
    const xMomentum = Number(maxValues?.x_momentum ?? swwData?.maxXMomentum);
    const yMomentum = Number(maxValues?.y_momentum ?? swwData?.maxYMomentum);
    const validMomentumValues = [xMomentum, yMomentum].filter(Number.isFinite);

    if (hasSww && validMomentumValues.length > 0) {
      legnedRange.value = [0, Math.max(...validMomentumValues)];
      showLegend.value = true;
    } else if (!hasSww) {
      showLegend.value = false;
    }

    isRunning.value = false;
    updateTaskProgress({ stage: 'RESULT_READY', progress: 100, message: t('task.resultReady'), status: 'succeed' });

  } catch (error) {
    console.error('处理模拟结果失败:', error);
    MxMsg.error(t('message.processResultFailed', { error: localizedErrorDetail(error) }));
    isRunning.value = false;
    updateTaskProgress({ stage: 'FAILED', message: t('task.resultLoadFailed', { error: localizedErrorDetail(error) }), status: 'failed' });
  }
};
const flyToSimulationArea = async () => {
  try {
    if (!mapAPI.value || !mapAPI.value.viewer) {
      console.warn('Cesium viewer未初始化,无法飞行');
      return;
    }

    const viewer = mapAPI.value.viewer;

    const waterCenter = PipeLineIns.value?.waterIns?.center;
    if (waterCenter) {
      const carto = Cesium.Cartographic.fromCartesian(waterCenter);
      const lon = Cesium.Math.toDegrees(carto.longitude);
      const lat = Cesium.Math.toDegrees(carto.latitude);
      viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, 5000),
        duration: 2.0
      });
      return;
    }

    if (lastPipeJSON.value) {
      const { junctions = {}, outfalls = {} } = lastPipeJSON.value;
      const coords = [];
      Object.values(junctions).forEach(j => {
        if (isFinite(j.longitude) && isFinite(j.latitude)) coords.push([j.longitude, j.latitude]);
      });
      Object.values(outfalls).forEach(o => {
        if (isFinite(o.longitude) && isFinite(o.latitude)) coords.push([o.longitude, o.latitude]);
      });
      if (coords.length > 0) {
        const lons = coords.map(c => c[0]);
        const lats = coords.map(c => c[1]);
        const lon = (Math.min(...lons) + Math.max(...lons)) / 2;
        const lat = (Math.min(...lats) + Math.max(...lats)) / 2;
        viewer.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(lon, lat, 5000),
          duration: 2.0
        });
        return;
      }
    }

    const defaultPosition = { lon: 118.68, lat: 32.08, height: 5000 };
    viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(defaultPosition.lon, defaultPosition.lat, defaultPosition.height),
      duration: 2.0
    });
  } catch (error) {
    console.error('飞行到模拟区域失败:', error);
  }
};
const handleSWWData = async (swwData) => {

  if (!swwData || swwData.status !== 'success') {
    console.error('SWW数据无效或处理失败:', swwData);
    return false;
  }

  const { imageFrames, metadata } = swwData;

  if (!imageFrames || imageFrames.length === 0) {
    console.warn('没有可用的SWW图像帧');
    return false;
  }

  if (!PipeLineIns.value) {
    console.error('PipeLine实例未初始化');
    if (!ensurePipeLineInstance()) return false;
  }

  try {
    const waterCrs = extractWaterCrs(swwData);
    currentWaterCrs.value = waterCrs;
    await PipeLineIns.value.loadWaterData({ imageFrames, metadata, crs: waterCrs });
  } catch (error) {
    console.error('加载水体数据失败:', error);
    MxMsg.error(t('message.waterDataLoadFailed', { error: localizedErrorDetail(error) }));
    return false;
  }

  recalcTimelineState();
  refreshGuiTimeControl();
  applyTimeIndex(timeControlState.minuteIdx);

  MxMsg.success(t('message.waterFramesLoaded', { count: imageFrames.length }));

  await flyToSimulationArea();
  return true;
};
const handleSWMMResults = async (results) => {

  if (!results || (!results.fileName && !results.timeBasedData && !results.objectBasedData)) {
    console.warn('管网结果数据无效');
    return false;
  }

  let pipeJSON = null;
  const inpContent = results.inpContent || results.inpFileContent || results.inpText;
  const inpCrs = normalizeCrsValue(
    currentWaterCrs.value ||
    results.crs ||
    results.projection ||
    results.epsg ||
    param.curParam?.crs ||
    param.defaultParam.crs.value,
    'EPSG:32650'
  );
  if (inpContent) {
    try {
      pipeJSON = await PipeLine.parseSWMM(inpContent, inpCrs);
      lastPipeJSON.value = pipeJSON;
    } catch (error) {
      console.error('解析INP文件失败, 管网可能无法渲染:', error);
    }
  } else {
    console.warn('SWMM结果未包含INP内容, 管网拓扑无法自动构建');
  }

  const processor = results.processor || 'unknown';

  let swmmData = {
    summary: results.summary || {},
    timeBasedData: results.timeBasedData || {},
    objectBasedData: results.objectBasedData || {},
    timeSeriesData: results.timeSeriesData || results.timeBasedData,
    objectData: results.objectData || results.objectBasedData,
    processor: processor
  };

  if (processor === 'java-native' && results.summary) {

    if (results.timeBasedData) {
      swmmData.timeSeriesData = results.timeBasedData;
    }

    if (results.objectBasedData) {
      swmmData.objectData = results.objectBasedData;
    }
  }
  else if (processor === 'history-loader') {

    if (results.timeBasedData) {
      swmmData.timeSeriesData = results.timeBasedData;
    }

    if (results.objectBasedData) {
      swmmData.objectData = results.objectBasedData;
    }
  }
  else if (results.fallbackData) {
    swmmData = results.fallbackData;
  }

  if (!swmmData || (!swmmData.timeSeriesData && !swmmData.objectData)) {
    console.error('无法获取有效的SWMM数据');
    MxMsg.error(t('message.swmmParseFailed'));
    return false;
  }

  if (!PipeLineIns.value && !ensurePipeLineInstance()) return false;

  const rendered = await renderPipelineData(swmmData, results, pipeJSON);
  if (!rendered) return false;

  await flyToSimulationArea();

  MxMsg.success(t('message.swmmLoaded'));
  return true;
};
const renderPipelineData = async (swmmData, rawResults, pipeJSON = null) => {

  if (!PipeLineIns.value) {
    console.warn('PipeLine实例未初始化，无法渲染管网数据');
    return false;
  }

  if (pipeJSON) {
    try {
      PipeLineIns.value.pipeJSON = pipeJSON;
      if (PipeLineIns.value.PipelineRendererIns) {
        mapAPI.value?.viewer?.scene?.primitives?.remove(PipeLineIns.value.PipelineRendererIns);
        PipeLineIns.value.PipelineRendererIns = null;
      }
    } catch (error) {
      console.warn('设置管网拓扑失败, 将尝试继续加载时间序列数据:', error);
    }
  }

  try {
    if (swmmData.timeSeriesData || swmmData.objectData) {
      await PipeLineIns.value.loadPipelineData(swmmData);
    } else {
      console.warn('没有可用的管网数据');
    }

    if (swmmData.objectData && Object.keys(swmmData.objectData).length > 0) {

      if (PipeLineIns.value.PipelineRendererIns &&
        typeof PipeLineIns.value.PipelineRendererIns.updateObjectData === 'function') {
        PipeLineIns.value.PipelineRendererIns.updateObjectData(swmmData.objectData);
      } else {
        console.warn('PipelineRendererIns.updateObjectData 方法不存在');
        PipeLineIns.value.swmmObjectData = swmmData.objectData;
      }
    }

    if (typeof initGUI === 'function') {
      initGUI();
    }

    return true;
  } catch (error) {
    console.error('渲染管网数据时发生错误:', error);
    MxMsg.error(t('message.pipeRenderFailed', { error: localizedErrorDetail(error) }));
    return false;
  }
};

function remove() {
  const viewer = mapAPI.value?.viewer;

  if (timer.value) {
    clearInterval(timer.value);
    timer.value = null;
  }
  callBackValues.autoPlay = false;

  destroyGUI();
  removeBuildingWhiteModels();

  const safeRemovePrimitive = (primitive) => {
    try {
      if (primitive && viewer?.scene?.primitives?.contains(primitive)) {
        viewer.scene.primitives.remove(primitive);
      }
    } catch (e) {
      console.warn('移除渲染对象失败', e);
    }
  };

  if (PipeLineIns.value) {
    if (PipeLineIns.value.waterIns && typeof PipeLineIns.value.waterIns.remove === 'function') {
      try { PipeLineIns.value.waterIns.remove(); } catch (e) { console.warn('水体实例清理失败', e); }
    }

    safeRemovePrimitive(PipeLineIns.value.PipelineRendererIns);
    safeRemovePrimitive(PipeLineIns.value.waterIns);

    if (typeof PipeLineIns.value.destroy === 'function') {
      try { PipeLineIns.value.destroy(); } catch (e) { console.warn('销毁PipeLine实例失败', e); }
    }
  }

  if (PipeLineIns.value && PipeLineIns.value.waterIns) {
    PipeLineIns.value.waterIns = null;
  }
  PipeLineIns.value = null;
  lastPipeJSON.value = null;
  currentWaterCrs.value = null;

  rendering.value = false;
  isRunning.value = false;
  isStartingSimulation.value = false;
  resetTaskProgress();

  try {
    const globe = mapAPI.value?.viewer?.scene?.globe;
    if (globe && globe.translucency) {
      globe.translucency.enabled = false;
      globe.translucency.frontFaceAlphaByDistance = undefined;
    }
  } catch (e) {
    console.warn('重置地表透明度失败', e);
  }

  if (simulationId.value) {
    (publicMode.value ? API.publicSimulationHistoryStop : API.simulationHistoryStop)({
      data: {
        id: simulationId.value,
        sessionId: ensureSessionId(),
      }
    }).finally(() => {
      simulationId.value = '';
      MxMsg.success(t('message.stoppedAndCleared'));
    });
  } else {
    simulationId.value = '';
    MxMsg.success(t('message.renderCleared'));
  }
}

function history() {
  proxy.$refs.historyRef.open();
}
function handleHistoryResultLoad(resultData) {

  if (!resultData) {
    console.error('历史结果数据为空');
    MxMsg.error(t('message.historyEmpty'));
    return;
  }

  if (!resultData.swwData && !resultData.simulationResults) {
    console.warn('历史结果中既没有swwData也没有simulationResults');
    MxMsg.warning(t('message.historyNoVisualization'));
  }

  handleSimulationComplete({
    type: 'COMPLETION',
    status: 'SUCCESS',
    message: t('task.historyLoaded'),
    resultData: resultData
  });

}

const fileName = '城市洪涝示例文件.zip';
function handleDownload() {
  downloadExample(fileName);
}

watch(locale, () => {
  document.documentElement.lang = locale.value;
  document.title = t('app.title');
  applyLocalizedParameterLabels();
  taskMessage.value = showTaskProgress.value
    ? t(`task.stage.${taskStage.value}`)
    : t('task.status.idle');
  if (gui) initGUI();
}, { immediate: true });

</script>

<style scoped lang="scss">
.guiWrapper {
  position: absolute;
  z-index: 20;
}

</style>
