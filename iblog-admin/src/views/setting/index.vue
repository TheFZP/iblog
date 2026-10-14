<template>
  <div class="page-card" v-loading="loading">
    <el-alert
      title="站点配置读取时带 Redis 缓存，保存后会自动失效，前台刷新即可看到最新内容。"
      type="info"
      :closable="false"
      style="margin-bottom: 18px"
    />

    <el-form :model="form" label-width="110px" style="max-width: 680px">
      <el-divider content-position="left">基础信息</el-divider>
      <el-form-item label="站点标题">
        <el-input v-model="form.site_title" />
      </el-form-item>
      <el-form-item label="副标题">
        <el-input v-model="form.site_subtitle" />
      </el-form-item>
      <el-form-item label="站点描述">
        <el-input v-model="form.site_description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="SEO 关键词">
        <el-input v-model="form.site_keywords" placeholder="英文逗号分隔" />
      </el-form-item>
      <el-form-item label="页脚文案">
        <el-input v-model="form.site_footer" />
      </el-form-item>
      <el-form-item label="备案号">
        <el-input v-model="form.site_icp" placeholder="如：京ICP备xxxxxxxx号" />
      </el-form-item>

      <el-divider content-position="left">作者信息</el-divider>
      <el-form-item label="作者名称">
        <el-input v-model="form.author_name" />
      </el-form-item>
      <el-form-item label="作者头像">
        <el-input v-model="form.author_avatar" placeholder="图片 URL" />
      </el-form-item>
      <el-form-item label="作者简介">
        <el-input v-model="form.author_intro" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="GitHub">
        <el-input v-model="form.github_url" />
      </el-form-item>
      <el-form-item label="联系邮箱">
        <el-input v-model="form.email" />
      </el-form-item>

      <el-divider content-position="left">功能开关</el-divider>
      <el-form-item label="开启评论">
        <el-switch v-model="commentEnabled" />
      </el-form-item>
      <el-form-item label="列表每页条数">
        <el-input-number v-model="pageSize" :min="5" :max="50" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存配置</el-button>
        <el-button @click="load">重置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSiteConfig, saveSiteConfig } from '@/api/taxonomy'

const loading = ref(false)
const saving = ref(false)
const form = reactive<Record<string, string>>({})

const commentEnabled = computed({
  get: () => form.comment_enabled === '1',
  set: (value: boolean) => {
    form.comment_enabled = value ? '1' : '0'
  }
})

const pageSize = computed({
  get: () => Number(form.page_size || 10),
  set: (value: number) => {
    form.page_size = String(value)
  }
})

async function load() {
  loading.value = true
  try {
    const data = await getSiteConfig()
    Object.assign(form, data)
  } finally {
    loading.value = false
  }
}

async function onSave() {
  saving.value = true
  try {
    await saveSiteConfig({ ...form })
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
