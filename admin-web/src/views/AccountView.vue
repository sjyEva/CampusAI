<script setup>
import { profile } from '../session.js'
</script>

<template>
  <header class="page-heading">
    <h1>{{ profile.role === 'admin' ? '管理账号' : '教学账号' }}</h1>
  </header>
  <section class="panel" aria-label="账号信息">
    <dl class="details">
      <div><dt>姓名</dt><dd>{{ profile.nickName }}</dd></div>
      <div><dt>登录账号</dt><dd>{{ profile.userName }}</dd></div>
      <div><dt>角色</dt><dd>{{ profile.role === 'admin' ? '管理员' : '教师' }}</dd></div>
      <div><dt>查看范围</dt><dd>{{ profile.role === 'admin' ? '全部课程' : '本人负责的课程' }}</dd></div>
    </dl>
  </section>
</template>
