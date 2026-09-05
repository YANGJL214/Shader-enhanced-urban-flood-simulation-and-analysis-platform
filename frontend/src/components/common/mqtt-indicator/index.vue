<template>
  <el-tooltip
    :content="tooltipContent"
    placement="top"
    raw-content
  >
  <span
    class="mqtt-status-dot"
    :class="statusClass"
    @click="handleClick"
  ></span>
  </el-tooltip>
</template>

<script setup lang="js">
/**
 * @author: zc
 * @date: 2025/12/19 9:48
 */
import { computed } from 'vue';
import { simulateMqttStore } from '@/store/MqttStore';
import { useI18n } from '@/i18n';

const mqttStore = simulateMqttStore();
const { t } = useI18n();
const status = computed(() => mqttStore.connectionStatus);
const statusClass = computed(() => ({
  'status-online': status.value === 'online',
  'status-reconnecting': status.value === 'reconnecting',
  'status-offline': status.value === 'offline',
}));
const tooltipContent = computed(() => `
${t('mqtt.tooltipIntro')}<br><br>
<span style="color:#67c23a;width:12px;height:12px;">●</span> ${t('mqtt.connected')}<br>
<span style="color:#e6a23c;width:12px;height:12px;">●</span> ${t('mqtt.reconnecting')}<br>
<span style="color:#f56c6c;width:12px;height:12px;">●</span> ${t('mqtt.disconnected')}
`);

function handleClick() {
  if (status.value === 'offline') {
    mqttStore.reconnect();
  }
}
</script>

<style scoped lang="scss">
.mqtt-status-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin: 0 5px;
  cursor: pointer;
  transition: transform 0.2s;
}

.mqtt-status-dot:hover {
  transform: scale(1.2);
}

.status-online {
  background-color: #67c23a;
}

.status-reconnecting {
  background-color: #e6a23c;
}

.status-offline {
  background-color: #f56c6c;
}
</style>
