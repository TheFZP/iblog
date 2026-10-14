<template>
  <div class="page-card">
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="onSearch">
        <el-option label="待审核" :value="0" />
        <el-option label="已通过" :value="1" />
      </el-select>
      <el-button @click="onSearch">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" style="width: 100%">
      <el-table-column prop="nickname" label="昵称" width="140" />
      <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
      <el-table-column prop="articleTitle" label="所属文章" min-width="180" show-overflow-tooltip />
      <el-table-column prop="email" label="邮箱" width="180" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'warning'" size="small">
            {{ row.status === 1 ? '已通过' : '待审核' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="提交时间" width="170" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status !== 1" link type="primary" @click="onApprove(row)">通过</el-button>
          <el-button v-else link type="warning" @click="onReject(row)">驳回</el-button>
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
  approveComment,
  deleteComment,
  pageComments,
  rejectComment,
  type CommentItem
} from '@/api/taxonomy'

const loading = ref(false)
const list = ref<CommentItem[]>([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: null as number | null
})

async function load() {
  loading.value = true
  try {
    const data = await pageComments({ ...query })
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

async function onApprove(row: CommentItem) {
  await approveComment(row.id)
  ElMessage.success('已通过')
  load()
}

async function onReject(row: CommentItem) {
  await rejectComment(row.id)
  ElMessage.success('已驳回')
  load()
}

async function onDelete(row: CommentItem) {
  await ElMessageBox.confirm('确定删除这条评论吗？', '提示', { type: 'warning' })
  await deleteComment(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
