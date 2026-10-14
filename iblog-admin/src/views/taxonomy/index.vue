<template>
  <div class="page-card">
    <el-tabs v-model="activeTab">
      <!-- 分类 -->
      <el-tab-pane label="分类管理" name="category">
        <div class="toolbar">
          <div class="toolbar-right">
            <el-button type="primary" @click="openCategoryDialog()">新增分类</el-button>
          </div>
        </div>
        <el-table :data="categories" v-loading="loading" style="width: 100%">
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column prop="slug" label="别名" width="180" />
          <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
          <el-table-column prop="articleCount" label="文章数" width="90" />
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openCategoryDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="onDeleteCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 标签 -->
      <el-tab-pane label="标签管理" name="tag">
        <div class="toolbar">
          <div class="toolbar-right">
            <el-button type="primary" @click="openTagDialog()">新增标签</el-button>
          </div>
        </div>
        <el-table :data="tags" v-loading="loading" style="width: 100%">
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column prop="slug" label="别名" width="180" />
          <el-table-column prop="articleCount" label="文章数" width="90" />
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTagDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="onDeleteTag(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 分类弹窗 -->
    <el-dialog v-model="categoryDialog.visible" :title="categoryDialog.title" width="460px">
      <el-form :model="categoryDialog.form" label-width="70px">
        <el-form-item label="名称">
          <el-input v-model="categoryDialog.form.name" placeholder="如：技术笔记" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="categoryDialog.form.slug" placeholder="如：tech，留空自动生成" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="categoryDialog.form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="categoryDialog.form.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitCategory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 标签弹窗 -->
    <el-dialog v-model="tagDialog.visible" :title="tagDialog.title" width="460px">
      <el-form :model="tagDialog.form" label-width="70px">
        <el-form-item label="名称">
          <el-input v-model="tagDialog.form.name" placeholder="如：Java" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="tagDialog.form.slug" placeholder="如：java，留空自动生成" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="tagDialog.form.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitTag">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createCategory,
  createTag,
  deleteCategory,
  deleteTag,
  listCategories,
  listTags,
  updateCategory,
  updateTag,
  type CategoryItem,
  type TagItem
} from '@/api/taxonomy'

const activeTab = ref('category')
const loading = ref(false)
const categories = ref<CategoryItem[]>([])
const tags = ref<TagItem[]>([])

const categoryDialog = reactive({
  visible: false,
  title: '新增分类',
  form: { id: undefined as number | undefined, name: '', slug: '', description: '', sort: 0 }
})

const tagDialog = reactive({
  visible: false,
  title: '新增标签',
  form: { id: undefined as number | undefined, name: '', slug: '', sort: 0 }
})

async function load() {
  loading.value = true
  try {
    const [c, t] = await Promise.all([listCategories(), listTags()])
    categories.value = c
    tags.value = t
  } finally {
    loading.value = false
  }
}

function openCategoryDialog(row?: CategoryItem) {
  categoryDialog.title = row ? '编辑分类' : '新增分类'
  categoryDialog.form = row
    ? { id: row.id, name: row.name, slug: row.slug, description: row.description || '', sort: row.sort || 0 }
    : { id: undefined, name: '', slug: '', description: '', sort: 0 }
  categoryDialog.visible = true
}

async function submitCategory() {
  const { id, ...rest } = categoryDialog.form
  if (!rest.name) {
    ElMessage.warning('请填写分类名称')
    return
  }
  if (id) {
    await updateCategory({ id, ...rest })
  } else {
    await createCategory(rest)
  }
  ElMessage.success('保存成功')
  categoryDialog.visible = false
  load()
}

async function onDeleteCategory(row: CategoryItem) {
  await ElMessageBox.confirm(`确定删除分类「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteCategory(row.id)
  ElMessage.success('删除成功')
  load()
}

function openTagDialog(row?: TagItem) {
  tagDialog.title = row ? '编辑标签' : '新增标签'
  tagDialog.form = row
    ? { id: row.id, name: row.name, slug: row.slug, sort: row.sort || 0 }
    : { id: undefined, name: '', slug: '', sort: 0 }
  tagDialog.visible = true
}

async function submitTag() {
  const { id, ...rest } = tagDialog.form
  if (!rest.name) {
    ElMessage.warning('请填写标签名称')
    return
  }
  if (id) {
    await updateTag({ id, ...rest })
  } else {
    await createTag(rest)
  }
  ElMessage.success('保存成功')
  tagDialog.visible = false
  load()
}

async function onDeleteTag(row: TagItem) {
  await ElMessageBox.confirm(`确定删除标签「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteTag(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
