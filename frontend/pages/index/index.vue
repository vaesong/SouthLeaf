<template>
	<view class="index-page">
		<!-- 轮播图区域  indicator-dots="true"  -->
    <swiper :autoplay="true" interval="3000" circular="true" class="top-swiper"
      indicator-color="rgba(255, 255, 255, 0.5)" indicator-active-color="#ffffff">
      <swiper-item v-for="(image, index) in swiperImages" :key="index">
        <image :src="image" class="swiper-image" mode="aspectFill" />
      </swiper-item>
    </swiper>

		<!-- 分类图标区域 -->
    <view class="category-section">
      <view v-for="(category, index) in filteredCategories" :key="category.id" class="category-item" @click="goToWorks(category.name)">
        <view class="icon-background">
          <image :src="encodeURI(category.images[0])" class="category-icon" mode="aspectFill" />
        </view>
        <text class="category-name">{{ category.name }}</text>
      </view>
    </view>

		<!-- 作品展示模块 -->
    <view class="section-container">
      <!-- 作品欣赏标题部分 -->
      <SectionHeader
        title="作品欣赏"
        leftColor="#4CAF50"
        :showMore="true"
        @more-click="goToWorks('全部')"
      />

      <!-- 作品展示网格布局 -->
      <view class="works-grid">
        <view v-for="(work, index) in works" :key="index" class="work-item" @click="viewWorkDetails(work)">
          <view class="image-wrapper">
            <image :src="work.imageSrc[0]" class="work-image" mode="aspectFill" />
          </view>
          <text class="work-title">{{ work.title }}</text>
        </view>
      </view>
    </view>

		<!-- 灰色分隔线 -->
		<view class="divider-line"></view>

		<!-- 套餐展示模块 -->
    <view class="section-container">
      <!-- 作品欣赏标题部分 -->
      <SectionHeader
        title="服务套餐"
        leftColor="#4CAF50"
        :showMore="true"
        @more-click="goToPackagess('全部')"
      />

      <!-- 作品展示网格布局 -->
      <view class="works-grid">
        <view v-for="(pkg, index) in packages" :key="index" class="work-item" @click="viewPkgDetails(pkg)">
          <view class="image-wrapper">
            <image :src="pkg.images[0]" class="work-image" mode="aspectFill" />
          </view>
          <text class="work-title">{{ pkg.title }}</text>
        </view>
      </view>
    </view>

		<!-- 灰色分隔线 -->
		<view class="divider-line"></view>

		<!-- 用户评价展示模块 -->
    <view class="section-container">
      <!-- 作品欣赏标题部分 -->
      <SectionHeader
        title="用户评价"
        leftColor="#4CAF50"
        :showMore="false"
      />

			<view class="review-placeholder">
        <image src="/static/index/review.png" class="placeholder-image" mode="widthFix" />
      </view>
    </view>

		<!-- 底部信息区域 -->
		<FooterInfo />
	</view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue';
import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";
import SectionHeader from "@/components/SectionHeader/SectionHeader.vue";

import { getAllCategories } from '@/api/category';
import { getSwipperImages } from '@/api/swipper';
import { getAllPackages } from '@/api/package';

export default {
	components: {
		FooterInfo,
		ModalPopup,
		SectionHeader
	},
	data() {
		return {
			// 后端接口，获取轮播区图片
			swiperImages: [],
			
			//后期接口，获取所有的目录类型
			categories: [],

			// 后端接口，获取作品信息（可选择个数，默认全部）
			works:[
			{
          id: 1,
          title: '男孩写真fdhjisakfhasffashjkfdhaslkfhasklfhslhjkd',
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
			],

			// 后端接口，获取套系信息（可选择个数，默认全部）
			packages: [],
		};
	},
	computed: {
    filteredCategories() {
      // 过滤掉 id 为 0 的 "全部"目录
      return this.categories.filter(category => category.name !== '全部');
    }
  },
	methods: {
    // 后端接口，获取分类信息
    async fetchCategories() {
			try {
				// 调用后端接口获取分类数据
				this.categories = await getAllCategories();
			} catch (error) {
				console.error('加载类别失败:', error.message);
			}
		},

    // 后端接口，获取轮播区数据
    async fetchSwippers() {
			try {
				this.swiperImages = await getSwipperImages();
			} catch (error) {
				console.error('加载轮播区失败:', error.message);
			}
		},

    // 后端接口，获取套系数据
    async fetchPackages() {
			try {
				this.packages = await getAllPackages();
			} catch (error) {
				console.error('加载套系失败:', error.message);
			}
		},

		goToWorks(categoryName) {
      // 将 categoryName 存储到全局状态或本地存储
      getApp().globalData.selectedCategory = categoryName;
      
      // 使用 switchTab 跳转到 TabBar 页面
      uni.switchTab({
        url: "/pages/works/works"
      });
    },
		goToPackagess(categoryName) {
      // 将 categoryName 存储到全局状态或本地存储
      getApp().globalData.selectedCategory = categoryName;
      
      // 使用 switchTab 跳转到 TabBar 页面
      uni.switchTab({
        url: "/pages/package/package"
      });
    },
		// 点击查看作品详情
    viewWorkDetails(work) {
      // 这里可以跳转到详情页面，传递作品的详细信息
      uni.navigateTo({
        url: `/pages/works/workdetail?id=${work.id}`
      });
    },
		// 点击查看套系详情
    viewPkgDetails(pkg) {
      // 这里可以跳转到详情页面，传递作品的详细信息
      uni.navigateTo({
        url: `/pages/package/pkgdetail?id=${pkg.id}`
      });
    }
	},
  mounted() {
		this.fetchCategories(); // 页面加载时获取分类数据
    this.fetchSwippers(); // 页面加载时获取轮播区数据
    this.fetchPackages(); // 页面加载时获取套系数据
	},
};
</script>


<style scoped>
@import url("index.css")
</style>
