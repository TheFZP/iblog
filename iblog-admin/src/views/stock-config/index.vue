<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>AI 分析配置</span>
          <el-tag v-if="configured" type="success" size="small">已配置</el-tag>
          <el-tag v-else type="warning" size="small">未配置（官网 AI 面板会提示去配置）</el-tag>
        </div>
      </template>

      <el-form label-width="140px" :disabled="saving">
        <el-form-item label="DeepSeek API Key">
          <el-input
            v-model="form.ai_api_key"
            type="password"
            show-password
            placeholder="sk-..."
            autocomplete="off"
          />
          <div class="hint">
            只存在服务端数据库里，不会下发给浏览器。留空并保存表示清除；显示为打码值时表示「不修改」。
          </div>
        </el-form-item>

        <el-form-item label="模型">
          <el-input v-model="form.ai_model" placeholder="deepseek-v4-flash" style="max-width: 280px" />
          <div class="hint">留空使用默认 deepseek-v4-flash。改错模型名会让对话直接报错。</div>
        </el-form-item>

        <el-form-item label="深度思考">
          <el-switch v-model="thinking" />
          <span class="inline-hint">关闭后不返回思考过程，速度更快、更省 token。</span>
        </el-form-item>

        <el-form-item label="推理强度">
          <el-select v-model="form.ai_reasoning_effort" :disabled="!thinking" style="max-width: 180px">
            <el-option label="低 low" value="low" />
            <el-option label="中 medium" value="medium" />
            <el-option label="高 high" value="high" />
          </el-select>
          <div class="hint">只在开启深度思考时生效，与桌面对齐默认 high。</div>
        </el-form-item>

        <el-form-item label="联网搜索">
          <el-switch v-model="webSearch" />
          <span class="inline-hint">
            开启后模型可以搜索财经资讯（东方财富搜索接口），响应会慢一些。
          </span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button :disabled="saving" @click="load">重置</el-button>
          <el-button link type="primary" @click="openAiPanel">去官网试一下</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card">
      <template #header>刷新间隔</template>
      <el-form label-width="140px" :disabled="saving">
        <el-form-item label="行情刷新 (ms)">
          <el-input v-model="form.quotes_refresh_ms" placeholder="例如 15000" style="max-width: 200px" />
        </el-form-item>
        <el-form-item label="K 线刷新 (ms)">
          <el-input v-model="form.kline_refresh_ms" placeholder="例如 60000" style="max-width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">这两项是给官网行情页读的，留空沿用前端默认值。</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchStockConfig, saveStockConfig, type StockConfig } from '@/api/stockConfig'

/**
 * AI 配置页。
 *
 * <p>为什么单独一页而不是塞进「站点设置」：AI Key 是凭证，写进站点设置会让任何
 * 读站点配置的路径都有机会把它带出去（后端也是据此把它们分成两张表存的）。</p>
 */

const saving = ref(false)
const form = reactive<StockConfig>({
  ai_api_key: '',
  ai_model: '',
  ai_thinking_enabled: '',
  ai_reasoning_effort: '',
  ai_web_search_enabled: '',
  quotes_refresh_ms: '',
  kline_refresh_ms: ''
})

/** 开关类在库里存的是字符串（'true'/'false'），空值按后端默认走。 */
const thinking = computed({
  get: () => form.ai_thinking_enabled !== 'false',
  set: (value: boolean) => {
    form.ai_thinking_enabled = value ? 'true' : 'false'
  }
})

const webSearch = computed({
  get: () => form.ai_web_search_enabled === 'true',
  set: (value: boolean) => {
    form.ai_web_search_enabled = value ? 'true' : 'false'
  }
})

const configured = computed(() => Boolean(form.ai_api_key && form.ai_api_key.trim()))

async function load() {
  try {
    const data = await fetchStockConfig()
    Object.assign(form, data)
    if (!form.ai_model) {
      form.ai_model = 'deepseek-v4-flash'
    }
    if (!form.ai_reasoning_effort) {
      form.ai_reasoning_effort = 'high'
    }
  } catch {
    // 拦截器已经弹过错误提示，这里不重复打扰
  }
}

async function handleSave() {
  saving.value = true
  try {
    await saveStockConfig({ ...form })
    ElMessage.success('已保存')
    await load()
  } catch {
    // 同上：错误提示由拦截器统一处理
  } finally {
    saving.value = false
  }
}

function openAiPanel() {
  // 走后台的一次性票据跳官网，和「行情」「模拟盘」按钮同一套机制
  const base = (import.meta.env.VITE_WEB_URL || '').replace(/\/+$/, '')
  if (!base) {
    ElMessage.warning('未配置官网地址（VITE_WEB_URL）')
    return
  }
  window.open(`${base}/stock`, '_blank', 'noopener')
}

onMounted(load)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.inline-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card {
  margin-bottom: 8px;
}
</style>
