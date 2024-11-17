<template>
  <view class="section-header">
    <view class="left-color-bar" :style="{ backgroundColor: leftColor }"></view>
    <text class="title">{{ title }}</text>
    <view v-if="showMore" class="more-arrow" @click="onMoreClick">
      <uni-icons type="arrowright" size="16" color="#333" />
    </view>
  </view>
</template>

<script>
export default {
  name: "SectionHeader",
  props: {
    leftColor: {
      type: String,
      default: "#4CAF50" // 默认左边颜色为绿色
    },
    title: {
      type: String,
      required: true
    },
    showMore: {
      type: Boolean,
      default: false
    }
  },
  methods: {
    onMoreClick() {
      this.$emit("more-click");
    }
  }
};
</script>

<style scoped>

.section-header {
  display: flex;
  align-items: center;
  flex-direction: row;
  justify-content: space-between;
  width: 100%;
  margin-bottom: 20rpx;
}

.left-color-bar {
  width: 8rpx;
  height: 36rpx;
  margin-right: 10rpx;
  border-radius: 4rpx;
}

.title {
  font-size: 28rpx;
  color: #333;
  font-weight: bold;
  flex-grow: 1;
}

.more-arrow {
  display: flex;
  align-items: center;
  color: #333;
}

</style>
