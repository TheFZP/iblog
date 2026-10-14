<template>
  <div class="page-card" v-loading="loading">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" placeholder="请输入文章标题" maxlength="200" show-word-limit />
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="分类">
            <el-select v-model="form.categoryId" placeholder="请选择分类" clearable style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="标签">
            <el-select
              v-model="form.tagIds"
              multiple
              clearable
              placeholder="请选择标签"
              style="width: 100%"
            >
              <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="作者">
            <el-input v-model="form.author" placeholder="作者名" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="别名 slug">
            <el-input v-model="form.slug" placeholder="留空自动生成，如 spring-boot-start" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="封面图">
        <el-input v-model="form.cover" placeholder="图片 URL，可选" />
      </el-form-item>

      <el-form-item label="摘要">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="列表页展示的摘要，可选"
        />
      </el-form-item>

      <el-form-item label="正文" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="18"
          placeholder="支持 Markdown 语法"
        />
      </el-form-item>

      <el-form-item label="发布状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">立即发布</el-radio>
          <el-radio :value="0">存为草稿</el-radio>
        </el-radio-group>
        <el-checkbox v-model="topChecked" style="margin-left: 24px">置顶</el-checkbox>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
        <el-button @click="$router.push('/articles')">返回</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createArticle, getArticle, updateArticle } from '@/api/article'
import { listCategories, listTags, type CategoryItem, type TagItem } from '@/api/taxonomy'

const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
const categories = ref<CategoryItem[]>([])
const tags = ref<TagItem[]>([])

const articleId = computed(() => {
  const id = route.params.id
  return id ? Number(id) : null
})

const form = reactive({
  title: '',
  slug: '',
  summary: '',
  content: '',
  cover: '',
  categoryId: null as number | null,
  author: 'admin',
  status: 1,
  isTop: 0,
  tagIds: [] as number[]
})

const topChecked = computed({
  get: () => form.isTop === 1,
  set: (value: boolean) => {
    form.isTop = value ? 1 : 0
  }
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入正文', trigger: 'blur' }]
}

async function loadDetail() {
  if (!articleId.value) {
    return
  }
  loading.value = true
  try {
    const data = await getArticle(articleId.value)
    form.title = data.title || ''
    form.slug = data.slug || ''
    form.summary = data.summary || ''
    form.content = data.content || ''
    form.cover = data.cover || ''
    form.categoryId = data.categoryId ?? null
    form.author = data.author || 'admin'
    form.status = data.status ?? 0
    form.isTop = data.isTop ?? 0
    form.tagIds = data.tagIds || []
  } finally {
    loading.value = false
  }
}

async function onSubmit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    const payload = { ...form }
    if (articleId.value) {
      await updateArticle({ ...payload, id: articleId.value })
    } else {
      await createArticle(payload)
    }
    ElMessage.success('保存成功')
    router.push('/articles')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  categories.value = await listCategories()
  tags.value = await listTags()
  await loadDetail()
})
</script>
