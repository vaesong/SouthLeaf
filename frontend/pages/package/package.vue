<template>
  <view class="package-page">
		<view class="top-bar">
			<!-- 顶部分类状态栏 -->
			<view class="category-bar">
				<scroll-view scroll-x :scroll-left="scrollLeft" class="category-scroll">
					<view
						v-for="(category, index) in categories"
						:key="category.id"
            :id="`category-item-${index}`"
						:class="['category-item', { active: selectedCategory === category.name }]"
						@click="selectCategory(category, index)"
					>
						{{ category.name }}
					</view>
				</scroll-view>

				<!-- 搜索和切换按钮 -->
				<view class="icon-group">
					<ToggleLayoutButton :initialLayout="isGridLayout" @toggle-layout="updateLayoutClass" />
				</view>
			</view>

      <!-- 分隔条 -->
      <view class="divider"></view>

			<!-- 新的默认排序状态栏 -->
			<view class="status-bar">
        <view class="sort-options">
          <text
            :class="['sort-option', { active: activeSortOption === '默认排序' }]"
            @click="selectSortOption('默认排序')"
          >默认排序</text>

          <view
            :class="['sort-option', { active: activeSortOption === '价格' }]"
            @click="togglePriceSortOrder"
          >
            价格
            <view class="price-icons">
              <UniIcons
                type="up"
                size="10"
                :color="priceSortOrder === 'asc' ? '#1e90ff' : '#333'"
                :style="priceSortOrder === 'asc' ? 'font-weight: bold; transform: scale(1.1);' : ''"
                style="padding: 0; margin: 0; line-height: 1;"
                @click.stop="onIconClick('asc')"
              />
              <UniIcons
                type="down"
                size="10"
                :color="priceSortOrder === 'desc' ? '#1e90ff' : '#333'"
                :style="priceSortOrder === 'desc' ? 'font-weight: bold; transform: scale(1.1);' : ''"
                style="padding: 0; margin: 0; line-height: 1;"
                @click.stop="onIconClick('desc')"
              />
            </view>
          </view>

          <text
            :class="['sort-option', { active: activeSortOption === '热门' }]"
            @click="selectSortOption('热门')"
          >热门</text>
        </view>
      </view>
		</view>

    <!-- 页面内容 -->
    <view class="content">
      <!-- 切换布局展示 -->
      <view :class="['pkgs-content', layoutClass]">
        <view
          v-for="pkg in displayedPackages"
          :key="pkg.id"
          :class="['pkg-item', layoutClass]"
          @click="viewDetails(pkg)"
        >
          <image :src="pkg.imageSrc[0]" class="pkg-image" mode="aspectFill" />
          
          <view class="pkg-info">
            <text class="pkg-title">{{ pkg.title }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 底部信息区域 -->
    <FooterInfo />
  </view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue'
import ToggleLayoutButton from '@/components/ToggleLayoutButton/ToggleLayoutButton.vue';
import UniIcons from '@/uni_modules/uni-icons/components/uni-icons/uni-icons.vue';

export default {
  components: {
    FooterInfo,
    ToggleLayoutButton,
    UniIcons
  },
  data() {
    return {
      //后期接口，获取所有的目录类型
      categories: [
				{ id: 0, name: '全部', desc: 'ALL', icons: ['/static/works/han_2.jpg']},
        { id: 1, name: '儿童', desc: 'Child Portrait', icons: ['/static/works/han_2.jpg'] },
        { id: 2, name: '亲子全家福', desc: 'Family Portrait', icons: ['/static/works/han_2.jpg'] },
        { id: 3, name: '婚纱', desc: 'Bridal Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 4, name: '写真', desc: 'Portrait Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 5, name: '孕照', desc: 'Maternity Photography', icons: ['/static/works/han_2.jpg'] },
        { id: 6, name: '哈哈哈测试', desc: 'Passport Photo', icons: ['/static/works/han_2.jpg'] },
        { id: 7, name: '嘻嘻嘻测试', desc: 'Passport Photo', icons: ['/static/works/han_2.jpg'] },
        { id: 8, name: '呜呜呜', desc: 'Passport Photo', icons: ['/static/works/han_2.jpg'] },

      ],

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
      ],

      layoutClass: 'grid-layout',
      selectedCategory: '全部',
      activeSortOption: '默认排序',
      priceSortOrder: '', // 初始化为升序
      scrollLeft: 0
      
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
    displayedPackages() {
      // 1. 根据 selectedCategory 筛选数据
      let filteredPackages = this.selectedCategory === '全部'
        ? this.packages
        : this.packages.filter(pkg => pkg.category === this.selectedCategory);

      // // 2. 根据 activeSortOption 排序数据
      // if (this.activeSortOption === '价格') {
      //   filteredPackages = filteredPackages.sort((a, b) => {
      //     return this.priceSortOrder === 'asc' ? a.price - b.price : b.price - a.price;
      //   });
      // } else if (this.activeSortOption === '热门') {
      //   // 假设按活动价格降序排列表示热门程度
      //   filteredPackages = filteredPackages.sort((a, b) => b.activityPrice - a.activityPrice);
      // }
      
      return filteredPackages;
    },
    isGridLayout() {
      return this.layoutClass === 'grid-layout';
    }
  },
  methods: {
    selectCategory(category, index) {
      this.selectedCategory = category.name;
      this.scrollToLastVisibleCategory(index);
    },
    togglePriceSortOrder() {
      // 切换价格排序的顺序和状态
      this.activeSortOption = '价格';
      this.priceSortOrder = this.priceSortOrder === 'asc' ? 'desc' : 'asc';
    },
    selectSortOption(option) {
      this.activeSortOption = option;
      if (option !== '价格') {
        // 重置 priceSortOrder 状态
        this.priceSortOrder = '';
      }
    },
    onIconClick(order) {
      // 单独点击上升或下降图标时切换排序顺序
      this.priceSortOrder = order;
      this.activeSortOption = '价格';
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

    updateLayoutClass(newLayoutClass) {
      this.layoutClass = newLayoutClass === 'grid-layout' ? 'grid-layout' : 'list-layout';
    },
    viewDetails(pkg) {
      uni.navigateTo({
        url: `/pages/package/pkgdetail?id=${pkg.id}`, // 假设有一个详情页面
      });
    },
  }
};
</script>


<style scoped>
@import url("package.css")
</style>
