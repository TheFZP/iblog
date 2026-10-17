<script setup lang="ts">
import { ref, watch } from 'vue'
import { usePositions, type Position } from '@/composables/usePositions'
import { fmtNum } from '@/utils/stockFormat'

/**
 * 持仓录入/编辑弹窗。
 *
 * <p>同一只股票只保留一条持仓记录（加仓就在原记录上改成本价和数量），
 * 与桌面版口径一致 —— 多批次加权成本是另一套功能，这里不做。</p>
 */

const props = defineProps<{
  show: boolean
  /** 编辑已有持仓时传入；新增时传 null */
  editing?: Position | null
  /** 从详情页打开时预填的股票 */
  preset?: { code: string; name: string; price?: number } | null
}>()

const emit = defineEmits<{ close: [] }>()

const { positions, upsertPosition, removePosition } = usePositions()

const code = ref('')
const name = ref('')
const buyPrice = ref<number | null>(null)
const quantity = ref<number | null>(null)
const buyDate = ref('')
const error = ref('')

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

watch(
  () => props.show,
  (visible) => {
    if (!visible) {
      return
    }
    error.value = ''
    if (props.editing) {
      code.value = props.editing.code
      name.value = props.editing.name
      buyPrice.value = props.editing.buyPrice || null
      quantity.value = props.editing.quantity || null
      buyDate.value = props.editing.buyDate || todayStr()
    } else if (props.preset) {
      code.value = props.preset.code
      name.value = props.preset.name
      // 新增时用现价预填成本价，用户改一下比例子从空开始快得多
      buyPrice.value = props.preset.price && props.preset.price > 0 ? Number(props.preset.price.toFixed(2)) : null
      quantity.value = null
      buyDate.value = todayStr()
    } else {
      code.value = ''
      name.value = ''
      buyPrice.value = null
      quantity.value = null
      buyDate.value = todayStr()
    }
  }
)

/** 成本价 × 数量，实时预览，让用户确认没填错位数 */
function previewCost(): string {
  const p = Number(buyPrice.value) || 0
  const q = Number(quantity.value) || 0
  if (p <= 0 || q <= 0) {
    return '--'
  }
  return `¥${fmtNum(p * q)}`
}

function submit() {
  if (!code.value.trim()) {
    error.value = '请填写股票代码'
    return
  }
  const p = Number(buyPrice.value)
  const q = Number(quantity.value)
  if (!p || p <= 0) {
    error.value = '请填写有效的成本价'
    return
  }
  // 数量必须是 100 的整数倍（一手 = 100 股），港股同理
  if (!q || q <= 0) {
    error.value = '请填写有效的持仓数量'
    return
  }
  if (q % 100 !== 0) {
    error.value = '持仓数量必须是 100 的整数倍（1 手 = 100 股）'
    return
  }
  upsertPosition({
    code: code.value.trim(),
    name: name.value.trim() || code.value.trim(),
    buyPrice: p,
    quantity: q,
    buyDate: buyDate.value || todayStr()
  })
  emit('close')
}

function doRemove() {
  if (props.editing) {
    removePosition(props.editing.code)
    emit('close')
  }
}
</script>

<template>
  <div v-if="show" class="modal" @click.self="emit('close')">
    <div class="modal__box" role="dialog" aria-modal="true" aria-label="持仓录入">
      <header class="modal__head">
        <h2 class="modal__title">{{ editing ? '编辑持仓' : '添加持仓' }}</h2>
        <button class="modal__close" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <div class="form">
        <div class="form__row">
          <label class="form__label" for="pos-code">股票代码</label>
          <input
            id="pos-code"
            v-model="code"
            class="form__input"
            :readonly="!!editing"
            placeholder="600519"
            @keydown.enter="submit"
          >
        </div>

        <div class="form__row">
          <label class="form__label" for="pos-name">名称</label>
          <input
            id="pos-name"
            v-model="name"
            class="form__input"
            placeholder="贵州茅台（可留空）"
            @keydown.enter="submit"
          >
        </div>

        <div class="form__row">
          <label class="form__label" for="pos-price">成本价</label>
          <input
            id="pos-price"
            v-model.number="buyPrice"
            class="form__input"
            type="number"
            step="0.01"
            min="0"
            placeholder="0.00"
            @keydown.enter="submit"
          >
        </div>

        <div class="form__row">
          <label class="form__label" for="pos-qty">数量</label>
          <input
            id="pos-qty"
            v-model.number="quantity"
            class="form__input"
            type="number"
            step="100"
            min="0"
            placeholder="100"
            @keydown.enter="submit"
          >
          <span class="form__hint">股，需为 100 的整数倍</span>
        </div>

        <div class="form__row">
          <label class="form__label" for="pos-date">买入日期</label>
          <input id="pos-date" v-model="buyDate" class="form__input" type="date">
        </div>

        <div class="form__row">
          <span class="form__label">成本金额</span>
          <span class="form__cost">{{ previewCost() }}</span>
        </div>

        <p v-if="error" class="form__error">{{ error }}</p>

        <div class="form__actions">
          <button v-if="editing" class="btn btn--ghost form__danger" @click="doRemove">删除持仓</button>
          <button class="btn btn--ghost" @click="emit('close')">取消</button>
          <button class="btn btn--primary" @click="submit">保存</button>
        </div>
      </div>

      <p v-if="positions.length > 0 && !editing" class="modal__foot">
        当前已记录 {{ positions.length }} 条持仓，重复添加同一只股票会覆盖原记录。
      </p>
    </div>
  </div>
</template>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: var(--s-5);
  background: var(--c-overlay);
  backdrop-filter: blur(2px);
}

.modal__box {
  width: min(440px, 100%);
  max-height: 88vh;
  overflow-y: auto;
  padding: var(--s-6);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.24);
}

.modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--s-4);
}

.modal__title {
  margin: 0;
  font-size: var(--t-lg);
  font-weight: 600;
}

.modal__close {
  border: 0;
  background: none;
  color: var(--c-text-faint);
  font-size: var(--t-md);
  cursor: pointer;
  padding: var(--s-1);
}

.modal__close:hover {
  color: var(--c-text);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
}

.form__row {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.form__label {
  width: 68px;
  flex: none;
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.form__hint {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.form__input {
  flex: 1;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-bg);
  color: var(--c-text);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.form__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.form__input:read-only {
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
}

.form__cost {
  font-size: var(--t-base);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.form__error {
  margin: 0;
  font-size: var(--t-sm);
  color: #d93025;
}

.form__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-3);
  margin-top: var(--s-2);
}

.form__danger {
  color: #d93025;
  margin-right: auto;
}

.modal__foot {
  margin: var(--s-4) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  line-height: 1.6;
}
</style>
