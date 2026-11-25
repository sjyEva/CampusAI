<script>
export default {}
</script>

<style>
page {
  /* The palette follows admin-web/src/style.css. */
  --campus-bg: #f5f6f8;
  --campus-ink: #161513;
  --campus-muted: #6f6a62;
  --campus-line: rgba(22, 21, 19, 0.12);
  --campus-line-strong: rgba(22, 21, 19, 0.18);
  --campus-accent: #5c5140;
  --campus-gold: #a8925a;
  --campus-warm: #f8f4eb;
  --campus-danger: #8d3a32;
  --campus-surface: #fff;
  background: var(--campus-bg);
  color: var(--campus-ink);
  font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", system-ui, sans-serif;
  font-size: 30rpx;
}

.page {
  padding: 40rpx 32rpx;
}

.title {
  font-size: 48rpx;
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
