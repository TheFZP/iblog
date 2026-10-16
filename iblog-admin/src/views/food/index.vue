<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜店名、特色菜、地址"
        clearable
        style="width: 260px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button type="primary" @click="load">查询</el-button>
      <div class="toolbar-right">
        <el-button type="primary" @click="openDialog()">新增美食</el-button>
      </div>
    </div>

    <el-table :data="foods" v-loading="loading" style="width: 100%">
      <el-table-column prop="name" label="店名" min-width="160">
        <template #default="{ row }">
          <div class="cell-name">
            <span>{{ row.name }}</span>
            <el-tag v-if="row.status === 0" size="small" type="info">已隐藏</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="cuisine" label="菜系" width="100" />
      <el-table-column prop="dishes" label="特色菜" min-width="200" show-overflow-tooltip />
      <el-table-column prop="address" label="位置" min-width="220" show-overflow-tooltip />
      <el-table-column label="坐标" width="90" align="center">
        <!-- 有坐标才能跳高德，这里给个直观的标记 -->
        <template #default="{ row }">
          <el-tag v-if="locatable(row)" size="small" type="success">可导航</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="avgPrice" label="人均" width="80">
        <template #default="{ row }">
          <span v-if="row.avgPrice != null">¥{{ row.avgPrice }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" top="6vh">
      <el-form :model="dialog.form" label-width="88px">
        <el-form-item label="店名" required>
          <el-input v-model="dialog.form.name" placeholder="如：巷子口老面馆" />
        </el-form-item>

        <el-form-item label="菜系">
          <el-input v-model="dialog.form.cuisine" placeholder="如：川菜 / 日料 / 火锅" style="width: 220px" />
          <span class="form-tip">会用于官网的筛选条</span>
        </el-form-item>

        <el-form-item label="特色菜">
          <el-input
            v-model="dialog.form.dishes"
            type="textarea"
            :rows="2"
            placeholder="多个用顿号分隔，如：红烧牛肉面、酱牛肉、凉拌黄瓜"
          />
        </el-form-item>

        <el-form-item label="位置">
          <el-input
            v-model="dialog.form.address"
            type="textarea"
            :rows="2"
            placeholder="如：杭州市西湖区文三路 128 号"
          />
        </el-form-item>

        <el-form-item label="经纬度">
          <div class="coord-row">
            <el-input v-model.number="dialog.form.longitude" placeholder="经度 如 120.123456" />
            <el-input v-model.number="dialog.form.latitude" placeholder="纬度 如 30.123456" />
          </div>
          <div class="form-tip">
            在高德
            <a href="https://lbs.amap.com/tools/picker" target="_blank" rel="noopener">坐标拾取器</a>
            上取点，<strong>用高德的坐标（gcj02）</strong>，别用 GPS 原始坐标 —— 会偏几百米。
            两个都不填则官网只显示搜索按钮，不显示「导航」。
          </div>
        </el-form-item>

        <el-form-item label="高德 POI">
          <el-input v-model="dialog.form.poiId" placeholder="可留空；有它就不用填经纬度" style="width: 260px" />
        </el-form-item>

        <el-form-item label="人均">
          <el-input-number v-model="dialog.form.avgPrice" :min="0" :max="9999" />
          <span class="form-tip">元，留空表示不限</span>
        </el-form-item>

        <el-form-item label="推荐度">
          <el-rate v-model="dialog.form.rating" clearable />
        </el-form-item>

        <el-form-item label="封面图">
          <el-input v-model="dialog.form.cover" placeholder="图片地址，可留空" />
        </el-form-item>

        <el-form-item label="最近去过">
          <el-date-picker
            v-model="dialog.form.visitedAt"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
          />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="dialog.form.remark"
            type="textarea"
            :rows="2"
            placeholder="招牌、排队情况、停车、是否要预约等"
          />
        </el-form-item>

        <el-form-item label="排序">
          <el-input-number v-model="dialog.form.sort" :min="0" />
          <span class="form-tip">越小越靠前</span>
        </el-form-item>

        <el-form-item label="是否展示">
          <el-switch v-model="dialog.form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createFood, deleteFood, listFoods, updateFood, type FoodItem } from '@/api/food'

const loading = ref(false)
const saving = ref(false)
const foods = ref<FoodItem[]>([])
const keyword = ref('')

function emptyForm(): Partial<FoodItem> {
  return {
    id: undefined,
    name: '',
    cuisine: '',
    dishes: '',
    address: '',
    longitude: null,
    latitude: null,
    poiId: '',
    avgPrice: null,
    rating: 0,
    cover: '',
    remark: '',
    visitedAt: null,
    sort: 0,
    status: 1
  }
}

const dialog = reactive({
  visible: false,
  title: '新增美食',
  form: emptyForm()
})

/** 与官网 canLocate 同义：有 POI 或经纬度齐全才能导航 */
function locatable(row: FoodItem): boolean {
  return !!row.poiId || (row.longitude != null && row.latitude != null)
}

async function load() {
  loading.value = true
  try {
    foods.value = await listFoods({ keyword: keyword.value.trim() || undefined })
  } finally {
    loading.value = false
  }
}

function openDialog(row?: FoodItem) {
  dialog.title = row ? '编辑美食' : '新增美食'
  dialog.form = row ? { ...row } : emptyForm()
  dialog.visible = true
}

async function submit() {
  const form = dialog.form
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写店名')
    return
  }
  // 后端也会拦，但在这里先提示能省一次往返
  const hasLng = form.longitude != null && form.longitude !== ('' as any)
  const hasLat = form.latitude != null && form.latitude !== ('' as any)
  if (hasLng !== hasLat) {
    ElMessage.warning('经纬度要一起填，只填一个无法定位')
    return
  }

  saving.value = true
  try {
    const payload = {
      ...form,
      // el-input-number 清空后是 null，但空字符串会被后端当成非法小数，统一成 null
      longitude: hasLng ? Number(form.longitude) : null,
      latitude: hasLat ? Number(form.latitude) : null,
      rating: form.rating || null
    }
    if (form.id) {
      await updateFood(payload)
    } else {
      await createFood(payload)
    }
    ElMessage.success('保存成功')
    dialog.visible = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row: FoodItem) {
  await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteFood(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.coord-row {
  display: flex;
  gap: 12px;
  width: 100%;
}

.form-tip {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.form-tip a {
  color: var(--el-color-primary);
}
</style>
