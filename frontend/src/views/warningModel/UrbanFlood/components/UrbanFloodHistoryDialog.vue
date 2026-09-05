<template>
  <el-dialog class="mx-dialog" width="48%" :title="t('history.title')" v-model="dialogVisible" :close-on-click-modal="false"
             @close="handleClose">
    <el-form ref="ruleForm" label-width="130px" style="margin-top: 10px" class="mx-rule-form">
      <el-divider content-position="left">{{ t('history.selection') }}</el-divider>
      <el-row :gutter="10">
        <el-col :span="24">
          <el-select v-model="curHistory.id" style="width: 100%" @change="changeHistory" :placeholder="t('history.selectRecord')">
            <el-option v-for="history in historyOpts" :key="history.id" :value="history.id"
                       :label="history.name">
            </el-option>
          </el-select>
        </el-col>
      </el-row>
      
      <template v-if="curHistory.id && historyDetail">
        <el-divider content-position="left" style="margin-top: 10px">{{ t('history.information') }}</el-divider>
        <el-row :gutter="10">
          <el-col :span="12">
            <el-form-item :label="t('history.simulationName')">
              <el-input v-model="historyDetail.simulationName" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('history.createTime')">
              <el-input v-model="historyDetail.createTime" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('history.status')">
              <el-tag :type="getStatusType(historyDetail.state)">
                {{ getStatusText(historyDetail.state) }}
              </el-tag>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="t('history.workspace')">
              <el-input v-model="workspaceStatus" disabled />
            </el-form-item>
          </el-col>
        </el-row>
      </template>
    </el-form>
    
    <template v-slot:footer>
      <span>
        <el-button @click="handleClose">{{ t('common.cancel') }}</el-button>
        <el-button type="success" @click="loadHistoryResult" :disabled="!curHistory.id || loading">
          <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
          {{ loading ? t('common.loading') : t('history.loadResult') }}
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, getCurrentInstance } from "vue";
import { Loading } from '@element-plus/icons-vue';
import { ensureSessionId } from '@/utils/public-session';
import { useI18n } from '@/i18n';

const { proxy } = getCurrentInstance();
const { API, MxMsg } = proxy;
const { locale, t } = useI18n();

const emit = defineEmits(['loadHistoryResult']);

const props = defineProps({
  publicMode: {
    type: Boolean,
    default: false,
  },
});

const dialogVisible = ref(false);
const loading = ref(false);

const curHistory = ref({
  name: "", 
  id: ""
});

const historyOpts = ref([]);
const historyDetail = ref(null);

const workspaceStatus = computed(() => {
  if (!historyDetail.value) return '';
  return historyDetail.value.workspacePath ? t('common.saved') : t('common.notSaved');
});

const getList = async () => {
  try {
    let urbanFloodCode = props.publicMode ? 5 : null;
    if (!props.publicMode) {
      const codeRes = await API.systemUpmsDictItems({
        data: {
          dictCode: 'MODEL_NAME',
        },
      });

      codeRes.data.forEach(element => {
        if (element.itemName === 'ANUGA_SWMM') {
          urbanFloodCode = parseInt(element.itemCode);
        }
      });
    }
    
    if (!urbanFloodCode) {
      MxMsg.error(t('history.noModelConfig'));
      return;
    }
    
    
    const historyRes = await (props.publicMode ? API.publicSimulationHistory : API.simulationHistory)({
      data: {
        "size": -1,
        "current": 1,
        "modelName": urbanFloodCode,
        "state": 1,
        "saveData": true,
        ...(props.publicMode ? { sessionId: ensureSessionId() } : {}),
      }
    });
    
    
    historyOpts.value = [];
    if (historyRes.data && historyRes.data.length > 0) {
      historyRes.data.forEach(element => {
        historyOpts.value.push({
          name: element.simulationName + ' - ' + element.createTime,
          id: element.id
        });
      });
    } else {
      MxMsg.info(t('history.noRecords'));
    }
  } catch (error) {
    console.error('获取历史记录失败:', error);
    MxMsg.error(t('history.getRecordsFailed'));
  }
};

const changeHistory = async (value) => {
  if (!value) {
    historyDetail.value = null;
    return;
  }
  
  try {
    const res = await (props.publicMode ? API.publicSimulationHistoryDetail : API.simulationHistoryDetail)({
      data: {
        "id": value,
        ...(props.publicMode ? { sessionId: ensureSessionId() } : {}),
      }
    });
    historyDetail.value = res.data;
  } catch (error) {
    console.error('获取历史详情失败:', error);
    MxMsg.error(t('history.getDetailFailed'));
  }
};

const loadHistoryResult = async () => {
  if (!curHistory.value.id) {
    MxMsg.warning(t('history.selectFirst'));
    return;
  }

  try {
    loading.value = true;
    MxMsg.info(t('history.loadingResult'));
    
    
    const res = await (props.publicMode ? API.publicUrbanFloodLoadHistory : API.urbanFloodLoadHistory)({
      data: {
        id: curHistory.value.id,
        ...(props.publicMode ? { sessionId: ensureSessionId() } : {}),
      }
    });


    dialogVisible.value = false;

    emit('loadHistoryResult', res.data);
    
    MxMsg.success(t('history.loadSuccess'));
  } catch (error) {
    console.error('加载历史结果失败:', error);
    const detail = locale.value === 'zh-CN' ? (error.message || t('common.unknown')) : t('common.unknown');
    MxMsg.error(t('history.loadFailed', { error: detail }));
  } finally {
    loading.value = false;
  }
};

const handleClose = () => {
  dialogVisible.value = false;
  curHistory.value = { name: "", id: "" };
  historyDetail.value = null;
};

const open = () => {
  dialogVisible.value = true;
  getList();
};

const getStatusType = (state) => {
  const statusMap = {
    0: 'info',
    2: 'warning',
    1: 'success',
    3: 'danger'
  };
  return statusMap[state] || 'info';
};

const getStatusText = (state) => {
  const textMap = {
    0: t('history.statePending'),
    2: t('history.stateRunning'),
    1: t('history.stateSuccess'),
    3: t('history.stateFailed')
  };
  return textMap[state] || t('common.unknown');
};

defineExpose({
  dialogVisible,
  open
});
</script>

<style scoped lang="scss">
.is-loading {
  animation: rotating 2s linear infinite;
}

@keyframes rotating {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}
</style>
