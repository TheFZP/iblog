<template>
  <svg
    class="wx"
    :class="`wx--${category}`"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    role="img"
    :aria-label="type || '天气'"
  >
    <template v-if="category === 'clear'">
      <circle class="sun" cx="32" cy="32" r="11" />
      <path class="ray" d="M32 6v7M32 51v7M6 32h7M51 32h7M13.6 13.6l5 5M45.4 45.4l5 5M50.4 13.6l-5 5M18.6 45.4l-5 5" />
    </template>

    <template v-else-if="category === 'partly'">
      <circle class="sun" cx="24" cy="24" r="9" />
      <path class="ray" d="M24 7v5M7 24h5M12 12l3.5 3.5M36 12l-3.5 3.5" />
      <path class="cloud-fill" d="M22 50h24a9 9 0 0 0 1-18 13 13 0 0 0-24.6-3A10 10 0 0 0 22 50Z" />
    </template>

    <template v-else-if="category === 'overcast'">
      <path class="cloud-fill" d="M16 44h28a8 8 0 0 0 1-16 12 12 0 0 0-22.6-2.7A9 9 0 0 0 16 44Z" />
      <path class="cloud-fill cloud-fill--back" d="M14 52h26a10 10 0 0 0 2-19.8" />
    </template>

    <template v-else-if="category === 'rain'">
      <path class="cloud-fill" d="M16 40h32a9 9 0 0 0 1-18 13 13 0 0 0-24.6-3A10 10 0 0 0 16 40Z" />
      <path class="drop" d="M22 47l-3 7M32 47l-3 7M42 47l-3 7" />
    </template>

    <template v-else-if="category === 'storm'">
      <path class="cloud-fill" d="M16 38h32a9 9 0 0 0 1-18 13 13 0 0 0-24.6-3A10 10 0 0 0 16 38Z" />
      <path class="bolt" d="M35 43 27 54h6l-3 6 10-12h-6l4-5Z" />
    </template>

    <template v-else-if="category === 'snow'">
      <path class="cloud-fill" d="M16 38h32a9 9 0 0 0 1-18 13 13 0 0 0-24.6-3A10 10 0 0 0 16 38Z" />
      <path class="snow" d="M24 48v8M20.5 50l7 4M27.5 50l-7 4M40 48v8M36.5 50l7 4M43.5 50l-7 4" />
    </template>

    <template v-else-if="category === 'fog'">
      <path class="cloud-fill" d="M18 34h30a8 8 0 0 0 1-16 12 12 0 0 0-22.6-2.7A9 9 0 0 0 18 34Z" />
      <path class="line" d="M12 44h40M18 51h30M26 58h20" />
    </template>

    <template v-else>
      <path class="line" d="M8 26h26a7 7 0 1 0-7-7M8 38h34a7 7 0 1 1-7 7M8 50h20a6 6 0 1 1-6 6" />
    </template>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ type?: string; size?: number }>(), {
  type: '',
  size: 64
})

type Category = 'clear' | 'partly' | 'overcast' | 'rain' | 'storm' | 'snow' | 'fog' | 'wind'

const category = computed<Category>(() => {
  const text = props.type || ''
  if (text.includes('雷')) return 'storm'
  if (text.includes('雪') || text.includes('冰')) return 'snow'
  if (text.includes('雨')) return 'rain'
  if (text.includes('雾') || text.includes('霾')) return 'fog'
  if (text.includes('阴')) return 'overcast'
  if (text.includes('多云')) return 'partly'
  if (text.includes('晴')) return 'clear'
  if (text.includes('风')) return 'wind'
  return 'partly'
})
</script>

<style scoped>
.wx {
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.sun {
  fill: color-mix(in srgb, currentColor 22%, transparent);
}
.ray,
.line,
.drop,
.snow,
.bolt {
  fill: none;
}
.cloud-fill {
  fill: color-mix(in srgb, currentColor 14%, transparent);
}
.cloud-fill--back {
  opacity: 0.55;
}
.bolt {
  fill: currentColor;
  stroke-width: 1.6;
}

.wx--clear,
.wx--partly {
  color: #e8a317;
}
.wx--overcast {
  color: #8792a2;
}
.wx--rain,
.wx--storm {
  color: #4f8fce;
}
.wx--snow {
  color: #7fb4d8;
}
.wx--fog {
  color: #97a0aa;
}
.wx--wind {
  color: #6f9b8a;
}

:global(:root[data-theme='dark']) .wx--clear,
:global(:root[data-theme='dark']) .wx--partly {
  color: #f0b84a;
}
:global(:root[data-theme='dark']) .wx--rain,
:global(:root[data-theme='dark']) .wx--storm {
  color: #6fa9e0;
}
</style>
