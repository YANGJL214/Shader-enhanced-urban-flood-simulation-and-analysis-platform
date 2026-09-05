<template>
  <el-tooltip :content="tooltipText" placement="bottom">
    <button
      class="language-switch"
      type="button"
      :aria-label="t('language.aria')"
      @click="toggleLocale"
    >
      <el-icon class="language-switch__icon"><Switch /></el-icon>
      <span :class="['language-switch__option', { active: locale === 'zh-CN' }]">中</span>
      <span class="language-switch__divider">/</span>
      <span :class="['language-switch__option', { active: locale === 'en-US' }]">EN</span>
    </button>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from '@/i18n'

const { locale, t, toggleLocale } = useI18n()
const tooltipText = computed(() => locale.value === 'zh-CN' ? t('language.switchToEnglish') : t('language.switchToChinese'))
</script>

<style scoped>
.language-switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 92px;
  height: 34px;
  padding: 3px 7px;
  color: #606266;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-radius: 18px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.language-switch:hover,
.language-switch:focus-visible {
  background: #ecf5ff;
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
  outline: none;
}

.language-switch__icon {
  margin-right: 1px;
  color: #409eff;
  font-size: 15px;
}

.language-switch__option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 23px;
  height: 24px;
  padding: 0 4px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.language-switch__option.active {
  color: #fff;
  background: #409eff;
}

.language-switch__divider {
  color: #a8abb2;
  font-size: 11px;
}
</style>

