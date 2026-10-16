<template>
  <div class="layout">
    <!-- 页头：沿用首页报头的手法，一条 2px 实线收口 -->
    <header class="page-head">
      <p class="page-head__eyebrow">探店记录</p>
      <h1 class="page-head__title">美食</h1>
      <p class="page-head__desc">
        路过就记一笔：店名、招牌菜、位置。手机上点「导航」会直接唤起高德地图。
      </p>
    </header>

    <!-- 筛选条：菜系 chips + 关键词搜索。
         只有一项菜系且没搜索时才显得多余，此时也照常显示，保持布局稳定。 -->
    <div v-if="foods.length || keyword || cuisine" class="filters">
      <div class="filters__cuisines" role="group" aria-label="按菜系筛选">
        <button
          type="button"
          class="chip"
          :class="{ 'chip--brand': cuisine === '' }"
          :aria-pressed="cuisine === ''"
          @click="selectCuisine('')"
        >
          全部
        </button>
        <button
          v-for="item in cuisines"
          :key="item"
          type="button"
          class="chip"
          :class="{ 'chip--brand': cuisine === item }"
          :aria-pressed="cuisine === item"
          @click="selectCuisine(item)"
        >
          {{ item }}
        </button>
      </div>

      <form class="filters__search" role="search" @submit.prevent="load">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.6-3.6" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜店名、菜名、地址" aria-label="搜索美食" />
      </form>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading && !foods.length" class="skeleton-list" aria-hidden="true">
      <div v-for="n in 4" :key="n" class="skeleton skeleton--food"></div>
    </div>

    <!-- 列表 -->
    <div v-else-if="foods.length" class="food-list">
      <article v-for="item in foods" :key="item.id" class="food">
        <div v-if="item.cover" class="food__cover">
          <img :src="item.cover" :alt="item.name" loading="lazy" />
        </div>

        <div class="food__body">
          <div class="food__head">
            <h2 class="food__name">{{ item.name }}</h2>
            <span v-if="item.cuisine" class="chip chip--brand">{{ item.cuisine }}</span>
          </div>

          <p v-if="item.dishes" class="food__dishes">
            <span class="food__label">特色菜</span>
            <span>{{ displayDishes(item.dishes) }}</span>
          </p>

          <p v-if="item.address" class="food__address">
            <span class="food__label">位置</span>
            <span>{{ item.address }}</span>
          </p>

          <p v-if="item.remark" class="food__remark">{{ item.remark }}</p>

          <div class="food__meta">
            <span v-if="item.avgPrice != null" class="food__price">人均 ¥{{ item.avgPrice }}</span>
            <span v-if="item.rating" class="food__rating" :aria-label="`推荐度 ${item.rating} 星`">
              <span aria-hidden="true">{{ '★'.repeat(item.rating) }}</span>
            </span>
            <span v-if="item.visitedAt" class="food__date">{{ item.visitedAt }}</span>
          </div>
        </div>

        <div class="food__actions">
          <button
            v-if="canLocate(item)"
            type="button"
            class="btn btn--locate"
            @click="navigate(item)"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11Z" />
              <circle cx="12" cy="10" r="2.6" />
            </svg>
            {{ mobile ? '导航' : '在地图中查看' }}
          </button>
          <button v-else-if="item.address" type="button" class="btn btn--ghost" @click="navigate(item)">
            搜索地图
          </button>
        </div>
      </article>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 3v8a3 3 0 0 0 6 0V3M9 11v10" />
        <path d="M17 3c-1.4 1.7-2 3.6-2 5.6 0 1.5.6 2.6 1.6 3.1V21" />
      </svg>
      <p v-if="keyword || cuisine">没有匹配的店，换个关键词或菜系试试。</p>
      <p v-else>还没有记录，去后台「美食管理」添加第一家吧。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listFoodCuisines, listFoods, type FoodItem } from '@/api/portal'
import { canLocate, isMobile, openAmap } from '@/utils/amap'

const foods = ref<FoodItem[]>([])
const cuisines = ref<string[]>([])
const cuisine = ref('')
const keyword = ref('')
const loading = ref(false)

/** 移动端文案与行为都不同，只算一次 */
const mobile = isMobile()

/** 特色菜用顿号或逗号分隔，统一渲染成「、」连接 */
function displayDishes(dishes: string): string {
  return dishes
    .split(/[、,，;；]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .join('、')
}

async function load() {
  loading.value = true
  try {
    foods.value = await listFoods({
      cuisine: cuisine.value || undefined,
      keyword: keyword.value.trim() || undefined
    })
  } finally {
    loading.value = false
  }
}

function selectCuisine(value: string) {
  cuisine.value = value
  load()
}

function navigate(item: FoodItem) {
  openAmap({
    name: item.name,
    address: item.address,
    longitude: item.longitude,
    latitude: item.latitude,
    poiId: item.poiId
  })
}

onMounted(async () => {
  // 菜系只依赖已有数据，失败也不该挡住列表
  listFoodCuisines()
    .then((list) => {
      cuisines.value = list
    })
    .catch(() => {
      cuisines.value = []
    })
  await load()
})
</script>

<style scoped>
.page-head {
  padding-bottom: var(--s-6);
  margin-bottom: var(--s-8);
  border-bottom: 2px solid var(--c-text);
}

.page-head__eyebrow {
  margin: 0 0 var(--s-2);
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-faint);
}

.page-head__title {
  margin: 0;
  font-size: var(--t-3xl);
  letter-spacing: -0.03em;
  line-height: 1.1;
}

.page-head__desc {
  margin: var(--s-3) 0 0;
  color: var(--c-text-muted);
  font-size: var(--t-md);
  max-width: 56ch;
}

/* ---------- 筛选条 ---------- */
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  margin-bottom: var(--s-8);
}

.filters__cuisines {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

/* 这些是 <button> 不是 <a>，得清掉浏览器默认样式 */
.filters__cuisines .chip {
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  cursor: pointer;
  min-height: 30px;
  font-family: inherit;
  transition: background 0.18s var(--ease), border-color 0.18s var(--ease);
}

.filters__cuisines .chip:hover {
  border-color: var(--c-border-strong);
  color: var(--c-text);
}

.filters__cuisines .chip--brand {
  border-color: transparent;
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.filters__search {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: 0 var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  transition: border-color 0.18s var(--ease), box-shadow 0.18s var(--ease);
}

.filters__search:focus-within {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.filters__search svg {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: var(--c-text-faint);
  stroke-width: 1.8;
  stroke-linecap: round;
}

.filters__search input {
  width: 190px;
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--c-text);
  font-family: inherit;
  font-size: var(--t-sm);
  outline: none;
}

/* ---------- 列表 ---------- */
.food-list {
  display: flex;
  flex-direction: column;
}

.food {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) auto;
  gap: var(--s-6);
  align-items: start;
  padding: var(--s-6) 0;
  border-bottom: 1px solid var(--c-border);
}

/* 没封面的条目省掉首列，文字自然左移 */
.food:not(:has(.food__cover)) {
  grid-template-columns: minmax(0, 1fr) auto;
}

.food__cover {
  width: 168px;
  aspect-ratio: 4 / 3;
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--c-surface-sunken);
}

.food__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.food__body {
  min-width: 0;
}

.food__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.food__name {
  margin: 0;
  font-size: var(--t-xl);
  letter-spacing: -0.015em;
  line-height: 1.3;
}

.food__dishes,
.food__address {
  margin: 0 0 var(--s-2);
  font-size: var(--t-base);
  line-height: 1.6;
  color: var(--c-text-secondary);
}

/* 标签列固定宽，多条信息左边的「特色菜 / 位置」才能对齐 */
.food__label {
  display: inline-block;
  width: 3.6em;
  color: var(--c-text-faint);
  font-size: var(--t-sm);
}

.food__remark {
  margin: var(--s-3) 0 0;
  padding-left: var(--s-4);
  border-left: 2px solid var(--c-brand-soft);
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  line-height: 1.7;
}

.food__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-4);
  margin-top: var(--s-3);
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.food__price {
  font-variant-numeric: tabular-nums;
}

.food__rating {
  color: var(--c-accent);
  letter-spacing: 0.08em;
}

.food__date {
  font-variant-numeric: tabular-nums;
}

.food__actions {
  display: flex;
  align-items: center;
  padding-top: 2px;
}

.btn--locate {
  gap: 6px;
  min-height: 36px;
  padding: 0 var(--s-4);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text);
  font-family: inherit;
  font-size: var(--t-sm);
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.18s var(--ease), color 0.18s var(--ease);
}

.btn--locate:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.btn--locate svg {
  width: 14px;
  height: 14px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linejoin: round;
}

.skeleton--food {
  height: 132px;
  margin-bottom: var(--s-4);
  border-radius: var(--r-sm);
}

/* ---------- 窄屏：封面挪到标题右边，操作按钮独占一行 ---------- */
@media (max-width: 720px) {
  .food,
  .food:not(:has(.food__cover)) {
    grid-template-columns: 104px minmax(0, 1fr);
    gap: var(--s-4);
    padding: var(--s-5) 0;
  }

  .food:not(:has(.food__cover)) .food__body {
    grid-column: 1 / -1;
  }

  .food__cover {
    width: 104px;
  }

  .food__name {
    font-size: var(--t-lg);
  }

  .food__actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  /* 触摸目标补到 44px，符合移动端规范 */
  .btn--locate,
  .filters__cuisines .chip {
    min-height: 44px;
  }

  .filters__cuisines .chip {
    padding-inline: var(--s-4);
  }

  .filters__search {
    width: 100%;
  }

  .filters__search input {
    width: 100%;
  }

  .page-head__title {
    font-size: var(--t-2xl);
  }
}
</style>
