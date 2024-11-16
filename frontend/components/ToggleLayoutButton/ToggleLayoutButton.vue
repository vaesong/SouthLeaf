<template>
  <view class="toggle-layout-btn" @click="handleToggle">
    <image :src="currentIcon" style="width: 100%; height: 100%;" mode="aspectFit"/>
  </view>
</template>

<script>
export default {
  props: {
    initialLayout: {
      type: Boolean,
      default: true // 默认网格布局
    }
  },
  data() {
    return {
      isGridLayout: this.initialLayout, // 内部管理布局状态
      gridIcon: '/static/icons/grid.png',
      listIcon: '/static/icons/list.png'
    };
  },
  computed: {
    currentIcon() {
      return this.isGridLayout ? this.gridIcon : this.listIcon;
    }
  },
  methods: {
    handleToggle() {
      this.isGridLayout = !this.isGridLayout; // 切换布局状态
      const layoutClass = this.isGridLayout ? 'grid-layout' : 'list-layout'; // 根据状态返回类名
      this.$emit('toggle-layout', layoutClass); // 发出事件并传递类名
    }
  },
};
</script>

<style>
.toggle-layout-btn {
  max-width: 60rpx;
  width: 40rpx;
  height: 40rpx;
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
