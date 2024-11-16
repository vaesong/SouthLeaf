<template>
  <view v-if="isVisible" class="modal-overlay" @click="close">
    <view class="modal-content" @click.stop>
      <view class="modal-header">
        <text>{{ title }}</text>
        <span class="close-button" @click="close">✕</span>
      </view>
      <view class="modal-body">
        <text class="modal-text">{{ description }}</text>
        <image class="modal-image" :src="imageSrc" mode="aspectFit" />
      </view>
    </view>
  </view>
</template>

<script>
export default {
  props: {
    isVisible: Boolean,
    title: String,
    imageSrc: String,
    description: String
  },
  methods: {
    close() {
      this.$emit("close");
    }
  }
};
</script>

<style scoped>
  @import url("ModalPopup.css");
</style>