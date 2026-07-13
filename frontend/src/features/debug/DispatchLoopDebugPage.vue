<script setup lang="ts">
import { useRouter } from 'vue-router'
import DispatchLoopDebugView from '../../views/dispatch/DispatchLoopDebugView.vue'

const router = useRouter()
</script>

<template>
  <div class="legacy-surface">
    <DispatchLoopDebugView @back="router.push('/overview')" />
  </div>
</template>
