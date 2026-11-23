<script setup>
import CourseFilter from './CourseFilter.vue'

defineProps({
  title: { type: String, required: true },
  en: { type: String, required: true },
  description: { type: String, default: '' },
})
</script>

<template>
  <header class="desk-head">
    <div class="desk-label">
      <h2>{{ title }}</h2>
      <span class="desk-en">{{ en }}</span>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="desk-tools">
      <CourseFilter />
      <slot />
    </div>
    <div class="desk-rule" />
  </header>
</template>
