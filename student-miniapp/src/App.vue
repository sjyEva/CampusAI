<script>
export default {}
</script>

<style>
@import './styles/study-layout.css';

page {
  /* The palette follows admin-web/src/style.css. */
  --campus-bg: #f7f4ed;
  --campus-ink: #24231f;
  --campus-muted: #6f6a62;
  --campus-line: rgba(22, 21, 19, 0.12);
  --campus-line-strong: rgba(22, 21, 19, 0.18);
  --campus-accent: #5c5140;
  --campus-gold: #a8925a;
  --campus-warm: #f8f4eb;
  --campus-danger: #8d3a32;
  --campus-surface: #fff;
  background-color: var(--campus-bg);
  color: var(--campus-ink);
  font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", system-ui, sans-serif;
  font-size: 14px;
  line-height: 1.45;
}

.study-page {
  box-sizing: border-box;
  max-width: 480px;
  min-height: 100vh;
  margin: 0 auto;
  padding: 8px 16px 20px;
  padding-bottom: calc(20px + env(safe-area-inset-bottom));
}

.study-page.with-assistant { padding-bottom: 76px; padding-bottom: calc(76px + env(safe-area-inset-bottom)); }
.study-section { padding: 9px 0 12px; border-bottom: 1px solid var(--campus-line); }
.study-section + .study-section { margin-top: 6px; }
.study-heading { font-size: 16px; font-weight: 700; line-height: 1.25; }
.study-subtitle { font-size: 15px; font-weight: 650; line-height: 1.35; }
.study-small { font-size: 12px; color: var(--campus-muted); line-height: 1.45; }
.study-caption { font-size: 11px; color: var(--campus-muted); line-height: 1.45; }
.study-stack { display: flex; flex-direction: column; gap: 8px; }
.study-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.study-field {
  box-sizing: border-box;
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid var(--campus-line);
  border-radius: 6px;
  background: #fffdf8;
  font-size: 14px;
  line-height: 1.45;
}

.study-btn {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
  min-height: 40px;
  margin: 0;
  padding: 7px 12px;
  border: 1px solid var(--campus-line);
  border-radius: 6px;
  background: transparent;
  color: var(--campus-ink);
  font-size: 13px;
  line-height: 1.35;
  white-space: nowrap;
}

.study-btn::after, .study-text-btn::after { border: 0; }
.study-btn.is-primary { background: var(--campus-ink); color: #fffaf0; border-color: var(--campus-ink); }
.study-btn[disabled], .study-text-btn[disabled] { opacity: .4; }
.study-text-btn {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
  min-height: 44px;
  margin: 0;
  padding: 2px 6px;
  border: 0;
  background: transparent;
  color: var(--campus-accent);
  font-size: 12px;
  line-height: 1.35;
  white-space: nowrap;
}
.study-note { padding: 8px 0; color: var(--campus-muted); font-size: 13px; line-height: 1.6; }
.study-error { padding: 7px 0; color: var(--campus-danger); font-size: 13px; line-height: 1.6; }
.study-mark { flex: none; width: 4px; height: 25px; background: var(--campus-accent); }
.study-arrow { flex: none; width: 6px; height: 6px; border-top: 1px solid currentColor; border-right: 1px solid currentColor; transform: rotate(45deg); color: var(--campus-muted); }
.study-text { white-space: pre-wrap; overflow-wrap: break-word; overflow-wrap: anywhere; line-height: 1.7; }
.study-picker { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.study-textarea { min-height: 96px; }
.study-tag { flex: none; padding: 3px 6px; border-radius: 4px; background: var(--campus-warm); color: var(--campus-accent); font-size: 11px; }

@media (max-width: 355px) {
  .study-page { padding-right: 14px; padding-left: 14px; }
}

.page {
  padding: 40rpx 32rpx;
}

.title {
  font-size: 20px;
  font-weight: 650;
}

.muted {
  color: var(--campus-muted);
}

.error {
  color: var(--campus-danger);
  margin: 24rpx 0;
}

.primary {
  margin-top: 32rpx;
  border-radius: 12rpx;
  background: var(--campus-ink);
  color: #f6f1e6;
}

.primary[disabled] {
  background: #eceae6;
  color: var(--campus-muted);
}
</style>
