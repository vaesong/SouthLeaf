<template>
  <view class="detail-page">
    <!-- 顶部静态大图 -->
    <view class="image-container">
      <image :src="work.imageSrc[0]" class="detail-image" mode="aspectFill" />
    </view>

    <!-- 标题和布局切换按钮 -->
    <view class="detail-header">
      <text class="work-title">{{ work.title }}</text>
      <ToggleLayoutButton :initialLayout="isGridLayout" @toggle-layout="updateLayoutClass" />
    </view>

    <!-- 分类标签 -->
    <view class="work-category" @click="goToWorks">#{{ work.category }}</view>

    <!-- 描述信息 -->
    <view class="detail-description">
      <text class="description-text">{{ work.description }}</text>
    </view>

    <!-- 图片展示区域，根据布局控制排列方式 -->    
    <view :class="['image-gallery', layoutClass]">
      <view 
        v-for="(image, index) in work.imageSrc" 
        :key="index" 
        class="gallery-image" 
      >
        <image :src="image" mode="widthFix" class="gallery-image" />
      </view>
    </view>

    <FooterInfo />
  </view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue';
import ToggleLayoutButton from '@/components/ToggleLayoutButton/ToggleLayoutButton.vue';

export default {
  components: {
    FooterInfo,
    ToggleLayoutButton
  },
  data() {
    return {
      work: {
        id: null,
        title: '',
        description: '',
        author: '',
        category: '',
        package: '',
        imageSrc: [],
        time: ''
      },
      layoutClass: 'grid-layout', // 控制初始布局状态
    };
  },
  computed: {
    // 根据 layoutClass 返回布尔值，控制 ToggleLayoutButton 的初始布局
    isGridLayout() {
      return this.layoutClass === 'grid-layout';
    }
  },
  onLoad(options) {
    const id = options.id;
    this.loadWorkDetails(id);
  },
  methods: {
    loadWorkDetails(id) {
      const work = {
        id: 1,
        title: '男孩写真',
        description: '这是一个创始人档的作品，包含丰富的内容和回忆...',
        author: '风言雾语',
        category: '写真',
        package: '写真',
        imageSrc: [
          '/static/works/han_0.jpg',
          '/static/works/han_1.jpg',
          '/static/works/han_2.jpg',
          '/static/works/han_3.jpg',
          '/static/works/han_4.jpg',
          '/static/works/han_5.jpg',
          '/static/works/han_6.jpg',
          '/static/works/han_7.jpg',
          '/static/works/han_8.jpg'
        ],
        time: '2023-08-15'
      };
      if (work && work.id === parseInt(id)) {
        this.work = work;
      }
    },
    // 接收 ToggleLayoutButton 传递的布局类名
    updateLayoutClass(newLayoutClass) {
      this.layoutClass = newLayoutClass === 'grid-layout' ? 'grid-layout' : newLayoutClass;
    },
    goToWorks() {
      // 存储 category 参数到全局状态或本地存储
      getApp().globalData.selectedCategory = this.work.category;
      // 使用 switchTab 跳转到 TabBar 页面
      uni.switchTab({
        url: "/pages/works/works"
      });
    }
  }
};
</script>

<style scoped>
@import url("workdetail.css");
</style>