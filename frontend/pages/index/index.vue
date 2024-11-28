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
          <image :src="category.icons[0]" class="category-icon" mode="aspectFill" />
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
            <image :src="pkg.imageSrc[0]" class="work-image" mode="aspectFill" />
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

export default {
	components: {
		FooterInfo,
		ModalPopup,
		SectionHeader
	},
	data() {
		return {
			// 后端接口，获取轮播区图片
			swiperImages: [
          '/static/works/han_0.jpg',
          '/static/works/han_1.jpg',
          '/static/works/han_2.jpg',
          '/static/works/han_3.jpg',
          '/static/works/han_4.jpg',
        ],
			
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
			packages: [
      {
        id: 0, 
        title: '秋日物语', 
        description: '在这温馨璀璨的圣诞季，每一帧写真都定格了冬日里最温暖的笑容与梦幻光影。银铃轻响中，我们捕捉到了节日的魔法，让爱与祝福在镜头下静静绽放，封存成永恒的记忆篇章。',
        category: '儿童',  
        imageSrc: [
          '/static/works/han_0.jpg',
          '/static/works/han_1.jpg',
          '/static/works/han_2.jpg'
        ],

        price: 618,
        activityPrice: 328,
        activateStart: '2024-09-01',
        activateEnd: '2024-10-31',
        services: [
            '服务内容：12小时拍摄，全天不少于2000张原片，精修保底100张起，当日预告不少于30张',
            '三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）进口微喷工艺相册一本',
            '附加项目：当日如需快修加收RMB2000元，三机位不少于80张增加机位RMB5000/总监机位，RMB3000/普通机位',
            '※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询'
        ],
        reminder: [
          '※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清',
          '※ 产品交付时间15个工作日，以收到尾款开始计算',
          '※ 客户如需自行选片必须提前告知',
          '※ 我们不接受除高质量问题外任何原因的相册重做要求',
        ]
      },
      {
        id: 1,
        title: '圣诞主题',
        description: '捕捉秋日美景的每一个瞬间，金黄色的落叶、温暖的光影为婚礼增添浪漫的氛围。让这一季的秋色成为永恒的记忆，记录下新人的幸福时刻，感受岁月静好的秋日情怀。',
        category: '写真',
        imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg',
            '/static/works/kid_2.jpg',
            '/static/works/kid_3.jpg',
            '/static/works/kid_4.jpg',
            '/static/works/kid_5.jpg'
          ],
        price: 520,
        activityPrice: 450,
        activateStart: '2024-09-01',
        activateEnd: '2024-10-31',
        services: [
            '服务内容：8小时拍摄，全天不少于1500张原片，精修保底80张，当日预告不少于20张',
            '三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）高级相册一本，秋季主题定制相框一幅',
            '附加项目：当日如需快修加收RMB1500元，三机位不少于60张增加机位RMB4000/总监机位，RMB2500/普通机位',
            '※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询'
        ],
        reminder: [
          '※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清',
          '※ 产品交付时间15个工作日，以收到尾款开始计算',
          '※ 客户如需自行选片必须提前告知',
          '※ 我们不接受除高质量问题外任何原因的相册重做要求',
        ]
      },
      {
        id: 2, 
        title: '秋日物语', 
        description: '在这温馨璀璨的圣诞季，每一帧写真都定格了冬日里最温暖的笑容与梦幻光影。银铃轻响中，我们捕捉到了节日的魔法，让爱与祝福在镜头下静静绽放，封存成永恒的记忆篇章。',
        category: '儿童',  
        imageSrc: [
          '/static/works/han_0.jpg',
          '/static/works/han_1.jpg',
          '/static/works/han_2.jpg'
        ],

        price: 618,
        activityPrice: 328,
        activateStart: '2024-09-01',
        activateEnd: '2024-10-31',
        services: [
            '服务内容：12小时拍摄，全天不少于2000张原片，精修保底100张起，当日预告不少于30张',
            '三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）进口微喷工艺相册一本',
            '附加项目：当日如需快修加收RMB2000元，三机位不少于80张增加机位RMB5000/总监机位，RMB3000/普通机位',
            '※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询'
        ],
        reminder: [
          '※ 我们将收取全款费用的30%作为定金，尾款在拍摄结束后5个工作日付清',
          '※ 产品交付时间15个工作日，以收到尾款开始计算',
          '※ 客户如需自行选片必须提前告知',
          '※ 我们不接受除高质量问题外任何原因的相册重做要求',
        ]
      },
			{
        id: 3,
        title: '圣诞主题',
        description: '捕捉秋日美景的每一个瞬间，金黄色的落叶、温暖的光影为婚礼增添浪漫的氛围。让这一季的秋色成为永恒的记忆，记录下新人的幸福时刻，感受岁月静好的秋日情怀。',
        category: '写真',
        imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg',
            '/static/works/kid_2.jpg',
            '/static/works/kid_3.jpg',
            '/static/works/kid_4.jpg',
            '/static/works/kid_5.jpg'
          ],
        price: 520,
        activityPrice: 450,
        activateStart: '2024-09-01',
        activateEnd: '2024-10-31',
        services: [
            '服务内容：8小时拍摄，全天不少于1500张原片，精修保底80张，当日预告不少于20张',
            '三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）高级相册一本，秋季主题定制相框一幅',
            '附加项目：当日如需快修加收RMB1500元，三机位不少于60张增加机位RMB4000/总监机位，RMB2500/普通机位',
            '※其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询'
        ],
        reminder: [
          '※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清',
          '※ 产品交付时间15个工作日，以收到尾款开始计算',
          '※ 客户如需自行选片必须提前告知',
          '※ 我们不接受除高质量问题外任何原因的相册重做要求',
        ]
      },
      ],
		};
	},
	computed: {
    filteredCategories() {
      // 过滤掉 id 为 0 的 "全部"目录
      return this.categories.filter(category => category.id !== 0);
    }
  },
	methods: {
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
	}
};
</script>


<style scoped>
@import url("index.css")
</style>
