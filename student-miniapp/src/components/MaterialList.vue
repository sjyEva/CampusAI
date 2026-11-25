<template>
  <view v-for="entry in items" :key="entryKey(entry)" class="material-row">
    <button class="material-main" :aria-label="'预览 ' + entry.name" @tap="readEntry(entry)">
      <text v-if="!compact" class="material-type">{{ entry.kind === 'note' ? '笔记' : extensionOf(entry.filename).toUpperCase() }}</text>
      <view class="material-meta">
        <view class="material-title">{{ entry.name }}</view>
        <view class="study-small material-caption">{{ itemMeta(entry) }}</view>
      </view>
      <view class="study-arrow" />
    </button>
    <button v-if="entry.origin === 'personal'" class="study-text-btn material-edit" :aria-label="'编辑 ' + entry.name" @tap="editEntry(entry)">编辑</button>
    <button class="study-text-btn material-star" :aria-label="(isFavorite(entry) ? '取消收藏 ' : '收藏 ') + entry.name"
      @tap="$emit('favorite', entry, !isFavorite(entry))">{{ isFavorite(entry) ? '★' : '☆' }}</button>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { entryKey, localDate } from '../api/workspace.js'
import { extensionOf } from '../utils/files.js'
import { editEntry, readEntry } from '../utils/study-ui.js'

const props = defineProps({
  items: { type: Array, required: true },
  favorites: { type: Array, default: () => [] },
  compact: Boolean
})
defineEmits(['favorite'])
const saved = computed(() => new Set(props.favorites.map(entryKey)))
const isFavorite = entry => saved.value.has(entryKey(entry))

function itemMeta(entry) {
  if (props.compact) return [entry.courseName || '未归入课程', entry.category].filter(Boolean).join(' · ')
  return entry.location || [entry.category, localDate(entry.updatedAt)].filter(Boolean).join(' · ')
}
</script>
