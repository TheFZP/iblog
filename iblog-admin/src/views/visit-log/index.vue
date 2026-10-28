<template>
  <div class="page-card">
    <!-- 今日统计 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-card__num">{{ summary?.todayCount ?? '—' }}</div>
        <div class="stat-card__label">今日访问（PV）</div>
      </div>
      <div class="stat-card">
        <div class="stat-card__num">{{ summary?.todayIpCount ?? '—' }}</div>
        <div class="stat-card__label">今日独立 IP（UV）</div>
      </div>
      <div class="stat-card stat-card--wide">
        <div class="stat-card__label">今日热门路径</div>
        <div class="top-paths">
          <el-tag
            v-for="item in summary?.topPaths ?? []"
            :key="item.path"
            size="small"
            class="top-path-tag"
            @click="applyPath(item.path)"
          >
            {{ item.path }}（{{ item.count }}）
          </el-tag>
          <span v-if="!summary?.topPaths?.length" class="top-paths__empty">暂无数据</span>
        </div>
      </div>
    </div>

    <!-- 过滤 -->
    <div class="toolbar">
      <el-input
        v-model="query.path"
        placeholder="按路径前缀过滤，如 /api/portal/articles"
        clearable
        style="width: 320px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </div>

    <el-table :data="list" v-loading="loading" style="width: 100%">
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column prop="path" label="路径" min-width="240" show-overflow-tooltip />
      <el-table-column prop="method" label="方法" width="80" />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 200 ? 'success' : 'danger'" size="small">
            {{ row.status === 200 ? '成功' : row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">
          {{ row.costMs }} ms
        </template>
      </el-table-column>
      <el-table-column prop="userAgent" label="User-Agent" min-width="200" show-overflow-tooltip />
      <el-table-column prop="referer" label="来源" min-width="160" show-overflow-tooltip />
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="onSearch"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getVisitSummary, pageVisitLogs, type VisitLogItem, type VisitSummary } from '@/api/visitLog'

const loading = ref(false)
const list = ref<VisitLogItem[]>([])
const total = ref(0)
const summary = ref<VisitSummary | null>(null)

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  path: ''
})

async function load() {
  loading.value = true
  try {
    const data = await pageVisitLogs({ ...query })
    list.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  summary.value = await getVisitSummary()
}

function onSearch() {
  query.pageNum = 1
  load()
}

function onReset() {
  query.path = ''
  onSearch()
}

/** 点热门路径标签直接带进过滤框 */
function applyPath(path: string) {
  query.path = path
  onSearch()
}

onMounted(() => {
  load()
  loadSummary()
})
</script>

<style scoped>
.stat-row {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.stat-card {
  flex: 0 0 180px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.stat-card--wide {
  flex: 1 1 320px;
  min-width: 280px;
}

.stat-card__num {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-card__label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.top-paths {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.top-path-tag {
  cursor: pointer;
}

.top-paths__empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
