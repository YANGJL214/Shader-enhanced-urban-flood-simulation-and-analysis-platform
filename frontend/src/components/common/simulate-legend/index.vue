<template>
  <div class="vg-legend">
    <div class="label-unit">{{ displayUnit }}</div>

    <div class="legend-body">
      <div class="gradient" :style="{ backgroundImage: gradientCSS }" role="img"
           :aria-label="t('map.colorLegendAria', { min: range[0], max: range[1] })"></div>
      <div class="labels right">
        <div v-for="(lab, i) in renderLabels" :key="i" class="label-item">
          {{ lab }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()

const props = defineProps({
  range: {
    type: Array,
    default: () => [0, 1]
  },
  colors: {
    type: Array,
    default: () => ['#d7191c', '#ffffbf', '#2b83ba']
  },
  minHeight: {
    type: Number,
    default: 250
  },
  minWidth: {
    type: Number,
    default: 80
  },
  tickCount: {
    type: Number,
    default: 5
  },
  reversed: {
    type: Boolean,
    default: true
  },
  valueFormatter: {
    type: Function,
    default: null
  },
  labels: {
    type: Array,
    default: null
  },
  unit: {
    type: String,
    default: ''
  },
})

const displayUnit = computed(() => props.unit || t('map.meter'))

const renderColors = ref([...props.colors])

watch(() => props.colors, (newV) => {
  renderColors.value = [...newV]
}, { deep: true, immediate: true })

const tickCount = Math.max(2, props.tickCount)

const gradientCSS = computed(() => {
  const colors = props.reversed ? [...renderColors.value].reverse() : renderColors.value
  const n = colors.length
  if (n === 1) return `linear-gradient(to top, ${colors[0]}, ${colors[0]})`
  const stops = colors.map((c, i) => {
    const pct = Math.round((i / (n - 1)) * 100)
    return `${c} ${pct}%`
  })
  return `linear-gradient(to top, ${stops.join(', ')})`
})

const tickValues = computed(() => {
  const [min, max] = props.range
  const values = []
  for (let i = 0; i < tickCount; i++) {
    const t = i / (tickCount - 1)
    values.push(min + (max - min) * t)
  }
  return props.reversed ? values.slice().reverse() : values
})

const renderLabels = computed(() => {
  if (Array.isArray(props.labels) && props.labels.length === tickCount) {
    return props.labels
  }
  return tickValues.value.map(v => formatValue(v))
})

function formatValue(v) {
  if (props.valueFormatter) return props.valueFormatter(v)
  if (Math.abs(v - Math.round(v)) < 1e-8) return String(Math.round(v))
  return Number(v).toFixed(2)
}
</script>

<style scoped>
.vg-legend {
  z-index: 20;
  display: inline-flex;
  flex-direction: column; 
  align-items: flex-start; 
  gap: 6px; 
  padding: 8px;
  background: rgba(255, 255, 255, 1);
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial;
  box-sizing: border-box;
  min-width: v-bind('props.minWidth + "px"');
}

.label-unit {
  font-size: 12px;
  font-weight: bold;
  color: rgba(0, 0, 0, 0.85);
  text-align: left;
}

.legend-body {
  display: flex;
  align-items: stretch;
  gap: 8px;
  min-height: v-bind('props.minHeight + "px"');
}

.gradient {
  width: 28px;
  border-radius: 6px;
  background-clip: padding-box;
  border: 1px solid rgba(0, 0, 0, 0.08);
  flex-shrink: 0;
}

.labels.right {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 30px;
  padding: 2px 0;
}

.label-item {
  white-space: nowrap;
  text-align: right;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.85);
  user-select: none;
}

@media (max-width: 420px) {
  .vg-legend {
    gap: 6px;
    padding: 6px;
  }

  .gradient {
    width: 20px;
  }

  .label-item {
    font-size: 11px;
  }
}
</style>
