<template>
  <el-dialog class="mx-dialog mx-param-dialog" width="680px" :title="$props.title" v-model="dialogVisible" :close-on-click-modal="false"
    @close="close(false)">
    <el-form ref="ruleForm" class="mx-param-form" label-width="190px">
      <el-divider content-position="left">{{ t('parameter.basic') }}</el-divider>
      <el-form-item :label="tempParam.simulationName.label">
        <el-input v-model="tempParam.simulationName.value" :placeholder="t('parameter.inputPrefix') + tempParam.simulationName.label" />
      </el-form-item>
      <el-form-item :label="t('parameter.selectRegion')">
        <el-select v-model="selectedRegionId" :placeholder="t('parameter.selectInitializedRegion')" style="width: 100%">
          <el-option v-for="region in regionList" :key="region.id" :label="region.regionName" :value="region.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="tempParam.simulationDuration.label">
        <el-input-number v-model="tempParam.simulationDuration.value" :min="1" :max="1440" :step="1"
          style="width: 100%;" controls-position="right" />
      </el-form-item>
      <el-form-item :label="t('parameter.rainfallCsv')">
        <el-upload class="rainfall-upload" accept=".csv" :auto-upload="false"
          :show-file-list="false" :on-change="handleRainfallFile">
          <template #trigger>
            <el-input v-model="rainfallFileName" disabled :placeholder="t('parameter.uploadRainfallCsv')" />
            <el-button type="primary" icon="UploadFilled" />
          </template>
          <template #default>
            <el-button type="danger" icon="Delete" />
          </template>
        </el-upload>
      </el-form-item>
    </el-form>
    <template v-slot:footer>
      <span>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="close(true)">{{ t('common.confirm') }}</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="js">
import { ref, watch, getCurrentInstance } from "vue";
import { clone } from "lodash";
import { ensureSessionId } from '@/utils/public-session';
import { useI18n } from '@/i18n';

const { proxy } = getCurrentInstance();
const { API, MxMsg } = proxy;
const { locale, t } = useI18n();
const $props = defineProps({
  publicMode: {
    type: Boolean,
    default: false,
  },
  params: {
    type: Object,
    required: true
  },
  title: {
    type: String,
    required: true,
    default: ""
  }
});
const dialogVisible = ref(false);
const tempParam = ref(clone($props.params));
const curParam = ref(clone($props.params));
watch(() => $props.params, (newParam) => {
  curParam.value = clone(newParam);
  tempParam.value = clone(newParam);
}, {
  deep: true,
  immediate: true
});
const regionList = ref([]);
const selectedRegionId = ref(null);
const rainfallFileName = ref('');
const rainfallFile = ref(null);

const emit = defineEmits(["updateParam"]);
defineExpose({
  dialogVisible,
  getSelectedRegionId: () => selectedRegionId.value,
  getRainfallFile: () => rainfallFile.value,
});

watch(() => dialogVisible.value, (newVal) => {
  if (newVal) {
    loadRegionList();
  }
});

const loadRegionList = async () => {
  try {
    const res = await ($props.publicMode ? API.publicFloodRegionListInitialized : API.floodRegionList)(
      $props.publicMode ? { data: { sessionId: ensureSessionId() } } : undefined
    );
    if (res && res.data) {
      regionList.value = res.data;
    } else {
      regionList.value = [];
    }
  } catch (error) {
    const detail = locale.value === 'zh-CN' ? (error.message || t('common.unknown')) : t('common.unknown');
    MxMsg.error(t('message.regionListFailed', { error: detail }));
  }
};

const handleRainfallFile = (file) => {
  rainfallFileName.value = file.name;
  rainfallFile.value = file.raw;
};

function close(save) {
  dialogVisible.value = false;
  if (save) {
    curParam.value = clone(tempParam.value);
    emit("updateParam", clone(tempParam.value));
  } else {
    tempParam.value = clone(curParam.value);
  }
}
</script>

<style scoped lang="scss">
.mx-param-form {
  max-height: 500px;
}

.rainfall-upload {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  width: 100%;
}

.rainfall-upload :deep(.el-upload) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  width: 100%;
}

@media (max-width: 720px) {
  .mx-param-form {
    :deep(.el-form-item) {
      display: block;
    }

    :deep(.el-form-item__label) {
      width: auto !important;
      margin-bottom: 6px;
    }

    :deep(.el-form-item__content) {
      margin-left: 0 !important;
    }
  }
}
</style>
