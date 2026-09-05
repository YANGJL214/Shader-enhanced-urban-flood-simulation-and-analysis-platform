<!-- components/BottomRightContainer.vue -->
<template>
  <div class="bottom-right-container">
    <slot></slot>
  </div>
</template>

<script setup>
</script>

<style scoped>
.bottom-right-container {
  position: absolute;
  right: 10px;
  bottom: 90px;
  display: flex;
  flex-direction: row-reverse; 
  align-items: flex-end; 
  gap: 12px; 
  pointer-events: none; 
}

.bottom-right-container ::v-slotted(*) {
  pointer-events: auto;
}
</style>
