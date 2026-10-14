<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索标题"
        clearable
        style="width: 200px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 160px">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px">
        <el-option label="已发布" :value="1" />
        <el-option label="草稿" :value="0" />
      </el-select>
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button @click="onReset">重置</el-button>
      <div class="toolbar-right">
        <el-button type="primary" @click="$router.push('/articles/create')">写文章</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" style="width: 100%">
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.title }}</span>
          <el-tag v-if="row.isTop === 1" type="danger" size="small" style="margin-left: 6px">置顶</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="分类" width="110" />
      <el-table-column label="标签" width="200">
        <template #default="{ row }">
          <el-tag
            v-for="name in row.tagNames || []"
            :key="name"
            size="small"
            effect="plain"
            style="margin-right: 4px"
          >
            {{ name }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '已发布' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="viewCount" label="浏览" width="80" />
      <el-table-column prop="commentCount" label="评论" width="80" />
      <el-table-column prop="publishTime" label="发布时间" width="170" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/articles/edit/${row.id}`)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 1 ? '下架' : '发布' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="onSearch"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeArticleStatus,
  deleteArticle,
  pageArticles,
  type ArticleItem,
  type ArticleQuery
} from '@/api/article'
import { listCategories, type CategoryItem } from '@/api/taxonomy'

const loading = ref(false)
const list = ref<ArticleItem[]>([])
const total = ref(0)
const categories = ref<CategoryItem[]>([])

const query = reactive<ArticleQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  categoryId: null,
  status: null
})

async function load() {
  loading.value = true
  try {
    const data = await pageArticles({ ...query })
    list.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.pageNum = 1
  load()
}

function onReset() {
  query.keyword = ''
  query.categoryId = null
  query.status = null
  onSearch()
}

async function toggleStatus(row: ArticleItem) {
  const next = row.status === 1 ? 0 : 1
  await changeArticleStatus(row.id, next)
  ElMessage.success(next === 1 ? '已发布' : '已下架')
  load()
}

async function onDelete(row: ArticleItem) {
  await ElMessageBox.confirm(`确定删除《${row.title}》吗？`, '提示', { type: 'warning' })
  await deleteArticle(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(async () => {
  categories.value = await listCategories()
  load()
})
</script>
