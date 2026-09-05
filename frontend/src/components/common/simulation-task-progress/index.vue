<template>
  <div class="simulation-task-progress" :class="`status-${status}`">
    <div class="progress-header">
      <span class="progress-stage">{{ stageText }}</span>
      <span class="progress-value">{{ roundedProgress }}%</span>
    </div>
    <el-progress :percentage="roundedProgress" :status="progressStatus" :stroke-width="8" :show-text="false" />
    <div class="progress-message">{{ message || defaultMessage }}</div>
    <div v-if="hasModelTime" class="model-time">
      {{ t('task.calculated', { elapsed: formattedElapsed, total: formattedTotal, progress: roundedModelProgress }) }}
    </div>
  </div>
</template>

<script setup lang="js">
import { computed } from 'vue';
import { useI18n } from '@/i18n';

const { t } = useI18n();

const props = defineProps({
  status: { type: String, default: 'running' },
  stage: { type: String, default: 'PREPARING' },
  progress: { type: Number, default: 0 },
  message: { type: String, default: '' },
  modelElapsedSeconds: { type: Number, default: null },
  modelTotalSeconds: { type: Number, default: null },
});

const roundedProgress = computed(() => Math.round(Math.max(0, Math.min(100, Number(props.progress) || 0))));
const hasModelTime = computed(() => Number.isFinite(props.modelElapsedSeconds) && Number.isFinite(props.modelTotalSeconds) && props.modelTotalSeconds > 0);
const roundedModelProgress = computed(() => hasModelTime.value
  ? Math.round(Math.min(100, props.modelElapsedSeconds / props.modelTotalSeconds * 100))
  : 0);

const stageText = computed(() => t(`task.stage.${props.stage}`) === `task.stage.${props.stage}`
  ? t('task.stage.default')
  : t(`task.stage.${props.stage}`));

const defaultMessage = computed(() => t(`task.status.${props.status}`) === `task.status.${props.status}`
  ? t('task.status.idle')
  : t(`task.status.${props.status}`));

const progressStatus = computed(() => {
  if (props.status === 'failed') return 'exception';
  if (props.status === 'succeed') return 'success';
  return undefined;
});

const formatSeconds = (seconds) => {
  const value = Math.max(0, Math.round(Number(seconds) || 0));
  const minutes = Math.floor(value / 60);
  const remainSeconds = value % 60;
  return t('task.duration', { minutes, seconds: remainSeconds.toString().padStart(2, '0') });
};

const formattedElapsed = computed(() => formatSeconds(props.modelElapsedSeconds));
const formattedTotal = computed(() => formatSeconds(props.modelTotalSeconds));
</script>

<style scoped lang="scss">
.simulation-task-progress {
  width: 272px;
  margin-left: 12px;
  padding: 5px 10px 6px;
  border-left: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
  line-height: 1.25;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 3px;
  font-size: 12px;
}

.progress-stage {
  font-weight: 600;
}

.progress-value {
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}

.progress-message,
.model-time {
  margin-top: 3px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-time {
  color: var(--el-color-primary);
}

.status-failed .progress-value,
.status-failed .progress-stage {
  color: var(--el-color-danger);
}

.status-succeed .progress-value,
.status-succeed .progress-stage {
  color: var(--el-color-success);
}
</style>
