<script setup lang="ts">
/**
 * 次日看涨独立页。
 *
 * <p>榜单由后端每个交易日 15:30 全市场扫描生成，页面只读；主指标是可交易口径
 * （T+1 开盘买 → 收盘卖）。从模拟盘页面抽出来做成独立菜单，避免与账户/规则操作混在一起。</p>
 */
import NextDayPredictPanel from '@/components/paper/NextDayPredictPanel.vue'
</script>

<template>
  <div class="predict-page">
    <div class="layout">
      <header class="page-head">
        <div class="page-head__main">
          <h1 class="page-title">次日看涨</h1>
          <p class="page-desc">
            交易日 15:30 全市场扫描的次日预测榜单，按「T+1 开盘买入 → 收盘卖出」的可交易口径统计准确率。
            榜单每晚更新，供次日开盘前参考。
          </p>
        </div>
      </header>

      <NextDayPredictPanel />
    </div>
  </div>
</template>

<style scoped>
.predict-page {
  padding-bottom: var(--s-8);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-6);
  margin-bottom: var(--s-8);
}

.page-head__main {
  min-width: 0;
}

.page-title {
  font-size: var(--t-3xl);
  letter-spacing: -0.03em;
}

.page-desc {
  margin-top: 8px;
  max-width: 64ch;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  line-height: 1.8;
}
</style>
