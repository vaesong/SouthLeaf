<template>
  <view class="works-page">
    <!-- 顶部栏 -->
    <view class="top-bar">
      <!-- 分类选项 -->
      <scroll-view 
        class="category-bar" 
        scroll-x 
        :scroll-left="scrollLeft"
      >
        <view
          v-for="(category, index) in categories"
          :key="category.id"
          :id="'category-item-' + index"
          class="category-item"
          :class="{ active: selectedCategory === category.name }"
          @click.stop="selectCategory(category, index)"
        >
          <text>{{ category.name }}</text>
          <span class="underline"></span>
        </view>
      </scroll-view>


      <!-- 搜索图标 -->
      <image 
        class="search-icon" 
        src="/static/icons/search.png" 
        @click="navigateToSearchPage" 
        mode="aspectFit" 
      />

      <!-- 切换排列方式按钮 -->
      <view class="toggle-layout-btn-container">
        <ToggleLayoutButton 
          :initialLayout="isGridLayout" 
          @toggle-layout="updateLayoutClass"
        />
      </view>
    </view>

    <!-- 判断展示内容 -->
    <view v-if="selectedCategory === '全部'" class="all-categories-view">
      <view 
        v-for="category in categories.filter(cat => cat.name !== '全部')"
        :key="category.id" 
        class="category-preview" 
        @click="selectCategory(category, index)"
      >
        <!-- 底部图片 -->
        <image :src="category.icons[0]" class="category-icon" mode="aspectFill" />
        
        <!-- 半透明覆盖层 -->
        <view class="overlay">
          <text class="category-name">{{ category.name }}</text>
          <text class="category-desc">{{ category.desc }}</text>
        </view>
      </view>
    </view>

    <view v-else :class="['works-content', layoutClass]">
      <!-- 原来的作品展示内容 -->
      <view v-for="work in filteredWorks" :key="work.id" :class="['work-item', layoutClass]" @click="viewDetails(work)">
        <image :src="work.imageSrc[0]" class="work-image" mode="aspectFill"/>
        <view class="work-info">
          <text class="work-title">{{ work.title }}</text>
          <text class="work-category">#{{ work.category }}</text>
          <text v-if="layoutClass === 'list-layout'" class="work-description">{{ work.description }}</text>
        </view>
      </view>
    </view>

    <!-- 底部信息区域 -->
    <FooterInfo />
  </view>
</template>


<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue';
import ToggleLayoutButton from '@/components/ToggleLayoutButton/ToggleLayoutButton.vue';

export default {
  components: { FooterInfo, ToggleLayoutButton },
  data() {
    return {
      //后期接口，获取所有的目录类型
      categories: [
				{ id: 0, name: '全部', desc: 'ALL', icons: ['/static/works/han_2.jpg']},
        { id: 1, name: '创始人档', desc: 'Founder Portfolio', icons: ['/static/works/han_2.jpg'] },
        { id: 2, name: '儿童', desc: 'Child Portrait', icons: ['/static/works/han_2.jpg'] },
        { id: 3, name: '亲子全家福', desc: 'Family Portrait', icons: ['/static/works/han_2.jpg'] },
        { id: 4, name: '婚纱', desc: 'Bridal Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 5, name: '写真', desc: 'Portrait Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 6, name: '孕照', desc: 'Maternity Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 7, name: '证件照', desc: 'Passport Photo', icons: ['/static/works/han_2.jpg'] },
      ],
      selectedCategory: "全部",
      layoutClass: 'grid-layout', // 控制图片排列方式
      layoutIcon: '/static/icons/grid.png', // 初始为网格图标
      scrollLeft: 0,

      allWorks: [
        {
          id: 1,
          title: '男孩写真',
          description: '这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/han_0.jpg',
            '/static/works/han_1.jpg',
            '/static/works/han_2.jpg'
          ],
          time: '2023-08-15'
        },
        {
          id: 2,
          title: '圣诞女孩',
          description: '这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg',
            '/static/works/kid_2.jpg',
            '/static/works/kid_3.jpg',
            '/static/works/kid_4.jpg',
            '/static/works/kid_5.jpg'
          ],
          time: '2023-07-10'
        },
        {
          id: 3,
          title: '男孩写真',
          description: '这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/han_0.jpg',
            '/static/works/han_1.jpg',
            '/static/works/han_2.jpg'
          ],
          time: '2023-08-15'
        },
        {
          id: 4,
          title: '圣诞女孩',
          description: '这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg'
          ],
          time: '2023-07-10'
        },
        {
          id: 5,
          title: '男孩写真',
          description: '这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/han_0.jpg',
            '/static/works/han_1.jpg',
            '/static/works/han_2.jpg'
          ],
          time: '2023-08-15'
        },
        {
          id: 6,
          title: '圣诞女孩',
          description: '这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg'
          ],
          time: '2023-07-10'
        },
        {
          id: 7,
          title: '男孩写真',
          description: '这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。这是一个创始人档的作品，包含丰富的内容和回忆。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/han_0.jpg',
            '/static/works/han_1.jpg',
            '/static/works/han_2.jpg'
          ],
          time: '2023-08-15'
        },
        {
          id: 8,
          title: '圣诞女孩',
          description: '这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。这是一个B档的作品，具有独特的风格和构图。',
          author: '风言雾语',
          category: '创始人档',
          package: '写真',
          imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg'
          ],
          time: '2023-07-10'
        },
      ]
    };
  },
  onShow() {
    const app = getApp();
    if (app && app.globalData && app.globalData.selectedCategory) {
      this.selectedCategory = app.globalData.selectedCategory;
      app.globalData.selectedCategory = null; // 清除全局状态，防止重复触发
    }
  },
  computed: {
    //后期接口，根据目录从服务器返回数据
    filteredWorks() {
      return this.allWorks.filter(work => work.category === this.selectedCategory);
    },
    // 根据 layoutClass 返回布尔值，控制 ToggleLayoutButton 的初始布局
    isGridLayout() {
      return this.layoutClass === 'grid-layout';
    },
    // 获取每个分类的一个预览图片
    previewImages() {
      // 生成每个分类的预览图片，只保留每个分类的第一个作品
      const previews = {};
      this.allWorks.forEach(work => {
        if (!previews[work.category]) {
          previews[work.category] = work.imageSrc[0]; // 获取每个分类的第一张图片
        }
      });
      return previews;
    },
  },
  methods: {
    updateLayoutClass(newClass) {
      this.layoutClass = newClass; // 接收切换按钮传来的类名
    },

    scrollToLastVisibleCategory(index) {
      const additionalOffset = 25; // 额外的滚动偏移量，单位为像素
      const query = uni.createSelectorQuery().in(this);
      query.select(`#category-item-${index}`).boundingClientRect();
      query.select('.category-bar').boundingClientRect();

      query.exec((res) => {
        if (res[0] && res[1]) {
          const itemRect = res[0]; // 点击的元素的矩形信息
          const scrollViewRect = res[1]; // `scroll-view` 容器的矩形信息
          // 判断是否需要向右滚动
          if ((itemRect.right + additionalOffset) > scrollViewRect.left + scrollViewRect.width) {
            // 只滚动一个目录项的宽度
            this.scrollLeft += (itemRect.width + additionalOffset);
          }
          // 判断是否需要向左滚动
          else if ((itemRect.left - additionalOffset) < scrollViewRect.left) {
            // 向左滚动一个目录项的宽度
            this.scrollLeft -= (itemRect.width + additionalOffset);
          }
        }
      });
    },

    selectCategory(category, index) {
      this.selectedCategory = category.name;
      // 更新 scrollIntoViewId，让 scroll-view 滚动到对应的目录项
      this.scrollToLastVisibleCategory(index);
    },
    navigateToSearchPage() {
      // 跳转到搜索页面
      uni.showToast({
          title: '开发中，敬请期待( •̀ .̫ •́ )✧', // 显示的提示内容
          icon: 'none',        // 不使用图标
          duration: 1000       // 显示时长，单位为毫秒（2秒后消失）
      });
    },
    // 点击作品查看详情
    viewDetails(work) {
      // 这里可以跳转到详情页面，传递作品的详细信息
      uni.navigateTo({
        url: `/pages/works/workdetail?id=${work.id}`
      });
    }
  },
};
</script>


<style scoped>
@import url("works.css");
</style>