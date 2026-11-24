<script setup>
import { useCourseScope } from '../composables/courseScope.js'
import { toast } from '../utils/toast.js'

const { selectedId, options } = useCourseScope()

function onChange(value) {
  const label = options.value.find((option) => option.value === value)?.label || value
  toast(`已筛选：${label}`)
}
</script>

<template>
  <el-select v-model="selectedId" class="scope-select" aria-label="课程范围" @change="onChange">
    <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value" />
  </el-select>
</template>
